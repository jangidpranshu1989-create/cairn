// Cairn Browser — main process
const { app, BrowserWindow, ipcMain, protocol, net, session, Menu, dialog, shell, nativeTheme, clipboard } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');

protocol.registerSchemesAsPrivileged([{ scheme: 'cairn', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: false, stream: true } }]);
if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
if (process.defaultApp) app.setAsDefaultProtocolClient('cairn', process.execPath, [path.resolve(process.argv[1])]);
else app.setAsDefaultProtocolClient('cairn');

const store = require('./store');
const search = require('./search');
let tabsMod, cloud, llm, passwords, pageTools, AgentCls;

const UI_DIR = path.join(__dirname, '..', 'ui');
const PAGES = new Set(['search', 'newtab', 'settings', 'history', 'bookmarks', 'downloads', 'passwords', 'account', 'mindmap', 'error', 'about', 'reader', 'unsafe']);
const isMac = process.platform === 'darwin';

let win, tabs, agent, agentTabId = null;
const pending = { asks: new Map(), confirms: new Map(), approvals: new Map(), pw: new Map(), perms: new Map() };
let downloads = new Map();

function chromeUA() {
  const v = process.versions.chrome;
  const os = isMac ? 'Macintosh; Intel Mac OS X 10_15_7' : process.platform === 'win32' ? 'Windows NT 10.0; Win64; x64' : 'X11; Linux x86_64';
  return `Mozilla/5.0 (${os}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${v.split('.')[0]}.0.0.0 Safari/537.36`;
}

function send(ch, data) { if (win && !win.isDestroyed()) win.webContents.send(ch, data); }

// ---------- cairn:// internal pages ----------
function registerProtocol() {
  const handler = (req) => {
    const u = new URL(req.url);
    if (u.host === 'search') return search.handle(u);
    if (u.host === 'auth-callback') return new Response('Signing in…', { headers: { 'content-type': 'text/plain' } });
    if (u.host === 'file') { // serve generated files (e.g. long screenshots) for preview
      const p = u.searchParams.get('p');
      if (p && within(p, pageTools.outDir())) return net.fetch(pathToFileURL(path.resolve(p)).toString());
      return new Response('Forbidden', { status: 403 });
    }
    let rel = u.pathname === '/' || !u.pathname ? `pages/${u.host}.html` : (u.pathname.startsWith('/') ? u.pathname.slice(1) : u.pathname);
    if (u.pathname === '/' && !PAGES.has(u.host)) rel = 'pages/error.html';
    const file = path.normalize(path.join(UI_DIR, rel));
    if (!within(file, UI_DIR)) return new Response('Forbidden', { status: 403 });
    return net.fetch(pathToFileURL(file).toString()).then((r) => {
      if (!/\.html$/.test(file)) return r;
      // Internal pages get a strict CSP: no remote scripts, no framing, no plugin content.
      const h = new Headers(r.headers);
      h.set('Content-Security-Policy', "default-src 'self' cairn:; script-src 'self' cairn: 'unsafe-inline'; style-src 'self' cairn: 'unsafe-inline'; img-src * data: blob:; media-src * data: blob:; connect-src *; font-src 'self' cairn: data:; frame-ancestors 'none'; object-src 'none'; base-uri 'none'");
      return new Response(r.body, { status: r.status, headers: h });
    });
  };
  protocol.handle('cairn', handler);
  session.fromPartition('persist:cairn').protocol.handle('cairn', handler);
}

