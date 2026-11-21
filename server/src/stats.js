// Private growth stats for the owner: how many installs (anonymous AI device tokens), accounts and active users.
// Counts only — no IPs, emails or ids leave this endpoint. Needs the owner key (see authorized()).
//   curl -H "Authorization: Bearer $ADMIN_TOKEN" https://<server>/api/admin/stats
import crypto from 'node:crypto';
import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { db } from './db.js';
import { overview } from './analytics.js';

export const statsRouter = Router();
statsRouter.use(rateLimit({ windowMs: 60_000, limit: 30, standardHeaders: true, legacyHeaders: false }));

// The owner's dashboard key. ADMIN_TOKEN (env) works if set; otherwise the key whose SHA-256 is below.
// Only the hash is public, and the key is 256 random bits, so it can't be guessed from it.
const BUILTIN_HASH = process.env.ADMIN_TOKEN_SHA256 || 'c5ca4cade272cb918879edcc129fdd3a92a9551563a79e5baf221238a63be8cf';
const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');
export function authorized(req) {
  const got = String(req.get('authorization') || '').replace(/^Bearer\s+/i, '').trim();
  if (got.length < 16) return false;
  const want = process.env.ADMIN_TOKEN || '';
  const eq = (x, y) => crypto.timingSafeEqual(Buffer.from(x), Buffer.from(y));
  if (want.length >= 16 && eq(sha(got), sha(want))) return true;
  return /^[a-f0-9]{64}$/.test(BUILTIN_HASH) && eq(sha(got), BUILTIN_HASH);
}

const DAY = 86_400_000;
const isoDay = (t) => new Date(t).toISOString().slice(0, 10);

export function computeStats(days = 30, at = Date.now()) {
  const n = Math.max(1, Math.min(365, Number(days) || 30));
  const since = at - n * DAY;
  const one = (sql, ...args) => db.prepare(sql).get(...args)?.n ?? 0;
  const daily = new Map();
  for (let i = n - 1; i >= 0; i--) daily.set(isoDay(at - i * DAY), { day: isoDay(at - i * DAY), installs: 0, accounts: 0, activeAi: 0 });
  for (const r of db.prepare('SELECT created_at FROM anon_devices WHERE created_at >= ?').all(since)) daily.get(isoDay(r.created_at)) && daily.get(isoDay(r.created_at)).installs++;
  for (const r of db.prepare('SELECT created_at FROM users WHERE created_at >= ?').all(since)) daily.get(isoDay(r.created_at)) && daily.get(isoDay(r.created_at)).accounts++;
  for (const r of db.prepare('SELECT day, COUNT(DISTINCT user_id) n FROM usage WHERE day >= ? GROUP BY day').all(isoDay(since))) if (daily.has(r.day)) daily.get(r.day).activeAi = r.n;
  const platforms = Object.fromEntries(db.prepare("SELECT COALESCE(NULLIF(platform,''),'unknown') p, COUNT(*) n FROM anon_devices GROUP BY p").all().map((r) => [r.p, r.n]));
  return {
    totals: {
      installs: one('SELECT COUNT(*) n FROM anon_devices'),
      accounts: one('SELECT COUNT(*) n FROM users'),
      platforms,
    },
    last24h: { installs: one('SELECT COUNT(*) n FROM anon_devices WHERE created_at >= ?', at - DAY), accounts: one('SELECT COUNT(*) n FROM users WHERE created_at >= ?', at - DAY) },
    activeAi: {
      today: one('SELECT COUNT(DISTINCT user_id) n FROM usage WHERE day = ?', isoDay(at)),
      last7d: one('SELECT COUNT(DISTINCT user_id) n FROM usage WHERE day >= ?', isoDay(at - 6 * DAY)),
      last30d: one('SELECT COUNT(DISTINCT user_id) n FROM usage WHERE day >= ?', isoDay(at - 29 * DAY)),
    },
    daily: [...daily.values()],
  };
}

statsRouter.get('/stats', (req, res) => {
  if (!authorized(req)) return res.status(404).json({ error: 'Not found' });
  res.set('Cache-Control', 'no-store').json(computeStats(req.query.days));
});

// Full dashboard payload (real-time, users, retention, website, downloads).
statsRouter.get('/analytics', async (req, res, next) => {
  if (!authorized(req)) return res.status(401).json({ error: 'Wrong dashboard key' });
  try { res.set('Cache-Control', 'no-store').json(await overview(req.query.days)); } catch (e) { next(e); }
});
