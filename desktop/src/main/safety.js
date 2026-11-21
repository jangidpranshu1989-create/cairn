// Cairn Safe Browsing (desktop). Same privacy model as Android: only 32-bit SHA-256 prefixes of the host and its parent
// domains leave the computer; full hashes are compared locally. Fails open if the server can't answer quickly.
const crypto = require('crypto');
const store = require('./store');

const cache = new Map();     // host -> { kind, at }
const allowed = new Set();   // "Proceed anyway" for this session
const TTL = 30 * 60_000;
const BACKOFF = 60_000;     // after a failed lookup, skip lookups for a minute instead of waiting on every page
let failedAt = 0;
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

function suffixes(host) {
  const h = String(host || '').toLowerCase().replace(/\.$/, '');
  if (/^\d+\.\d+\.\d+\.\d+$/.test(h) || h.includes(':')) return [h];
  const p = h.split('.'); const out = [];
  for (let i = 0; i < p.length - 1 && out.length < 5; i++) out.push(p.slice(i).join('.'));
  return out;
}

async function check(url) {
  let host; try { const u = new URL(url); if (!/^https?:$/.test(u.protocol)) return null; host = u.hostname.toLowerCase(); } catch { return null; }
  const s = store.get().settings.data;
  if (s.safeBrowsing === false || allowed.has(host) || host === 'localhost' || host.endsWith('.local')) return null;
  const c = cache.get(host); if (c && Date.now() - c.at < TTL) return c.kind;
  if (Date.now() - failedAt < BACKOFF) return null;
  const hashes = new Map(suffixes(host).map(h => [sha(h), h]));
  try {
    const base = s.serverUrl.replace(/\/$/, '');
    const r = await fetch(`${base}/api/safety/lookup?p=${[...hashes.keys()].map(h => h.slice(0, 8)).join(',')}`, { signal: AbortSignal.timeout(1800) });
    if (!r.ok) { failedAt = Date.now(); return null; }
    const { matches = [] } = await r.json();
    const hit = matches.find(m => hashes.has(m.hash));
    const kind = hit ? (hit.kind || 'phishing') : null;
    failedAt = 0;
    cache.set(host, { kind, at: Date.now() });
    return kind;
  } catch { failedAt = Date.now(); return null; }
}

const allow = (host) => allowed.add(String(host).toLowerCase());
module.exports = { check, allow, suffixes };