// ---------- Session: shield, privacy headers, permissions, downloads ----------
async function setupSession() {
  const ses = session.fromPartition('persist:cairn');
  ses.setUserAgent(chromeUA());
  const s = () => store.get().settings.data;

  ses.webRequest.onBeforeSendHeaders((d, cb) => {
    const h = d.requestHeaders;
    if (s().doNotTrack) { h['DNT'] = '1'; h['Sec-GPC'] = '1'; }
    cb({ requestHeaders: h });
  });

  ses.setPermissionRequestHandler(async (wc, permission, cb, details) => {
    const always = ['clipboard-sanitized-write', 'fullscreen', 'pointerLock', 'window-management'];
    if (always.includes(permission)) return cb(true);
    if (wc.getURL().startsWith('cairn://')) return cb(true);
    const origin = (() => { try { return new URL(details.requestingUrl).host; } catch { return 'This site'; } })();
    const labels = { media: details.mediaTypes?.includes('video') ? 'use your camera' : 'use your microphone', geolocation: 'know your location', notifications: 'show notifications', 'clipboard-read': 'read your clipboard', midi: 'use MIDI devices', openExternal: 'open an external app' };
    const site = (() => { try { return new URL(details.requestingUrl).origin; } catch { return ''; } })();
    const key = permission === 'media' ? (details.mediaTypes?.includes('video') ? 'camera' : 'microphone') : permission;
    const st = store.get().settings;
    const saved = st.data.sitePermissions?.[site]?.[key];
    if (typeof saved === 'boolean') return cb(saved);
    if (!labels[permission]) return cb(false);
    const id = Math.random().toString(36).slice(2);
    const timer = setTimeout(() => { if (pending.perms.delete(id)) { cb(false); send('perm:close', id); } }, 60000);
    const tabId = tabs ? [...tabs.tabs.values()].find(t => t.view.webContents === wc)?.id : null;
    const cancel = () => { const p = pending.perms.get(id); if (!p) return; pending.perms.delete(id); clearTimeout(p.timer); p.cb(false); send('perm:close', id); };
    pending.perms.set(id, { cb, site, key, timer });
    wc.once('did-navigate', cancel); wc.once('destroyed', cancel);
    send('perm:prompt', { id, tabId, origin, text: `${origin} wants to ${labels[permission]}` });
  });

  ses.on('will-download', (_e, item, wc) => {
    const dir = s().downloadDir || app.getPath('downloads');
    if (!s().askWhereToSave) item.setSavePath(uniquePath(path.join(dir, item.getFilename())));
    const id = Date.now() + Math.random().toString(36).slice(2, 6);
    const rec = { id, filename: item.getFilename(), url: item.getURL(), total: item.getTotalBytes(), received: 0, state: 'progressing', path: item.getSavePath(), at: Date.now() };
    downloads.set(id, { rec, item });
    send('downloads:update', [...downloads.values()].map(d => d.rec));
    item.on('updated', (_ev, state) => { rec.received = item.getReceivedBytes(); rec.total = item.getTotalBytes(); rec.state = state; rec.path = item.getSavePath(); send('downloads:update', [...downloads.values()].map(d => d.rec)); });
    item.once('done', (_ev, state) => {
      rec.state = state; rec.path = item.getSavePath(); rec.received = item.getReceivedBytes();
      const d = store.get().downloads; d.data.unshift({ ...rec }); d.data.splice(500); d.save();
      send('downloads:update', [...downloads.values()].map(x => x.rec));
      if (state === 'completed') send('toast', { text: `Downloaded ${rec.filename}`, file: rec.path });
    });
  });

  // Ad & tracker shield (Ghostery engine, lists cached on disk)
  if (s().shield) {
    try {
      const { ElectronBlocker } = require('@ghostery/adblocker-electron');
      const cache = path.join(app.getPath('userData'), 'shield-engine.bin');
      const blocker = await ElectronBlocker.fromPrebuiltAdsAndTracking(fetch, {
        path: cache, read: fs.promises.readFile, write: fs.promises.writeFile });
      blocker.enableBlockingInSession(ses);
      blocker.on('request-blocked', (req) => {
        const t = [...tabs.tabs.values()].find(x => x.view.webContents.id === req.tabId);
        if (t) { t.blocked++; if (t.id === tabs.activeId) send('shield', { tabId: t.id, blocked: t.blocked }); }
        const st = store.get().settings; st.data.shieldTotal = (st.data.shieldTotal || 0) + 1; st.save();
      });
    } catch (e) { console.warn('Shield unavailable:', e.message); }
  }
  return ses;
}
function uniquePath(p) {
  if (!fs.existsSync(p)) return p;
  const { dir, name, ext } = path.parse(p);
  for (let i = 1; ; i++) { const c = path.join(dir, `${name} (${i})${ext}`); if (!fs.existsSync(c)) return c; }
}

// ---------- Window ----------
function createWindow() {
  const st = store.get().settings.data;
  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 820, minHeight: 560, show: false,
    backgroundColor: st.theme === 'light' ? '#F4F1EA' : '#0B0C0F',
    titleBarStyle: 'hidden',
    ...(isMac ? { trafficLightPosition: { x: 14, y: 13 } } : { titleBarOverlay: { color: '#00000000', symbolColor: st.theme === 'light' ? '#2a2925' : '#cfcbc2', height: 40 } }),
    icon: path.join(UI_DIR, 'icons', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, '..', 'preload', 'ui.js'), sandbox: true, contextIsolation: true, spellcheck: false },
  });
  win.loadFile(path.join(UI_DIR, 'index.html'), { query: { platform: process.platform } });
  win.once('ready-to-show', () => win.show());
  win.webContents.session.setPermissionRequestHandler((_wc, perm, cb) => cb(perm === 'media' || perm === 'clipboard-sanitized-write'));

  tabs = new tabsMod.Tabs(win);
  tabs.on('update', (list) => send('tabs:update', list));
  tabs.on('found', (r) => send('find:result', r));
  tabs.on('ask', (text) => send('agent:prefill', { text, run: true }));
  tabs.on('speak', (text) => send('agent:event', { type: 'speak', text }));
  tabs.on('tool', (t) => runPageTool(t));
  tabs.isAuthorizeUrl = (url) => cloud.isAuthorizeUrl(url);
  tabs.on('oauth-authorize', async (url, tab) => {
    const wc = tab.view.webContents;
    const fallback = () => { tab.oneTapBypass = url; wc.loadURL(url).catch(() => {}); };
    try {
      const req = await cloud.oauthRequest(url);
      if (String(req.prompt || '').split(' ').includes('login')) return fallback();
      const u = cloud.publicSession().user || {};
      const name = u.name || (u.email || '').split('@')[0] || 'you';
      const scopes = req.scopes.filter(s => s.id !== 'openid').map(s => '• ' + s.label);
      const { response } = await dialog.showMessageBox(win, {
        type: 'question', title: 'Continue with Cairn', buttons: [`Continue as ${name}`, 'Cancel', 'Use another account'], defaultId: 0, cancelId: 1, noLink: true,
        message: `Continue to ${req.client.name}?`,
        detail: `${req.client.domain}${req.client.firstParty ? ' · by Cairn' : ''}\n\nSigned in as ${u.email || name}.\n${req.client.name} will be able to:\n• Know who you are on Cairn\n${scopes.join('\n')}\n\nCairn never shares your password, passkeys or browsing data.`,
      });
      if (response === 2) return fallback();
      const to = await cloud.oauthApprove(url, response === 0);
      wc.loadURL(to).catch(() => {});
      if (response === 0) send('toast', { text: `Signed in to ${req.client.name} with Cairn` });
    } catch (e) {
      if (e.redirect) wc.loadURL(e.redirect).catch(() => {}); else fallback();
    }
  });
  tabs.on('auth-callback', (url, tab) => { if (cloud.handleAuthCallback(url)) { tab.view.webContents.loadURL('cairn://account').catch(() => {}); send('toast', { text: 'Signed in to Cairn' }); } });
  tabs.on('page-key', (e, input) => { if (input.type === 'keyDown' && input.key === 'Escape' && agent?.running) { /* allow page escape */ } });

  const restore = st.startup === 'restore' && st.restoreTabs?.length ? st.restoreTabs : null;
  if (restore) restore.forEach((u, i) => tabs.create(u, { active: i === restore.length - 1 }));
  else tabs.create('cairn://newtab');

  win.on('close', () => { st.restoreTabs = tabs.snapshotSession(); store.get().settings.flush(); });
  win.on('enter-full-screen', () => send('fullscreen', true));
  win.on('leave-full-screen', () => send('fullscreen', false));
  buildMenu();
}

