// Cairn Sync (desktop): bookmarks, history, open tabs, passwords, passkeys, settings and profile follow the
// account to every signed-in device. Wire-compatible with the Android engine (vault/Sync.kt):
//   KEK        = PBKDF2-SHA256(password, "cairn-vault:" + email.lower, 310000, 32 bytes)
//   wrappedKey = "v1." + b64url(iv12 | AES-256-GCM(KEK, b64url(vaultKey)) | tag)
//   item id    = b64url(HMAC-SHA256(vaultKey, kind + "|" + naturalKey)).slice(0, 32)
//   item data  = "v1." + b64url(iv12 | AES-256-GCM(vaultKey, json) | tag)
// The server only ever sees opaque ids and ciphertext.
const crypto = require('node:crypto');
const os = require('node:os');
const { EventEmitter } = require('node:events');
const store = require('./store');

const events = new EventEmitter();
let cloud = null, tabsRef = null, timer = null, running = null;
const remoteTabs = [];

const b64u = (b) => Buffer.from(b).toString('base64url');
const unb64u = (s) => Buffer.from(s, 'base64url');
const v = () => store.get().vault;
const st = () => (v().data.sync ||= { on: true, key: null, rev: 0, items: {}, install: crypto.randomUUID(), last: 0 });

// Optional custom sync passphrase ("true zero-knowledge"): the vault key is wrapped with a key derived from a
// passphrase that never leaves the device, so even someone holding the account password + server data can't read it.
//   KEK = PBKDF2-SHA256(NFC(passphrase), "cairn-sync-passphrase:" + email.lower, 310000, 32 bytes)
const PASS_KDF = 'passphrase-pbkdf2-sha256-310000', PASS_SALT = 'cairn-sync-passphrase:';
function kdfPass(pass, email) { return crypto.pbkdf2Sync(Buffer.from(String(pass).normalize('NFC'), 'utf8'), PASS_SALT + String(email).trim().toLowerCase(), 310000, 32, 'sha256'); }
const kcvOf = (vaultKey) => b64u(crypto.createHmac('sha256', vaultKey).update('cairn-kcv').digest());
function kdf(password, email) { return crypto.pbkdf2Sync(password, 'cairn-vault:' + String(email).trim().toLowerCase(), 310000, 32, 'sha256'); }
function seal(k, plain) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', k, iv);
  const ct = Buffer.concat([c.update(plain, 'utf8'), c.final(), c.getAuthTag()]);
  return 'v1.' + b64u(Buffer.concat([iv, ct]));
}
function open(k, enc) {
  try {
    const b = unb64u(String(enc).replace(/^v1\./, ''));
    const d = crypto.createDecipheriv('aes-256-gcm', k, b.subarray(0, 12));
    d.setAuthTag(b.subarray(b.length - 16));
    return Buffer.concat([d.update(b.subarray(12, b.length - 16)), d.final()]).toString('utf8');
  } catch { return null; }
}
const key = () => (st().key ? unb64u(st().key) : null);
const itemId = (kind, natural) => b64u(crypto.createHmac('sha256', key()).update(kind + '|' + natural).digest()).slice(0, 32);
const hash = (j) => b64u(crypto.createHash('sha256').update(JSON.stringify(j)).digest()).slice(0, 22);

const enabled = () => !!(cloud?.isSignedIn() && st().on && st().key);

async function req(p, method = 'GET', body) { cloud ||= require('./cloud'); return cloud.api('/sync/v2' + p, { method, body }); }

/** After an email/password sign-in: fetch (or create) the account vault key. */
async function setupKey(email, password) {
  const kek = kdf(password, email);
  const k = await req('/key');
  let vaultKey;
  st().email = String(email).trim().toLowerCase();
  if (k.wrappedKey && /^passphrase/.test(k.kdf || '')) {
    // Locked with a custom sync passphrase: ask for it (it never goes to the server).
    Object.assign(st(), { key: null, needPassphrase: true, passphrase: true }); v().flush(); events.emit('status', status());
    return;
  }
  if (k.wrappedKey) {
    const raw = open(kek, k.wrappedKey);
    if (!raw) throw new Error('Your synced data is locked with an older password. Sign in on a device that still has it, or reset sync.');
    vaultKey = unb64u(raw);
  } else {
    vaultKey = crypto.randomBytes(32);
    try { await req('/key', 'PUT', { wrappedKey: seal(kek, b64u(vaultKey)), salt: 'cairn-vault:email', kdf: 'pbkdf2-sha256-310000', kcv: kcvOf(vaultKey) }); }
    catch (e) {   // another device created it first
      const again = await req('/key'); const raw = again.wrappedKey && open(kek, again.wrappedKey);
      if (!raw) throw e; vaultKey = unb64u(raw);
    }
  }
  Object.assign(st(), { key: b64u(vaultKey), rev: 0, items: {}, needPassphrase: false, passphrase: false }); v().flush();
  soon(0);
}

