// Cairn cloud client: account session, realtime socket, phone approvals and file transfers.
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const store = require('./store');

const events = new EventEmitter();
let ws = null, wsTimer = null, backoff = 1000;

const base = () => store.get().settings.data.serverUrl.replace(/\/$/, '');
const session = () => store.get().vault.data.cloud;
function setSession(s) { store.get().vault.data.cloud = s; store.get().vault.flush(); events.emit('session', publicSession()); connect(); }
const isSignedIn = () => !!session()?.refreshToken;
const publicSession = () => (session() ? { user: session().user, signedIn: true } : { signedIn: false });

async function refresh() {
  const s = session();
  if (!s) throw new Error('Not signed in');
  const r = await fetch(base() + '/api/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: s.refreshToken }) });
  if (!r.ok) { setSession(null); throw new Error('Session expired. Please sign in again.'); }
  const j = await r.json();
  store.get().vault.data.cloud = { ...s, accessToken: j.accessToken, refreshToken: j.refreshToken };
  store.get().vault.flush();
}

async function api(p, { method = 'GET', body, form, signal, retried } = {}) {
  const s = session();
  if (!s) throw new Error('Sign in to your Cairn account first (Settings → Account).');
  const headers = { Authorization: 'Bearer ' + s.accessToken };
  if (body) headers['Content-Type'] = 'application/json';
  const r = await fetch(base() + '/api' + p, { method, headers, body: form || (body ? JSON.stringify(body) : undefined), signal });
  if (r.status === 401 && !retried) { await refresh(); return api(p, { method, body, form, signal, retried: true }); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || `Server error ${r.status}`);
  return j;
}

async function login(email, password, captcha) {
  const r = await fetch(base() + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password, ...captcha }) });
  const j = await r.json();
  if (!r.ok) return { error: j.error, captcha: j.captcha };
  setSession({ accessToken: j.accessToken, refreshToken: j.refreshToken, user: j.user });
  return { user: j.user };
}
async function signup(payload) {
  const r = await fetch(base() + '/api/auth/signup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  const j = await r.json();
  if (!r.ok) return { error: j.error, captcha: j.captcha };
  setSession({ accessToken: j.accessToken, refreshToken: j.refreshToken, user: j.user });
  return { user: j.user };
}
async function captcha() { return (await fetch(base() + '/api/auth/captcha')).json(); }
async function logout() { try { await api('/auth/logout', { method: 'POST' }); } catch {} setSession(null); }

function handleAuthCallback(url) {
  const h = new URLSearchParams(new URL(url).hash.slice(1));
  if (!h.get('access')) return false;
  setSession({ accessToken: h.get('access'), refreshToken: h.get('refresh'), user: JSON.parse(h.get('user') || '{}') });
  return true;
}

function connect() {
  clearTimeout(wsTimer);
  try { ws?.close(); } catch {}
  ws = null;
  if (!isSignedIn()) return;
  const url = base().replace(/^http/, 'ws') + '/ws?token=' + encodeURIComponent(session().accessToken);
  const sock = new WebSocket(url);
  ws = sock;
  sock.onopen = () => { backoff = 1000; events.emit('online', true); };
  sock.onmessage = (e) => { try { const m = JSON.parse(e.data); events.emit('message', m); events.emit(m.type, m); } catch {} };
  sock.onclose = async (e) => {
    events.emit('online', false);
    if (ws !== sock) return;
    if (e.code === 4401) { try { await refresh(); } catch { return; } }
    wsTimer = setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 30_000);
  };
  sock.onerror = () => {};
}

/** Ask the paired phone to approve. Resolves 'approved' | 'denied' | 'expired' | 'no-phone'. */
async function requestApproval({ kind, title, details }, signal) {
  const { approval, pairedPhones } = await api('/approvals', { method: 'POST', body: { kind, title, details } });
  if (!pairedPhones) return { status: 'no-phone', id: approval.id };
  events.emit('approval_pending', approval);
  while (!signal?.aborted) {
    const a = await api('/approvals/' + approval.id + '/wait', { signal });
    if (a.status !== 'pending') return { status: a.status, id: a.id };
  }
  return { status: 'cancelled', id: approval.id };
}
async function decideLocally(id, approve) { return api('/approvals/' + id + '/decision', { method: 'POST', body: { approve, verified: false } }); }

async function sendToPhone(filePath, folder) {
  const fd = new FormData();
  const buf = fs.readFileSync(filePath);
  fd.append('file', new Blob([buf]), path.basename(filePath));
  fd.append('filename', path.basename(filePath));
  fd.append('folder', folder || 'Cairn');
  return api('/transfers', { method: 'POST', form: fd });
}

const devices = () => api('/devices');
const pairStart = () => api('/devices/pair/start', { method: 'POST' });
const removeDevice = (id) => api('/devices/' + id, { method: 'DELETE' });
async function deleteAccount(password) { await api('/auth/account', { method: 'DELETE', body: { password } }); setSession(null); return { ok: true }; }

module.exports = { events, api, isSignedIn, publicSession, login, signup, captcha, logout, handleAuthCallback, connect,
  requestApproval, decideLocally, sendToPhone, devices, pairStart, removeDevice, deleteAccount, base };