// ---------- Agent wiring ----------
function agentTab() {
  let t = agentTabId && tabs.tabs.get(agentTabId);
  if (!t) {
    const a = tabs.active;
    // reuse the active tab only if it is a blank new tab, otherwise open a dedicated agent tab next to it
    if (a && /^cairn:\/\/newtab/.test(a.url)) { t = a; } else t = tabs.create('about:blank', { active: true, agent: true, openerIndex: tabs.order.indexOf(tabs.activeId) });
    t.agent = true; agentTabId = t.id; tabs.emitUpdate();
  }
  return t;
}

function makeAgent() {
  agent = new AgentCls({
    emit: (ev) => send('agent:event', ev),
    agentTab,
    newAgentTab: (url) => { const t = tabs.create(url, { active: true, agent: true }); agentTabId = t.id; return t; },
    pageContext: async () => { const a = tabs.active; return a && /^https?:/.test(a.url) ? { title: a.title, url: a.url } : null; },
    ask: (question, signal) => new Promise((resolve, reject) => {
      const id = Math.random().toString(36).slice(2);
      pending.asks.set(id, resolve);
      send('agent:event', { type: 'ask', id, question });
      signal.addEventListener('abort', () => { pending.asks.delete(id); reject(new Error('Stopped')); }, { once: true });
    }),
    confirmAction: (text, signal) => new Promise((resolve) => {
      const id = Math.random().toString(36).slice(2);
      pending.confirms.set(id, resolve);
      send('agent:event', { type: 'confirm', id, text });
      signal.addEventListener('abort', () => resolve(false), { once: true });
    }),
    approve: (req, signal) => approve(req, signal),
    openMindmap: (m) => openMindmap(m),
  });
}

/** Phone-first approval; falls back to an in-browser approval card when no phone is paired/signed in. */
async function approve(req, signal) {
  const local = () => new Promise((resolve) => {
    const id = Math.random().toString(36).slice(2);
    pending.approvals.set(id, resolve);
    send('agent:event', { type: 'approval', id, ...req, phone: false });
    signal.addEventListener('abort', () => resolve('cancelled'), { once: true });
  });
  if (!cloud.isSignedIn()) return local();
  try {
    send('agent:event', { type: 'approval', id: null, ...req, phone: true });
    const r = await cloud.requestApproval(req, signal);
    send('agent:event', { type: 'approval_result', status: r.status });
    if (r.status === 'no-phone') return local();
    return r.status;
  } catch (e) {
    if (signal.aborted) return 'cancelled';
    send('toast', { text: 'Phone approval unavailable: ' + e.message });
    return local();
  }
}

function openMindmap(m) {
  const id = 'mm' + Date.now();
  global.__mindmaps = global.__mindmaps || new Map();
  global.__mindmaps.set(id, m);
  tabs.create(`cairn://mindmap?id=${id}`);
}

async function runPageTool(kind) {
  const t = tabs.active;
  if (!t || !/^https?:/.test(t.url)) return send('toast', { text: 'Open a web page first' });
  const wc = t.view.webContents;
  send('tool:busy', { kind, busy: true });
  try {
    if (kind === 'summary') { const s = await pageTools.summarize(wc, {}); send('agent:event', { type: 'summary', ...s, standalone: true }); }
    else if (kind === 'mindmap') { openMindmap(await pageTools.mindmap(wc, {})); }
    else if (kind === 'pdf') { const f = await pageTools.savePdf(wc); send('toast', { text: 'Saved PDF', file: f }); }
    else if (kind === 'readerpdf') { const a = await pageTools.getArticle(wc); const f = await pageTools.htmlToPdf(pageTools.documentHtml(a.title, a.url, a.text), a.title); send('toast', { text: 'Saved clean article PDF', file: f }); }
    else if (kind === 'longshot') { const r = await pageTools.longScreenshot(wc); send('toast', { text: `Long screenshot saved (${r.width}×${r.height})`, file: r.file, image: true }); }
    else if (kind === 'shot') { const f = await pageTools.visibleScreenshot(wc); send('toast', { text: 'Screenshot saved', file: f, image: true }); }
    else if (kind === 'read') { const a = await pageTools.getArticle(wc); send('agent:event', { type: 'speak', text: `${a.title}.\n${a.text}`, lang: a.lang, title: a.title }); }
    else if (kind === 'reader') { const a = await pageTools.getArticle(wc); global.__reader = a; tabs.create('cairn://reader'); }
  } catch (e) {
    send('toast', { text: e.message === 'NO_AI' ? "Cairn's AI isn't reachable right now. Check your internet connection." : e.message, error: true });
  } finally { send('tool:busy', { kind, busy: false }); }
}

