// Cairn Analytics: privacy-friendly, first-party usage stats for the owner dashboard.
//
// What apps send (POST /api/t): a random install id (not linked to accounts, IPs or devices), the event name, platform,
// app version, OS version, language and time zone. No URLs, page titles, searches or personal data are ever sent.
// The website sends page path, referrer domain and UTM tags (no cookies, no fingerprinting).
//
// Storage: per-day aggregates in SQLite (snapshotted to Supabase with the rest of the DB) plus an in-memory presence map
// for the real-time view. Writes are buffered and flushed every 20 s so heartbeats stay cheap.
import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { db } from './db.js';

const IST = 5.5 * 3600_000;
const DAY = 86_400_000;
export const dayKey = (t = Date.now()) => new Date(t + IST).toISOString().slice(0, 10);

db.exec(`
CREATE TABLE IF NOT EXISTS t_installs (
  id TEXT PRIMARY KEY, platform TEXT, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, version TEXT, os TEXT,
  locale TEXT, tz TEXT, country TEXT, src TEXT, days INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS t_installs_first ON t_installs(first_seen);
CREATE TABLE IF NOT EXISTS t_daily (day TEXT NOT NULL, id TEXT NOT NULL, platform TEXT, PRIMARY KEY (day, id));
CREATE TABLE IF NOT EXISTS t_counts (day TEXT NOT NULL, platform TEXT NOT NULL, event TEXT NOT NULL, k TEXT NOT NULL DEFAULT '', n INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (day, platform, event, k));
`);

// Events the apps may send. Anything else is dropped, so the table can't be filled with junk.
const APP_EVENTS = new Set(['install', 'app_open', 'heartbeat', 'page_load', 'search', 'agent_run', 'agent_done', 'compare', 'voice', 'circle', 'pdf', 'download', 'crash', 'signin', 'shield_off', 'tab_new']);
const WEB_EVENTS = new Set(['web_view', 'web_download', 'web_click']);
const PLATFORMS = new Set(['android', 'desktop', 'web']);

const TZ_COUNTRY = {
  'Asia/Kolkata': 'IN', 'Asia/Calcutta': 'IN', 'Asia/Karachi': 'PK', 'Asia/Dhaka': 'BD', 'Asia/Kathmandu': 'NP', 'Asia/Katmandu': 'NP',
  'Asia/Colombo': 'LK', 'Asia/Dubai': 'AE', 'Asia/Riyadh': 'SA', 'Asia/Qatar': 'QA', 'Asia/Singapore': 'SG', 'Asia/Kuala_Lumpur': 'MY',
  'Asia/Jakarta': 'ID', 'Asia/Bangkok': 'TH', 'Asia/Manila': 'PH', 'Asia/Tokyo': 'JP', 'Asia/Seoul': 'KR', 'Asia/Shanghai': 'CN',
  'Asia/Hong_Kong': 'HK', 'Europe/London': 'GB', 'Europe/Berlin': 'DE', 'Europe/Paris': 'FR', 'Europe/Amsterdam': 'NL', 'Europe/Madrid': 'ES',
  'Europe/Rome': 'IT', 'Europe/Moscow': 'RU', 'Europe/Istanbul': 'TR', 'Africa/Lagos': 'NG', 'Africa/Nairobi': 'KE', 'Africa/Cairo': 'EG',
  'Africa/Johannesburg': 'ZA', 'America/New_York': 'US', 'America/Chicago': 'US', 'America/Denver': 'US', 'America/Los_Angeles': 'US',
  'America/Phoenix': 'US', 'America/Toronto': 'CA', 'America/Vancouver': 'CA', 'America/Sao_Paulo': 'BR', 'America/Mexico_City': 'MX',
  'Australia/Sydney': 'AU', 'Australia/Melbourne': 'AU', 'Pacific/Auckland': 'NZ',
};
const clip = (v, n = 40) => String(v ?? '').replace(/[\u0000-\u001f]/g, '').slice(0, n);
const countryOf = (req, tz) => clip(req.get('cf-ipcountry') || TZ_COUNTRY[tz] || (tz ? tz.split('/')[0] : '') || '', 20) || 'unknown';

