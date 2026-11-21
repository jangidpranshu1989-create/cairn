// Anonymous usage stats (Settings → usageStats, on by default). Sends a random install id, event name, Cairn version,
// OS, language and time zone. Never URLs, titles, searches or anything typed. See server/src/analytics.js.
const crypto = require('crypto');
const os = require('os');
const { app, BrowserWindow } = require('electron');
const store = require('./store');

let queue = []; let timer = null;
const settings = () => store.get().settings;
function id() {
  const st = settings();
  if (!st.data.telemetryId) { st.data.telemetryId = crypto.randomBytes(16).toString('base64url'); st.save(); event('install'); }
  return st.data.telemetryId;
}
const on = () => settings().data.usageStats !== false;
function event(e, k = '') {
  if (!on()) return;
  queue.push({ i: id(), p: 'desktop', e, k: String(k).slice(0, 40), v: app.getVersion(), os: `${process.platform} ${os.release()}`.slice(0, 30),
    l: (app.getLocale() || '').slice(0, 15), tz: Intl.DateTimeFormat().resolvedOptions().timeZone || '' });
  if (queue.length > 100) queue = queue.slice(-100);
}
async function send() {
  if (!on()) { queue = []; return; }
  if (!queue.length) return;
  const batch = queue; queue = [];
  try {
    const base = String(settings().data.serverUrl || '').replace(/\/$/, '');
    const r = await fetch(base + '/api/t', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ batch }), signal: AbortSignal.timeout(10000) });
    if (r.status >= 500) throw new Error('server');
  } catch { queue = batch.filter((x) => x.e !== 'heartbeat').concat(queue).slice(-100); }
}
function start() {
  start.lastBlur = Date.now(); event('app_open'); send();
  timer = setInterval(() => { if (BrowserWindow.getFocusedWindow()) event('heartbeat'); send(); }, 60_000);
  timer.unref?.();
  app.on('browser-window-focus', () => { if (Date.now() - (start.lastBlur || 0) > 30 * 60_000) event('app_open'); });
  app.on('browser-window-blur', () => { start.lastBlur = Date.now(); });
}
module.exports = { start, event, send };