// ---------- IPC ----------
function isInternal(e) { try { return new URL(e.senderFrame.url).protocol === 'cairn:' || e.sender === win.webContents; } catch { return false; } }
function handle(ch, fn, { internal = false } = {}) {
  ipcMain.handle(ch, async (e, ...args) => {
    if (internal && !isInternal(e)) throw new Error('forbidden');
    return fn(e, ...args);
  });
}

function registerIpc() {
  // tabs & navigation
  handle('tabs:new', (_e, url) => { tabs.create(url || 'cairn://newtab'); });
  handle('tabs:close', (_e, id) => tabs.close(id ?? tabs.activeId));
  handle('tabs:activate', (_e, id) => tabs.activate(id));
  handle('tabs:move', (_e, id, i) => tabs.move(id, i));
  handle('tabs:list', () => tabs.list());
  handle('nav:go', (_e, input) => tabs.navigate(input));
  handle('nav:back', () => tabs.active?.view.webContents.navigationHistory.goBack());
  handle('nav:forward', () => tabs.active?.view.webContents.navigationHistory.goForward());
  handle('nav:reload', (_e, hard) => hard ? tabs.active?.view.webContents.reloadIgnoringCache() : tabs.active?.view.webContents.reload());
  handle('nav:stop', () => tabs.active?.view.webContents.stop());
  handle('nav:home', () => tabs.navigate(store.get().settings.data.homepage));
  handle('view:rect', (_e, r) => tabs.setRect(r));
  handle('view:freeze', () => tabs.freeze());
  handle('view:unfreeze', () => tabs.unfreeze());
  handle('view:focus', () => tabs.active?.view.webContents.focus());
  handle('find:start', (_e, text, opts) => text ? tabs.active?.view.webContents.findInPage(text, opts) : tabs.active?.view.webContents.stopFindInPage('clearSelection'));
  handle('find:stop', () => tabs.active?.view.webContents.stopFindInPage('clearSelection'));
  handle('zoom', (_e, dir) => {
    const wc = tabs.active?.view.webContents; if (!wc) return 1;
    const z = dir === 0 ? 1 : Math.min(3, Math.max(0.3, wc.getZoomFactor() + dir * 0.1));
    wc.setZoomFactor(z); return z;
  });
  handle('suggest', (_e, q) => suggest(q));

  // bookmarks
  handle('bookmarks:toggle', () => {
    const t = tabs.active; if (!t || !/^https?:/.test(t.url)) return false;
    const b = store.get().bookmarks; const i = b.data.findIndex(x => x.url === t.url);
    if (i >= 0) b.data.splice(i, 1); else b.data.unshift({ id: Date.now().toString(36), url: t.url, title: t.title, favicon: t.favicon, at: Date.now() });
    b.save(); return i < 0;
  });
  handle('bookmarks:has', (_e, url) => store.get().bookmarks.data.some(x => x.url === url));
  handle('bookmarks:list', () => store.get().bookmarks.data);
  handle('bookmarks:remove', (_e, id) => { const b = store.get().bookmarks; b.data = b.data.filter(x => x.id !== id); b.save(); }, { internal: true });
  handle('bookmarks:update', (_e, id, f) => { const b = store.get().bookmarks; const x = b.data.find(y => y.id === id); if (x) Object.assign(x, { title: f.title ?? x.title, url: f.url ?? x.url }); b.save(); }, { internal: true });

  // history
  handle('history:list', (_e, q = '', limit = 300) => {
    const h = store.get().history.data; const ql = q.toLowerCase(); const out = [];
    for (let i = h.length - 1; i >= 0 && out.length < limit; i--) if (!ql || h[i].url.toLowerCase().includes(ql) || (h[i].title || '').toLowerCase().includes(ql)) out.push({ ...h[i], i });
    return out;
  }, { internal: true });
  handle('history:remove', (_e, at) => { const h = store.get().history; h.data = h.data.filter(x => x.at !== at); h.save(); }, { internal: true });
  handle('history:clear', async (_e, opts = {}) => {
    const h = store.get().history; const since = opts.since || 0;
    h.data = since ? h.data.filter(x => x.at < since) : []; h.save();
    const ses = session.fromPartition('persist:cairn');
    if (opts.cookies) await ses.clearStorageData({ storages: ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'cachestorage'] });
    if (opts.cache) await ses.clearCache();
    if (opts.passwords) passwords.clearSince(since);
    return true;
  }, { internal: true });

  // downloads
  handle('downloads:list', () => {
    const live = [...downloads.values()].map(d => d.rec);
    const ids = new Set(live.map(x => x.id));
    return [...live, ...store.get().downloads.data.filter(x => !ids.has(x.id))];
  });
  handle('downloads:cancel', (_e, id) => downloads.get(id)?.item.cancel());
  handle('downloads:pause', (_e, id) => { const d = downloads.get(id); if (!d) return; d.item.isPaused() ? d.item.resume() : d.item.pause(); });
  handle('downloads:clear', () => { store.get().downloads.data = []; store.get().downloads.save(); downloads = new Map([...downloads].filter(([, d]) => d.rec.state === 'progressing')); });
  handle('file:open', (_e, f) => { if (safeFile(f)) shell.openPath(f); });
  handle('file:reveal', (_e, f) => { if (safeFile(f)) shell.showItemInFolder(f); });
  handle('file:toPhone', async (_e, f, folder) => { if (!safeFile(f)) throw new Error('forbidden'); return cloud.sendToPhone(f, folder); });

  // agent
  handle('agent:run', async (_e, text) => { if (!agent) makeAgent(); agent.run(text).catch(() => {}); return true; });
  handle('agent:stop', () => agent?.stop());
  handle('agent:reset', () => { agent?.reset(); agentTabId = null; });
  handle('agent:answer', (_e, id, text) => { pending.asks.get(id)?.(text); pending.asks.delete(id); });
  handle('agent:confirm', (_e, id, ok) => { pending.confirms.get(id)?.(ok); pending.confirms.delete(id); });
  handle('agent:approve', (_e, id, ok) => { pending.approvals.get(id)?.(ok ? 'approved' : 'denied'); pending.approvals.delete(id); });
  handle('agent:status', () => llm.status());
  handle('page:tool', (_e, kind) => runPageTool(kind));
  handle('voice:speak', async (_e, text) => llm.speech(text));
  handle('voice:transcribe', async (_e, buf, mime) => llm.transcribe(Buffer.from(buf), mime));
  handle('open:file-url', (_e, f) => safeFile(f) ? `cairn://file?p=${encodeURIComponent(f)}` : null);

  // settings
  handle('settings:get', () => ({ ...store.get().settings.data, groqKey: undefined, nvidiaKey: undefined }));
  handle('settings:set', (_e, patch) => {
    const s = store.get().settings; const v = store.get().vault;
    for (const [k, val] of Object.entries(patch || {})) {
      if (k in store.DEFAULT_SETTINGS) s.data[k] = val;
      if (k === 'serverUrl') s.data.serverAuto = false;
    }
    s.save();
    if ('theme' in patch) applyTheme();
    if ('serverUrl' in patch) cloud.connect();
    if ('dohMode' in patch || 'dohUrl' in patch) applySecureDns();
    send('settings:changed', s.data);
    return true;
  }, { internal: true });
  handle('settings:pickDir', async () => { const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] }); return r.canceled ? null : r.filePaths[0]; }, { internal: true });

  // cloud account & phone
  handle('cloud:session', () => cloud.publicSession());
  handle('sync:status', () => require('./sync').status(), { internal: true });
  handle('sync:now', () => require('./sync').syncNow(), { internal: true });
  handle('sync:setOn', (_e, on) => require('./sync').setOn(on), { internal: true });
  handle('sync:remoteTabs', () => require('./sync').remoteTabs(), { internal: true });
  handle('vault:encrypted', () => require('electron').safeStorage.isEncryptionAvailable());
  handle('cloud:captcha', () => cloud.captcha(), { internal: true });
  handle('cloud:login', (_e, email, pw, cap) => cloud.login(email, pw, cap), { internal: true });
  handle('cloud:signup', (_e, payload) => cloud.signup(payload), { internal: true });
  handle('cloud:logout', () => cloud.logout(), { internal: true });
  handle('cloud:me', () => cloud.api('/auth/me'), { internal: true });
  handle('cloud:devices', () => cloud.devices(), { internal: true });
  handle('cloud:pair', () => cloud.pairStart(), { internal: true });
  handle('cloud:removeDevice', (_e, id) => cloud.removeDevice(id), { internal: true });
  handle('cloud:deleteAccount', (_e, pw) => cloud.deleteAccount(pw), { internal: true });
  handle('cloud:base', () => cloud.base());
  handle('cloud:passkeyPage', () => { tabs.create(cloud.base() + '/accounts/?handoff=desktop'); });

  // passwords — management (internal pages only)
  handle('pw:all', () => passwords.all(), { internal: true });
  handle('pw:reveal', async (_e, id) => {
    const { response } = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Show password', 'Cancel'], defaultId: 1, cancelId: 1, message: 'Show this saved password?', detail: 'Make sure nobody is looking at your screen.' });
    return response === 0 ? passwords.reveal(id) : null;
  }, { internal: true });
  handle('pw:remove', (_e, id) => passwords.remove(id), { internal: true });
  handle('pw:update', (_e, id, f) => passwords.update(id, f), { internal: true });
  handle('pw:add', (_e, url, u, p) => passwords.save(passwords.originOf(url), u, p), { internal: true });
  handle('pw:export', async () => {
    const { response } = await dialog.showMessageBox(win, { type: 'warning', buttons: ['Export', 'Cancel'], cancelId: 1, message: 'Export passwords as an unencrypted CSV file?', detail: 'Anyone with the file can read your passwords.' });
    if (response) return false;
    const r = await dialog.showSaveDialog(win, { defaultPath: 'cairn-passwords.csv' });
    if (r.canceled) return false; fs.writeFileSync(r.filePath, passwords.exportCsv()); return true;
  }, { internal: true });
  handle('pw:import', async () => {
    const r = await dialog.showOpenDialog(win, { filters: [{ name: 'CSV', extensions: ['csv'] }], properties: ['openFile'] });
    return r.canceled ? 0 : passwords.importCsv(fs.readFileSync(r.filePaths[0], 'utf8'));
  }, { internal: true });

  // passwords — page side (origin taken from the sending frame, never from the page)
  const frameOrigin = (e) => { try { return new URL(e.senderFrame.url).origin; } catch { return ''; } };
  handle('pw:query', (e) => store.get().settings.data.autofillPasswords ? passwords.query(frameOrigin(e)) : []);
  handle('pw:fill', (e, id) => passwords.fill(frameOrigin(e), id));
  handle('pw:generate', () => passwords.generate());
  handle('pw:offer', (e, username, password) => {
    const origin = frameOrigin(e);
    if (!store.get().settings.data.offerSavePasswords || !/^https?:/.test(origin) || !password || passwords.isNever(origin)) return;
    const st = passwords.status(origin, username, password);
    if (st === 'same') return;
    const id = Math.random().toString(36).slice(2);
    pending.pw.set(id, { origin, username, password });
    send('pw:prompt', { id, origin, username, update: st === 'update' });
  });
  handle('safety:back', (e) => { const t = [...tabs.tabs.values()].find(x => x.view.webContents === e.sender); if (!t) return; const nh = t.view.webContents.navigationHistory; if (nh.canGoBack() && nh.getActiveIndex() > 1) nh.goToOffset(-2); else t.view.webContents.loadURL('cairn://newtab'); }, { internal: true });
  handle('safety:proceed', (e, url) => { try { const u = new URL(url); if (!/^https?:$/.test(u.protocol)) return; require('./safety').allow(u.hostname); e.sender.loadURL(url); } catch {} }, { internal: true });
  handle('perm:decide', (_e, id, allow, remember) => {
    const p = pending.perms.get(id); pending.perms.delete(id); if (!p) return;
    clearTimeout(p.timer); p.cb(!!allow);
    if (remember && p.site) { const st = store.get().settings; st.data.sitePermissions = { ...(st.data.sitePermissions || {}) }; st.data.sitePermissions[p.site] = { ...(st.data.sitePermissions[p.site] || {}), [p.key]: !!allow }; st.save(); }
  });
  handle('pw:decide', (_e, id, action) => {
    const p = pending.pw.get(id); pending.pw.delete(id); if (!p) return;
    if (action === 'save') passwords.save(p.origin, p.username, p.password);
    if (action === 'never') passwords.never(p.origin);
  });

  // internal page helpers
  handle('mindmap:get', (_e, id) => global.__mindmaps?.get(id) || null, { internal: true });
  handle('reader:get', () => global.__reader || null, { internal: true });
  handle('mindmap:save', async (_e, dataUrl, name) => {
    const f = path.join(pageTools.outDir(), `${String(name).replace(/[^\p{L}\p{N}]+/gu, '-').slice(0, 50)}-mindmap.png`);
    fs.writeFileSync(f, Buffer.from(dataUrl.split(',')[1], 'base64')); send('toast', { text: 'Mind map saved', file: f, image: true }); return f;
  }, { internal: true });
  handle('app:info', () => ({ version: app.getVersion(), electron: process.versions.electron, chrome: process.versions.chrome, platform: process.platform }));
  handle('app:menu', () => appMenu().popup({ window: win }));
  handle('clipboard:write', (_e, t) => clipboard.writeText(String(t)));
  handle('ui:theme', () => resolvedTheme());
  // New-tab "Ask Cairn" box → open the assistant panel and run the task
  handle('ui:ask', (_e, text) => { send('agent:prefill', { text: String(text).slice(0, 4000), run: true }); }, { internal: true });
  handle('history:top', (_e, n = 8) => {
    const counts = new Map();
    for (const x of store.get().history.data.slice(-3000)) {
      let o; try { o = new URL(x.url); } catch { continue; }
      if (!/^https?:$/.test(o.protocol)) continue;
      const k = o.hostname; const c = counts.get(k) || { url: o.origin, title: x.title, n: 0 }; c.n++; counts.set(k, c);
    }
    return [...counts.values()].sort((a, b) => b.n - a.n).slice(0, n);
  }, { internal: true });
}