// ---------- live presence (real-time) ----------
const ACTIVE_MS = 5 * 60_000;
const live = new Map();            // id -> { at, platform, version, country, screen }
const perMinute = [];              // [{ m: minuteStart, ids: Set }] last 30 minutes
function touchLive(id, info) {
  const at = Date.now();
  const prev = live.get(id) || {};
  for (const f of ['country', 'version']) if ((!info[f] || info[f] === 'unknown') && prev[f]) info[f] = prev[f];
  live.set(id, { ...prev, ...info, at });
  const m = Math.floor(at / 60_000) * 60_000;
  let last = perMinute[perMinute.length - 1];
  if (!last || last.m !== m) { last = { m, ids: new Set() }; perMinute.push(last); while (perMinute.length > 30) perMinute.shift(); }
  last.ids.add(id);
  if (live.size > 50_000) for (const [k, v] of live) if (at - v.at > ACTIVE_MS) live.delete(k);
}
export function realtime(at = Date.now()) {
  const active = [...live.values()].filter((v) => at - v.at < ACTIVE_MS);
  const by = (f) => active.reduce((o, v) => { const k = v[f] || 'unknown'; o[k] = (o[k] || 0) + 1; return o; }, {});
  const minutes = [];
  for (let i = 29; i >= 0; i--) {
    const m = Math.floor(at / 60_000) * 60_000 - i * 60_000;
    minutes.push({ m, n: perMinute.find((x) => x.m === m)?.ids.size || 0 });
  }
  return { active: active.length, apps: active.filter((v) => v.platform !== 'web').length, web: active.filter((v) => v.platform === 'web').length,
    byPlatform: by('platform'), byCountry: by('country'), byVersion: by('version'), byScreen: by('screen'), minutes };
}

// ---------- buffered writes ----------
let counts = new Map();            // "day|platform|event|k" -> n
let installs = new Map();          // id -> row
let daily = new Map();             // "day|id" -> platform
const bump = (day, platform, event, k = '', n = 1) => { const key = `${day}|${platform}|${event}|${clip(k, 80)}`; counts.set(key, (counts.get(key) || 0) + n); };

const upCount = db.prepare('INSERT INTO t_counts (day, platform, event, k, n) VALUES (?,?,?,?,?) ON CONFLICT(day, platform, event, k) DO UPDATE SET n = n + excluded.n');
const upDaily = db.prepare('INSERT OR IGNORE INTO t_daily (day, id, platform) VALUES (?,?,?)');
const upInstall = db.prepare(`INSERT INTO t_installs (id, platform, first_seen, last_seen, version, os, locale, tz, country, src, days)
  VALUES (@id,@platform,@first_seen,@last_seen,@version,@os,@locale,@tz,@country,@src,0)
  ON CONFLICT(id) DO UPDATE SET last_seen=excluded.last_seen, version=COALESCE(NULLIF(excluded.version,''),version), os=COALESCE(NULLIF(excluded.os,''),os),
  locale=COALESCE(NULLIF(excluded.locale,''),locale), tz=COALESCE(NULLIF(excluded.tz,''),tz), country=excluded.country`);
const incDays = db.prepare('UPDATE t_installs SET days = days + 1 WHERE id = ?');

export function flush() {
  if (!counts.size && !installs.size && !daily.size) return;
  const c = counts, i = installs, d = daily; counts = new Map(); installs = new Map(); daily = new Map();
  try {
    db.transaction(() => {
      // First activity of an install on a day: counted once per day even if the server restarted in between.
      for (const [key, v] of d) {
        const [day, id] = key.split('|');
        const isNew = !knownInstall.get(id);
        if (i.has(id)) upInstall.run(i.get(id));
        if (!upDaily.run(day, id, v.platform).changes) continue;
        incDays.run(id);
        if (isNew) upCount.run(day, v.platform, 'new', '', 1);
        upCount.run(day, v.platform, 'country', v.country, 1);
        if (v.platform !== 'web') { upCount.run(day, v.platform, 'version', v.version || 'unknown', 1); upCount.run(day, v.platform, 'os', v.os || 'unknown', 1); upCount.run(day, v.platform, 'locale', v.locale || 'unknown', 1); }
      }
      for (const row of i.values()) upInstall.run(row);
      for (const [key, n] of c) { const [day, platform, event, k] = key.split('|'); upCount.run(day, platform, event, k, n); }
    })();
  } catch (e) { console.error('[analytics] flush failed', e.message); }
}
setInterval(flush, 20_000).unref();
// Write buffered counts before shutdown; runs ahead of the Supabase snapshot handler, and exits if nothing else will.
for (const sig of ['SIGTERM', 'SIGINT']) process.prependListener(sig, function onSig() { flush(); if (process.listenerCount(sig) <= 1) process.exit(0); });

const seenToday = new Map();       // id -> day already queued as active
const knownInstall = db.prepare('SELECT 1 FROM t_installs WHERE id = ?');

