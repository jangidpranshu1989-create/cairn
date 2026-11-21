// Cairn Safe Browsing: a privacy-preserving lookup of phishing and malware hosts.
// Browsers never send the address they are visiting. They hash each host suffix (SHA-256), send only the first
// 32 bits of each hash, and compare the full hashes we return locally (the same k-anonymity idea as Google Safe Browsing v4).
import { Router } from 'express';
import crypto from 'node:crypto';
import rateLimit from 'express-rate-limit';

const FEEDS = [
  { kind: 'phishing', url: 'https://openphish.com/feed.txt', parse: (t) => t.split('\n').map(l => { try { return new URL(l.trim()).hostname; } catch { return null; } }) },
  { kind: 'phishing', url: 'https://phishing.army/download/phishing_army_blocklist.txt', parse: (t) => t.split('\n').filter(l => l && !l.startsWith('#')).map(l => l.trim()) },
  { kind: 'malware', url: 'https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt', parse: (t) => t.split('\n').filter(l => l && !l.startsWith('#')).map(l => l.trim().split(/\s+/)[1]) },
];
// Always-on test entries so the warning page can be checked end to end.
const TEST = { 'phishing.testsafebrowsing.cairn': 'phishing', 'malware.testsafebrowsing.cairn': 'malware', 'testsafebrowsing.appspot.com': 'malware' };
// Big sites that must never be flagged because of a stray feed entry on a shared host.
const NEVER = new Set(['google.com', 'www.google.com', 'github.com', 'raw.githubusercontent.com', 'drive.google.com', 'docs.google.com', 'sites.google.com',
  'dropbox.com', 'www.dropbox.com', 'onedrive.live.com', 'pplx.app', 'cairn.app', 'amazonaws.com', 's3.amazonaws.com', 'cloudflare.com', 'pages.dev', 'workers.dev', 'vercel.app', 'netlify.app', 'web.app', 'firebaseapp.com']);

const sha = (h) => crypto.createHash('sha256').update(h).digest('hex');
let table = new Map();   // prefix(8 hex) -> [{ hash, kind }]
let stats = { hosts: 0, updatedAt: 0, sources: {} };

function build(entries) {
  const t = new Map();
  for (const [host, kind] of entries) {
    const h = sha(host); const p = h.slice(0, 8);
    const arr = t.get(p) || []; if (!arr.some(x => x.hash === h)) arr.push({ hash: h, kind }); t.set(p, arr);
  }
  return t;
}

export async function refreshLists() {
  const entries = new Map(Object.entries(TEST));
  for (const f of FEEDS) {
    try {
      const r = await fetch(f.url, { signal: AbortSignal.timeout(20000), headers: { 'User-Agent': 'CairnSafeBrowsing/1.0' } });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const hosts = f.parse(await r.text()).filter(Boolean).map(h => h.toLowerCase().replace(/\.$/, '')).filter(h => !NEVER.has(h));
      hosts.forEach(h => { if (!entries.has(h)) entries.set(h, f.kind); });
      stats.sources[f.url.split('/')[2]] = hosts.length;
    } catch (e) { stats.sources[f.url.split('/')[2] + ' error'] = String(e.message || e); }
  }
  table = build(entries); stats.hosts = entries.size; stats.updatedAt = Date.now();
}

export function startSafety() {
  table = build(Object.entries(TEST));
  if (process.env.SAFE_BROWSING_FEEDS === '0') return;
  refreshLists().catch(() => {});
  setInterval(() => refreshLists().catch(() => {}), 6 * 3600_000).unref();
}

export const safetyRouter = Router();
safetyRouter.use(rateLimit({ windowMs: 60_000, limit: Number(process.env.SAFETY_RATE_PER_MIN || 3000), // high: mobile users share IPs (CGNAT)
   standardHeaders: true, legacyHeaders: false }));
safetyRouter.use((_req, res, next) => { res.set('Access-Control-Allow-Origin', '*'); res.set('Cache-Control', 'private, max-age=600'); next(); });
// GET /api/safety/lookup?p=ab12cd34,ef567890  -> { matches: [{ hash, kind }] }
safetyRouter.get('/lookup', (req, res) => {
  const ps = String(req.query.p || '').toLowerCase().split(',').filter(p => /^[0-9a-f]{8}$/.test(p)).slice(0, 12);
  const matches = ps.flatMap(p => table.get(p) || []);
  res.json({ matches, ttl: 1800 });
});
safetyRouter.get('/status', (_req, res) => res.json({ hosts: stats.hosts, updatedAt: stats.updatedAt, sources: stats.sources }));

// Same expansion the clients use: the host itself plus up to four parent domains (never a bare TLD).
export function hostSuffixes(host) {
  const h = String(host || '').toLowerCase().replace(/\.$/, '');
  if (/^\d+\.\d+\.\d+\.\d+$/.test(h) || h.includes(':')) return [h];
  const parts = h.split('.'); const out = [];
  for (let i = 0; i < parts.length - 1 && out.length < 5; i++) out.push(parts.slice(i).join('.'));
  return out;
}