/** Unlocks sync on this device with the custom passphrase. */
async function unlockPassphrase(pass) {
  const email = st().email || cloud?.publicSession?.()?.user?.email; if (!email) throw new Error('Sign in first');
  const k = await req('/key');
  const raw = k.wrappedKey && open(kdfPass(pass, email), k.wrappedKey);
  if (!raw) throw new Error('That passphrase is not correct');
  Object.assign(st(), { key: raw, rev: 0, items: {}, needPassphrase: false, passphrase: true }); v().flush();
  soon(0); return status();
}
/** Turns the custom passphrase on (or changes it). Same vault key, so nothing is re-uploaded. */
async function setPassphrase(pass) {
  if (!key()) throw new Error('Sync is not unlocked on this computer');
  if (String(pass).normalize('NFC').length < 8) throw new Error('Use at least 8 characters');
  const email = st().email || cloud?.publicSession?.()?.user?.email; if (!email) throw new Error('Sign in first');
  const k = await req('/key');
  await req('/key/rewrap', 'POST', { wrappedKey: seal(kdfPass(pass, email), b64u(key())), salt: 'cairn-sync-passphrase:email', kdf: PASS_KDF, expect: k.wrappedKey, kcv: kcvOf(key()) });
  st().passphrase = true; v().flush(); return status();
}
/** Removes the passphrase: wraps the key with the account password again (needs the password). */
async function removePassphrase(password) {
  if (!key()) throw new Error('Sync is not unlocked on this computer');
  const email = st().email || cloud?.publicSession?.()?.user?.email; if (!email) throw new Error('Sign in first');
  const k = await req('/key');
  await req('/key/rewrap', 'POST', { wrappedKey: seal(kdf(password, email), b64u(key())), salt: 'cairn-vault:email', kdf: 'pbkdf2-sha256-310000', expect: k.wrappedKey, kcv: kcvOf(key()) });
  st().passphrase = false; v().flush(); return status();
}
/** Forgot the passphrase: start fresh with a new key (server copies are deleted; this computer re-uploads its data). */
async function resetSync(password) {
  const email = st().email || cloud?.publicSession?.()?.user?.email; if (!email) throw new Error('Sign in first');
  const vaultKey = crypto.randomBytes(32);
  await req('/key', 'PUT', { wrappedKey: seal(kdf(password, email), b64u(vaultKey)), salt: 'cairn-vault:email', kdf: 'pbkdf2-sha256-310000', kcv: kcvOf(vaultKey), replace: true });
  Object.assign(st(), { key: b64u(vaultKey), rev: 0, items: {}, needPassphrase: false, passphrase: false }); v().flush();
  soon(0); return status();
}

// ---------------- local snapshot ----------------
const SETTINGS = { theme: 'theme', searchEngine: 'searchEngine', searchAnswers: 'searchAnswers', shield: 'shield', block3p: 'blockThirdPartyCookies', dnt: 'doNotTrack', shoppingCountry: 'shoppingCountry' };