export function record(req, b) {
  const id = clip(b.i, 40); const platform = clip(b.p, 10); const event = clip(b.e, 20);
  if (!/^[A-Za-z0-9_-]{12,40}$/.test(id) || !PLATFORMS.has(platform)) return false;
  if (!(platform === 'web' ? WEB_EVENTS : APP_EVENTS).has(event)) return false;
  const at = Date.now(); const day = dayKey(at);
  const tz = clip(b.tz, 40); const country = countryOf(req, tz); const version = clip(b.v, 20);
  const os = clip(b.os, 30); const locale = clip(b.l, 15);
  touchLive(id, { platform, version: platform === 'web' ? 'web' : version || 'unknown', country, screen: platform === 'web' ? clip(b.path, 60) || '/' : event === 'heartbeat' ? 'browsing' : event });
  if (event === 'heartbeat') bump(day, platform, 'minutes');
  else bump(day, platform, event, platform === 'web' && event === 'web_view' ? '' : clip(b.k, 60));
  const prev = installs.get(id);
  installs.set(id, { id, platform, first_seen: prev?.first_seen ?? at, last_seen: at, version: version || prev?.version || '', os: os || prev?.os || '', locale: locale || prev?.locale || '', tz: tz || prev?.tz || '', country,
    src: prev?.src || clip(b.src || b.ref || '', 60) });
  if (seenToday.get(id) !== day) {
    seenToday.set(id, day); if (seenToday.size > 200_000) seenToday.clear();
    daily.set(`${day}|${id}`, { platform, country, version, os, locale });
  }
  if (platform === 'web' && event === 'web_view') {
    bump(day, 'web', 'page', clip(b.path, 80) || '/');
    if (b.ref) bump(day, 'web', 'ref', clip(b.ref, 60));
    if (b.utm) bump(day, 'web', 'utm', clip(b.utm, 60));
    bump(day, 'web', 'device', clip(b.dev, 10) || 'unknown');
  }
  return true;
}

// ---------- public ingest ----------
export const ingestRouter = Router();
ingestRouter.use(rateLimit({ windowMs: 60_000, limit: Number(process.env.ANALYTICS_RATE_PER_MIN || 3000), standardHeaders: true, legacyHeaders: false }));
ingestRouter.post('/', (req, res) => {
  // Accepts a single event or { batch: [...] } (apps queue events while offline).
  const items = Array.isArray(req.body?.batch) ? req.body.batch.slice(0, 50) : [req.body || {}];
  let ok = 0; for (const it of items) if (it && typeof it === 'object' && record(req, it)) ok++;
  res.status(ok ? 204 : 400).end();
});

// ---------- owner dashboard data ----------
let ghCache = { at: 0, data: null };
async function githubDownloads() {
  if (Date.now() - ghCache.at < 10 * 60_000 && ghCache.data) return ghCache.data;
  try {
    const r = await fetch('https://api.github.com/repos/jangidpranshu1989-create/cairn/releases?per_page=50', { headers: { 'User-Agent': 'cairn-server', Accept: 'application/vnd.github+json', ...(process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}) }, signal: AbortSignal.timeout(6000) });
    if (!r.ok) throw new Error('github ' + r.status);
    const rel = await r.json();
    const kind = (n) => /\.apk$/i.test(n) ? 'android' : /\.(exe|msi)$/i.test(n) ? 'windows' : /\.(dmg|zip)$/i.test(n) && /mac/i.test(n) ? 'mac' : /\.(AppImage|deb|rpm)$/i.test(n) ? 'linux' : null;
    const totals = { android: 0, windows: 0, mac: 0, linux: 0 }; const releases = [];
    for (const x of rel) {
      if (x.draft || /^analytics/i.test(x.tag_name)) continue;
      const row = { tag: x.tag_name, at: x.published_at, android: 0, windows: 0, mac: 0, linux: 0 };
      for (const a of x.assets || []) { const k = kind(a.name); if (k) { row[k] += a.download_count; totals[k] += a.download_count; } }
      releases.push(row);
    }
    ghCache = { at: Date.now(), data: { totals, total: Object.values(totals).reduce((a, b) => a + b, 0), releases: releases.slice(0, 12) } };
  } catch (e) { if (!ghCache.data) ghCache = { at: Date.now() - 9 * 60_000, data: { error: e.message, totals: {}, total: 0, releases: [] } }; }
  return ghCache.data;
}

const all = (sql, ...a) => db.prepare(sql).all(...a);
const one = (sql, ...a) => db.prepare(sql).get(...a)?.n ?? 0;
const top = (since, platformSql, event, limit = 10) => all(`SELECT k, SUM(n) n FROM t_counts WHERE day >= ? AND event = ? ${platformSql} GROUP BY k ORDER BY n DESC LIMIT ${limit}`, since, event);