// True only when `p` resolves to a path inside `dir` (blocks ../ traversal and sibling-prefix tricks like /out-evil).
function within(p, dir) { if (!p || !dir) return false; const r = path.resolve(String(p)); const d = path.resolve(String(dir)); return r === d || r.startsWith(d + path.sep); }
function safeFile(f) { if (!f) return false; const dl = store.get().settings.data.downloadDir; return within(f, pageTools.outDir()) || within(f, app.getPath('downloads')) || (!!dl && within(f, dl)); }

function suggest(q) {
  const ql = String(q).toLowerCase().trim();
  if (!ql) return [];
  const seen = new Set(); const out = [];
  for (const b of store.get().bookmarks.data) if ((b.url + b.title).toLowerCase().includes(ql) && !seen.has(b.url)) { seen.add(b.url); out.push({ type: 'bookmark', url: b.url, title: b.title }); if (out.length >= 3) break; }
  const h = store.get().history.data;
  for (let i = h.length - 1; i >= 0 && out.length < 7; i--) { const x = h[i]; if ((x.url + x.title).toLowerCase().includes(ql) && !seen.has(x.url)) { seen.add(x.url); out.push({ type: 'history', url: x.url, title: x.title }); } }
  return out;
}

function resolvedTheme() { const t = store.get().settings.data.theme; return t === 'system' ? (nativeTheme.shouldUseDarkColors ? 'dark' : 'light') : t; }
function applyTheme() {
  const t = resolvedTheme();
  nativeTheme.themeSource = store.get().settings.data.theme === 'system' ? 'system' : t;
  if (!isMac && win?.setTitleBarOverlay) win.setTitleBarOverlay({ color: '#00000000', symbolColor: t === 'light' ? '#2a2925' : '#cfcbc2', height: 40 });
  send('theme', t);
  for (const tab of tabs?.tabs?.values?.() || []) { try { if (tab.view.webContents.getURL().startsWith('cairn://')) tab.view.webContents.send('theme', t); } catch {} }
}