function local() {
  const out = new Map(); const put = (kind, k, j) => out.set(kind + '|' + k, { kind, k, j });
  const s = store.get();
  for (const b of s.bookmarks.data) if (/^https?:/.test(b.url)) put('bookmarks', b.url, { url: b.url, title: b.title || '', folder: b.folder || '', at: b.at || 0 });
  const hist = new Map();
  for (let i = s.history.data.length - 1; i >= 0 && hist.size < 500; i--) {
    const h = s.history.data[i]; const e = hist.get(h.url);
    if (e) e.visits++; else hist.set(h.url, { url: h.url, title: h.title || '', at: h.at, visits: 1 });
  }
  for (const h of hist.values()) put('history', h.url, h);
  for (const p of v().data.passwords || []) put('passwords', p.origin + '\n' + p.username, { origin: p.origin, username: p.username, password: p.password, at: p.updated || 0 });
  for (const pk of v().data.passkeys || []) put('passkeys', pk.credId, { ...pk, updated: 0 });
  for (const kind of ['cards', 'addresses']) for (const x of v().data[kind] || []) put(kind, x.id, { ...x });
  const sd = s.settings.data;
  for (const [k, local] of Object.entries(SETTINGS)) put('settings', k, { k, v: sd[local] });
  if (v().data.profileJson) put('profile', 'me', { json: v().data.profileJson });
  const open = (tabsRef?.list() || []).filter(t => /^https?:/.test(t.url)).slice(0, 60).map(t => ({ url: t.url, title: t.title || '' }));
  put('tabs', st().install, { install: st().install, device: deviceName(), tabs: open });
  return out;
}
function deviceName() { const p = { darwin: 'Mac', win32: 'Windows PC', linux: 'Linux PC' }[process.platform] || 'Computer'; return `${p} (${os.hostname().replace(/\.local$/, '')})`; }

function apply(kind, j) {
  const s = store.get();
  switch (kind) {
    case 'bookmarks': {
      const b = s.bookmarks; const x = b.data.find(y => y.url === j.url);
      if (x) Object.assign(x, { title: j.title || x.title, folder: j.folder || x.folder });
      else b.data.unshift({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6), url: j.url, title: j.title, folder: j.folder || '', at: j.at || Date.now() });
      b.save(); return j.url;
    }
    case 'history': {
      const h = s.history; if (!j.url || !/^https?:/.test(j.url)) return null;
      if (!h.data.some(x => x.url === j.url && x.at >= j.at)) {
        let i = h.data.length; while (i > 0 && h.data[i - 1].at > j.at) i--;
        h.data.splice(i, 0, { url: j.url, title: j.title, at: j.at }); h.save();
      }
      return j.url;
    }
    case 'passwords': {
      const list = v().data.passwords ||= []; const x = list.find(p => p.origin === j.origin && p.username === j.username);
      if (x) { if ((j.at || 0) >= (x.updated || 0)) Object.assign(x, { password: j.password, updated: j.at }); }
      else list.push({ id: crypto.randomUUID(), origin: j.origin, username: j.username, password: j.password, created: j.at || Date.now(), updated: j.at || Date.now(), lastUsed: 0 });
      v().flush(); return j.origin + '\n' + j.username;
    }
    case 'passkeys': {   // used by ./passkeys.js to sign in on this computer
      const list = v().data.passkeys ||= []; const { updated, ...pk } = j; const i = list.findIndex(p => p.credId === pk.credId);
      if (i >= 0) list[i] = pk; else list.push(pk); v().flush(); return pk.credId;
    }
    case 'cards': case 'addresses': return require('./autofill').applyRemote(kind, j);
    case 'settings': { const lk = SETTINGS[j.k]; if (lk && j.v !== undefined && j.v !== null) { s.settings.data[lk] = j.v; s.settings.save(); events.emit('settings'); } return j.k; }
    case 'profile': v().data.profileJson = j.json || ''; v().flush(); return 'me';
    case 'tabs': {
      if (j.install === st().install) return null;
      const i = remoteTabs.findIndex(r => r.device === j.device); if (i >= 0) remoteTabs.splice(i, 1);
      if (j.tabs?.length) remoteTabs.unshift({ device: j.device, at: j.at || 0, tabs: j.tabs });
      events.emit('tabs', remoteTabs); return null;
    }
  }
  return null;
}
function remove(kind, k) {
  const s = store.get();
  if (kind === 'bookmarks') { s.bookmarks.data = s.bookmarks.data.filter(b => b.url !== k); s.bookmarks.save(); }
  else if (kind === 'history') { s.history.data = s.history.data.filter(h => h.url !== k); s.history.save(); }
  else if (kind === 'passwords') { const [o, u] = k.split('\n'); v().data.passwords = (v().data.passwords || []).filter(p => !(p.origin === o && p.username === u)); v().flush(); }
  else if (kind === 'passkeys') { v().data.passkeys = (v().data.passkeys || []).filter(p => p.credId !== k); v().flush(); }
  else if (kind === 'cards' || kind === 'addresses') require('./autofill').remove(kind, k);
}

