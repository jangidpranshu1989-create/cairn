// Cairn cloud client: account session, realtime socket, phone approvals and file transfers.
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const store = require('./store');

const events = new EventEmitter();
const syncMod = () => require('./sync');
// Unlock (or create) the end-to-end encrypted sync vault right after a password sign-in. Never blocks login.
function afterPasswordAuth(email, password) { syncMod().setupKey(email, password).catch(e => events.emit('sync_error', e.message)); }
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
  afterPasswordAuth(email, password);
  return { user: j.user };
}
async function signup(payload) {
  const r = await fetch(base() + '/api/auth/signup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
  const j = await r.json();
  if (!r.ok) return { error: j.error, captcha: j.captcha };
  setSession({ accessToken: j.accessToken, refreshToken: j.refreshToken, user: j.user });
  if (payload?.email && payload?.password) afterPasswordAuth(payload.email, payload.password);
  return { user: j.user };
}
async function captcha() { return (await fetch(base() + '/api/auth/captcha')).json(); }
async function logout() { try { await api('/auth/logout', { method: 'POST' }); } catch {} try { syncMod().signOut(); } catch {} setSession(null); }

function handleAuthCallback(url) {
  const h = new URLSearchParams(new URL(url).hash.slice(1));
  if (!h.get('access')) return false;
  setSession({ accessToken: h.get('access'), refreshToken: h.get('refresh'), user: JSON.parse(h.get('user') || '{}') });
  return true;
}

async function connect() {
  clearTimeout(wsTimer);
  try { ws?.close(); } catch {}
  ws = null;
  if (!isSignedIn()) return;
  // One-time, 60-second ticket so the long-lived access token never appears in a URL (proxy/server logs).
  let q;
  try { q = 'ticket=' + encodeURIComponent((await api('/auth/ws-ticket', { method: 'POST' })).ticket); }
  catch { q = 'ticket='; } // no ticket (offline or session expired): the server refuses it and onclose retries with backoff
  if (!isSignedIn() || ws) return;
  const url = base().replace(/^http/, 'ws') + '/ws?' + q;
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

// ---------- "Continue with Cairn" one-tap ----------
function isAuthorizeUrl(url) {
  if (!isSignedIn()) return false;
  try { const u = new URL(url), b = new URL(base()); return u.origin === b.origin && u.pathname === b.pathname.replace(/\/$/, '') + '/oauth/authorize'; } catch { return false; }
}
async function oauthRequest(url) {
  const r = await fetch(base() + '/api/oauth/request' + new URL(url).search);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error || 'This sign-in request is not valid'), { redirect: j.redirect });
  return j;
}
async function oauthApprove(url, approve) {
  const params = Object.fromEntries(new URL(url).searchParams);
  return (await api('/oauth/approve', { method: 'POST', body: { ...params, approve, via: 'desktop' } })).redirect;
}

/** One-time link that signs the hosted account page in and opens passkey setup (or the passkey list). */
async function passkeySetupLink(view) { return (await api('/passkeys/setup-link', { method: 'POST', body: { from: 'desktop', ...(view ? { view } : {}) } })).url; }

module.exports = { passkeySetupLink, isAuthorizeUrl, oauthRequest, oauthApprove, events, api, isSignedIn, publicSession, login, signup, captcha, logout, handleAuthCallback, connect,
  requestApproval, decideLocally, sendToPhone, devices, pairStart, removeDevice, deleteAccount, base };