function appMenu() {
  const go = (u) => () => tabs.create(u);
  return Menu.buildFromTemplate([
    { label: 'New tab', accelerator: 'CmdOrCtrl+T', click: go('cairn://newtab') },
    { label: 'Reopen closed tab', accelerator: 'CmdOrCtrl+Shift+T', click: () => tabs.reopenClosed() },
    { type: 'separator' },
    { label: 'History', accelerator: 'CmdOrCtrl+Y', click: go('cairn://history') },
    { label: 'Bookmarks', click: go('cairn://bookmarks') },
    { label: 'Downloads', click: go('cairn://downloads') },
    { label: 'Passwords', click: go('cairn://passwords') },
    { type: 'separator' },
    { label: 'Summarize page', click: () => runPageTool('summary') },
    { label: 'Mind map', click: () => runPageTool('mindmap') },
    { label: 'Reader view', click: () => runPageTool('reader') },
    { label: 'Save as PDF', accelerator: 'CmdOrCtrl+Shift+S', click: () => runPageTool('pdf') },
    { label: 'Long screenshot', accelerator: 'CmdOrCtrl+Shift+L', click: () => runPageTool('longshot') },
    { label: 'Print…', accelerator: 'CmdOrCtrl+P', click: () => tabs.active?.view.webContents.print() },
    { type: 'separator' },
    { label: 'Account', click: go('cairn://account') },
    { label: 'Settings', accelerator: 'CmdOrCtrl+,', click: go('cairn://settings') },
    { label: 'About Cairn', click: go('cairn://about') },
    { type: 'separator' },
    { label: 'Developer tools', accelerator: isMac ? 'Alt+Cmd+I' : 'Ctrl+Shift+I', click: () => tabs.active?.view.webContents.toggleDevTools() },
    { role: 'quit' },
  ]);
}