async function pull() {
  const S = st(); const k = key(); let more = true;
  while (more) {
    const r = await req('/changes?since=' + S.rev);
    const items = r.items || [];
    for (const it of items) {
      if (it.deleted) { const s = S.items[it.id]; if (s) remove(s[0], s[1]); delete S.items[it.id]; continue; }
      const txt = open(k, it.data); if (!txt) continue;
      let j; try { j = JSON.parse(txt); } catch { continue; }
      const nk = apply(it.kind, j); if (nk != null) S.items[it.id] = [it.kind, nk, ''];
    }
    S.rev = r.rev ?? S.rev; more = !!r.more && items.length > 0;
  }
  const snap = local();
  for (const [id, s] of Object.entries(S.items)) if (!s[2]) { const e = snap.get(s[0] + '|' + s[1]); if (e) S.items[id] = [s[0], s[1], hash(e.j)]; }
}

async function push() {
  const S = st(); const k = key(); const now = Date.now(); const next = {}; const items = [];
  for (const { kind, k: nk, j } of local().values()) {
    const id = itemId(kind, nk); const h = hash(j); next[id] = [kind, nk, h];
    if (S.items[id]?.[2] === h) continue;
    items.push({ kind, id, updatedAt: now, data: seal(k, JSON.stringify(kind === 'tabs' ? { ...j, at: now } : j)) });
  }
  for (const [id, s] of Object.entries(S.items)) if (!next[id] && s[0] !== 'history') items.push({ kind: s[0], id, updatedAt: now, deleted: true });
  for (let i = 0; i < items.length; i += 500) {
    const r = await req('/changes', 'POST', { items: items.slice(i, i + 500) });
    S.rev = Math.max(S.rev, r.rev || 0);
  }
  for (const [id, s] of Object.entries(S.items)) if (s[0] === 'history' && !next[id]) next[id] = s;
  S.items = next;
}

async function syncNow() {
  if (!enabled()) return { ok: false, error: st().key ? 'Sync is off' : 'Sign in with your password to turn on sync' };
  if (running) return running;
  running = (async () => {
    try { await pull(); await push(); st().last = Date.now(); st().error = ''; return { ok: true }; }
    catch (e) { st().error = e.message; return { ok: false, error: e.message }; }
    finally { v().flush(); running = null; events.emit('status', status()); }
  })();
  return running;
}
function soon(ms = 2500) { if (!enabled()) return; clearTimeout(timer); timer = setTimeout(() => syncNow().catch(() => {}), ms); }
const status = () => ({ on: st().on, ready: !!st().key, last: st().last || 0, error: st().error || '', signedIn: !!cloud?.isSignedIn(), passphrase: !!st().passphrase, needPassphrase: !!st().needPassphrase });
function setOn(on) { st().on = !!on; v().flush(); if (on) soon(0); return status(); }
function signOut() { clearTimeout(timer); Object.assign(st(), { key: null, rev: 0, items: {}, needPassphrase: false, passphrase: false, email: '' }); remoteTabs.length = 0; v().flush(); }

function init(cloudMod, tabs) {
  cloud = cloudMod; tabsRef = tabs;
  cloud.events.on('sync_changed', () => soon(500));
  cloud.events.on('online', (o) => { if (o) soon(1000); });
  // Local edits: watch the stores' debounced saves instead of touching every call site.
  for (const name of ['bookmarks', 'history', 'settings']) {
    const s = store.get()[name]; const orig = s.flush.bind(s);
    s.flush = () => { orig(); soon(); };
  }
  const vs = v(); const vf = vs.flush.bind(vs); let inner = false;
  vs.flush = () => { vf(); if (!inner && !running) { inner = true; soon(); inner = false; } };
  tabs?.on?.('update', () => soon(8000));
  setInterval(() => soon(0), 10 * 60 * 1000).unref?.();
  soon(3000);
}

module.exports = { init, setupKey, unlockPassphrase, setPassphrase, removePassphrase, resetSync, syncNow, soon, status, setOn, signOut, remoteTabs: () => remoteTabs, events, _test: { kdf, kdfPass, seal, open, kcvOf } };