export async function overview(days = 30, at = Date.now()) {
  flush();
  const n = Math.max(7, Math.min(180, Number(days) || 30));
  const today = dayKey(at); const since = dayKey(at - (n - 1) * DAY);
  const app = "AND platform IN ('android','desktop')";
  const series = [];
  for (let i = n - 1; i >= 0; i--) series.push({ day: dayKey(at - i * DAY), users: 0, android: 0, desktop: 0, web: 0, newUsers: 0, sessions: 0, minutes: 0, webViews: 0, downloads: 0 });
  const idx = new Map(series.map((s, i) => [s.day, i]));
  for (const r of all('SELECT day, platform, COUNT(*) n FROM t_daily WHERE day >= ? GROUP BY day, platform', since)) {
    const s = series[idx.get(r.day)]; if (!s) continue; s[r.platform] = r.n; if (r.platform !== 'web') s.users += r.n;
  }
  for (const r of all("SELECT day, platform, event, SUM(n) n FROM t_counts WHERE day >= ? AND event IN ('new','app_open','minutes','web_view','web_download') GROUP BY day, platform, event", since)) {
    const s = series[idx.get(r.day)]; if (!s) continue;
    if (r.event === 'new' && r.platform !== 'web') s.newUsers += r.n;
    if (r.event === 'app_open') s.sessions += r.n;
    if (r.event === 'minutes' && r.platform !== 'web') s.minutes += r.n;
    if (r.event === 'web_view') s.webViews += r.n;
    if (r.event === 'web_download') s.downloads += r.n;
  }
  const distinct = (fromDay, plat = "platform != 'web'") => one(`SELECT COUNT(DISTINCT id) n FROM t_daily WHERE day >= ? AND ${plat}`, fromDay);
  // Retention: of the installs first seen on day D, how many came back on D+1 / D+7.
  const cohorts = [];
  for (let i = 14; i >= 1; i--) {
    const d0 = dayKey(at - i * DAY); const start = Date.parse(d0 + 'T00:00:00Z') - IST;
    const ids = all("SELECT id FROM t_installs WHERE platform != 'web' AND first_seen >= ? AND first_seen < ? LIMIT 5000", start, start + DAY).map((r) => r.id);
    if (!ids.length) { cohorts.push({ day: d0, size: 0, d1: null, d7: null }); continue; }
    const back = (k) => { if (i < k) return null; const dk = dayKey(start + k * DAY + IST / 2); const q = db.prepare(`SELECT COUNT(*) n FROM t_daily WHERE day = ? AND id IN (${ids.map(() => '?').join(',')})`); return q.get(dk, ...ids).n; };
    cohorts.push({ day: d0, size: ids.length, d1: back(1), d7: back(7) });
  }
  const events = all(`SELECT event k, SUM(n) n FROM t_counts WHERE day >= ? ${app} AND event NOT IN ('new','country','version','os','locale','minutes') GROUP BY event ORDER BY n DESC`, since);
  const totalUsers = one("SELECT COUNT(*) n FROM t_installs WHERE platform != 'web'");
  const minutesToday = series[series.length - 1].minutes; const usersToday = series[series.length - 1].users;
  return {
    generatedAt: at, today, days: n,
    realtime: realtime(at),
    kpis: {
      totalUsers, dau: usersToday, wau: distinct(dayKey(at - 6 * DAY)), mau: distinct(dayKey(at - 29 * DAY)),
      newToday: series[series.length - 1].newUsers, sessionsToday: series[series.length - 1].sessions,
      avgMinutes: usersToday ? Math.round((minutesToday / usersToday) * 10) / 10 : 0,
      webVisitorsToday: series[series.length - 1].web, webVisitors30d: distinct(dayKey(at - 29 * DAY), "platform = 'web'"),
      aiUsersToday: one('SELECT COUNT(DISTINCT user_id) n FROM usage WHERE day = ?', new Date(at).toISOString().slice(0, 10)),
      aiDevices: one('SELECT COUNT(*) n FROM anon_devices'), accounts: one('SELECT COUNT(*) n FROM users'),
      goal: 10_000,
    },
    series, cohorts, events,
    breakdown: {
      platform: all("SELECT platform k, COUNT(*) n FROM t_installs WHERE platform != 'web' GROUP BY platform ORDER BY n DESC"),
      version: top(since, app, 'version'), os: top(since, app, 'os'), locale: top(since, app, 'locale'),
      country: top(since, app, 'country'),
    },
    web: { pages: top(since, "AND platform = 'web'", 'page'), referrers: top(since, "AND platform = 'web'", 'ref'), utm: top(since, "AND platform = 'web'", 'utm'),
      devices: top(since, "AND platform = 'web'", 'device'), countries: top(since, "AND platform = 'web'", 'country'),
      downloadClicks: top(since, "AND platform = 'web'", 'web_download') },
    github: await githubDownloads(),
  };
}