function buildMenu() {
  const t = () => tabs;
  const tabAt = (n) => () => { const ids = t().order; t().activate(n === 9 ? ids[ids.length - 1] : ids[n - 1]); };
  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    { label: 'File', submenu: [
      { label: 'New Tab', accelerator: 'CmdOrCtrl+T', click: () => t().create() },
      { label: 'New Window', accelerator: 'CmdOrCtrl+N', click: () => t().create() },
      { label: 'Close Tab', accelerator: 'CmdOrCtrl+W', click: () => t().close(t().activeId) },
      { label: 'Reopen Closed Tab', accelerator: 'CmdOrCtrl+Shift+T', click: () => t().reopenClosed() },
      { label: 'Open Location', accelerator: 'CmdOrCtrl+L', click: () => send('focus:omnibox') },
      { label: 'Print', accelerator: 'CmdOrCtrl+P', click: () => t().active?.view.webContents.print() },
      { type: 'separator' }, isMac ? { role: 'close' } : { role: 'quit' },
    ] },
    { label: 'Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' },
      { label: 'Find', accelerator: 'CmdOrCtrl+F', click: () => send('find:open') }] },
    { label: 'View', submenu: [
      { label: 'Reload', accelerator: 'CmdOrCtrl+R', click: () => t().active?.view.webContents.reload() },
      { label: 'Hard Reload', accelerator: 'CmdOrCtrl+Shift+R', click: () => t().active?.view.webContents.reloadIgnoringCache() },
      { label: 'Zoom In', accelerator: 'CmdOrCtrl+=', click: () => send('zoom', 1) },
      { label: 'Zoom Out', accelerator: 'CmdOrCtrl+-', click: () => send('zoom', -1) },
      { label: 'Actual Size', accelerator: 'CmdOrCtrl+0', click: () => send('zoom', 0) },
      { label: 'Toggle Assistant', accelerator: 'CmdOrCtrl+K', click: () => send('agent:toggle') },
      { label: 'Developer Tools', accelerator: isMac ? 'Alt+Cmd+I' : 'Ctrl+Shift+I', click: () => t().active?.view.webContents.toggleDevTools() },
      { label: 'Browser UI DevTools', accelerator: 'CmdOrCtrl+Alt+Shift+I', click: () => win.webContents.openDevTools({ mode: 'detach' }) },
      { role: 'togglefullscreen' },
    ] },
    { label: 'History', submenu: [
      { label: 'Back', accelerator: isMac ? 'Cmd+[' : 'Alt+Left', click: () => t().active?.view.webContents.navigationHistory.goBack() },
      { label: 'Forward', accelerator: isMac ? 'Cmd+]' : 'Alt+Right', click: () => t().active?.view.webContents.navigationHistory.goForward() },
      { label: 'Show History', accelerator: 'CmdOrCtrl+Y', click: () => t().create('cairn://history') },
    ] },
    { label: 'Bookmarks', submenu: [{ label: 'Bookmark This Page', accelerator: 'CmdOrCtrl+D', click: () => send('bookmark:toggle') }, { label: 'Show Bookmarks', accelerator: 'CmdOrCtrl+Shift+O', click: () => t().create('cairn://bookmarks') }] },
    { label: 'Tab', submenu: [
      { label: 'Next Tab', accelerator: 'Ctrl+Tab', click: () => { const o = t().order; t().activate(o[(o.indexOf(t().activeId) + 1) % o.length]); } },
      { label: 'Previous Tab', accelerator: 'Ctrl+Shift+Tab', click: () => { const o = t().order; t().activate(o[(o.indexOf(t().activeId) - 1 + o.length) % o.length]); } },
      ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => ({ label: `Tab ${n}`, accelerator: `CmdOrCtrl+${n}`, click: tabAt(n), visible: false })),
    ] },
    { role: 'windowMenu' },
    { label: 'Help', submenu: [{ label: 'About Cairn', click: () => t().create('cairn://about') }, { label: 'Privacy Policy', click: () => t().create('https://cairn.app/privacy.html') }] },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ---------- Boot ----------
app.on('second-instance', (_e, argv) => {
  const deep = argv.find(a => a.startsWith('cairn://'));
  if (deep?.startsWith('cairn://auth-callback') && cloud.handleAuthCallback(deep)) send('toast', { text: 'Signed in to Cairn' });
  else if (deep) tabs?.create(deep);
  if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
});
app.on('open-url', (e, url) => { e.preventDefault(); if (url.startsWith('cairn://auth-callback') && cloud?.handleAuthCallback(url)) send('toast', { text: 'Signed in to Cairn' }); });

app.userAgentFallback = chromeUA();
// Built-in Secure DNS (DNS-over-HTTPS) for every page Cairn loads.
const DOH = { cloudflare: 'https://cloudflare-dns.com/dns-query', google: 'https://dns.google/dns-query' };
function applySecureDns() {
  const s = store.get().settings.data;
  const url = s.dohMode === 'custom' ? (/^https:\/\//.test(s.dohUrl || '') ? s.dohUrl.trim() : '') : DOH[s.dohMode];
  try {
    if (url) app.configureHostResolver({ secureDnsMode: 'secure', secureDnsServers: [url] });
    else app.configureHostResolver({ secureDnsMode: 'automatic', secureDnsServers: [] });
  } catch (e) { console.warn('Secure DNS:', e.message); }
}

// The Cairn server address is published in the public repo so every install finds the same account server.
async function discoverServer() {
  const s = store.get().settings;
  if (!app.isPackaged || process.env.CAIRN_SERVER || s.data.serverAuto === false) return;
  const r = await fetch('https://raw.githubusercontent.com/jangidpranshu1989-create/cairn/main/server.json', { cache: 'no-store', signal: AbortSignal.timeout(8000) });
  if (!r.ok) return;
  const url = String((await r.json()).server || '').trim().replace(/\/$/, '');
  if (!/^https:\/\//.test(url) || url === s.data.serverUrl) return;
  const signedIn = cloud.isSignedIn ? cloud.isSignedIn() : !!store.get().vault.data.cloud?.refreshToken;
  if (signedIn && s.data.serverUrl !== 'https://api.cairn.app') return;
  s.data.serverUrl = url; s.save();
  cloud.connect();
}

app.whenReady().then(async () => {
  store.init();
  applySecureDns();
  tabsMod = require('./tabs'); cloud = require('./cloud'); llm = require('./llm'); passwords = require('./passwords'); pageTools = require('./page-tools');
  AgentCls = require('./agent/agent').Agent;
  registerProtocol();
  registerIpc();
  createWindow();
  applyTheme();
  setupSession().catch(e => console.error(e));
  cloud.connect();
  discoverServer().catch(() => {});
  const sync = require('./sync');
  sync.init(cloud, tabs);
  sync.events.on('settings', () => { applyTheme(); send('settings:changed', true); });
  sync.events.on('status', (st) => send('sync:status', st));
  sync.events.on('tabs', (t) => send('sync:remoteTabs', t));
  cloud.events.on('sync_error', (m) => send('toast', { text: 'Sync: ' + m }));
  if (!require('electron').safeStorage.isEncryptionAvailable())
    setTimeout(() => send('toast', { text: 'Your OS keychain is unavailable, so saved passwords are stored unencrypted on this computer. Install/unlock a keyring (e.g. gnome-keyring) to protect them.' }), 4000);
  cloud.events.on('session', (s) => send('cloud:session', s));
  cloud.events.on('online', (o) => send('cloud:online', o));
  cloud.events.on('device_paired', (m) => send('toast', { text: `Phone paired: ${m.device.name}` }));
  cloud.events.on('transfer_delivered', (m) => send('toast', { text: `Saved on phone: ${m.savedTo}/${m.filename}` }));
  nativeTheme.on('updated', () => { if (store.get().settings.data.theme === 'system') applyTheme(); });
  if (app.isPackaged && process.env.CAIRN_NO_UPDATE !== '1') {
    try {
      const { autoUpdater } = require('electron-updater');
      autoUpdater.logger = null;
      autoUpdater.on('error', (e) => console.warn('[updater]', String(e?.message || e).split('\n')[0]));
      autoUpdater.checkForUpdatesAndNotify().catch(() => {});
    } catch {}
  }
});
app.on('window-all-closed', () => { store.flushAll(); app.quit(); });
app.on('before-quit', () => store.flushAll());
