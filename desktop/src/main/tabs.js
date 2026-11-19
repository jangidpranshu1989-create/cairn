// Tab manager built on WebContentsView.
const { WebContentsView, Menu, clipboard, shell } = require('electron');
const path = require('node:path');
const { EventEmitter } = require('node:events');
const store = require('./store');
const { PARTITION } = require('./agent/background');

const SEARCH = {
  duckduckgo: 'https://duckduckgo.com/?q=',
  bing: 'https://www.bing.com/search?q=',
  brave: 'https://search.brave.com/search?q=',
};

function resolveInput(input) {
  const t = String(input || '').trim();
  if (!t) return 'cairn://newtab';
  if (/^(https?|cairn|file|about|data|view-source):/i.test(t)) return t;
  if (/^localhost(:\d+)?(\/.*)?$/i.test(t) || /^\d{1,3}(\.\d{1,3}){3}(:\d+)?(\/.*)?$/.test(t)) return 'http://' + t;
  if (!/\s/.test(t) && /^[^\s]+\.[a-z]{2,}(:\d+)?(\/.*)?$/i.test(t)) return 'https://' + t;
  return (SEARCH[store.get().settings.data.searchEngine] || SEARCH.duckduckgo) + encodeURIComponent(t);
}

class Tabs extends EventEmitter {
  constructor(win) {
    super();
    this.win = win;
    this.tabs = new Map();
    this.order = [];
    this.activeId = null;
    this.rect = { x: 0, y: 88, width: 800, height: 600 };
    this.frozen = false;
    this.closed = [];
    this.seq = 0;
  }

  get active() { return this.tabs.get(this.activeId); }

  create(url = 'cairn://newtab', { active = true, agent = false, openerIndex } = {}) {
    const id = ++this.seq;
    const view = new WebContentsView({
      webPreferences: {
        partition: PARTITION, sandbox: true, contextIsolation: true, nodeIntegration: false,
        preload: path.join(__dirname, '..', 'preload', 'page.js'),
        spellcheck: true, safeDialogs: true, autoplayPolicy: 'document-user-activation-required',
      },
    });
    view.setBackgroundColor('#00000000');
    const tab = { id, view, title: 'New Tab', url, favicon: '', loading: false, audible: false, agent, blocked: 0, zoom: 1, canBack: false, canFwd: false };
    this.tabs.set(id, tab);
    const at = openerIndex != null ? openerIndex + 1 : this.order.length;
    this.order.splice(at, 0, id);
    this.wire(tab);
    view.webContents.loadURL(resolveInput(url)).catch(() => {});
    if (active) this.activate(id); else this.emitUpdate();
    return tab;
  }

  wire(tab) {
    const wc = tab.view.webContents;
    const upd = () => {
      tab.url = wc.getURL(); tab.title = wc.getTitle() || tab.url;
      tab.canBack = wc.navigationHistory.canGoBack(); tab.canFwd = wc.navigationHistory.canGoForward();
      this.emitUpdate();
    };
    wc.on('page-title-updated', upd);
    wc.on('did-navigate', (_e, url) => { tab.blocked = 0; upd(); this.recordHistory(tab, url); });
    wc.on('did-navigate-in-page', upd);
    wc.on('did-start-loading', () => { tab.loading = true; this.emitUpdate(); });
    wc.on('did-stop-loading', () => { tab.loading = false; upd(); this.recordHistory(tab, wc.getURL()); });
    wc.on('page-favicon-updated', (_e, icons) => { tab.favicon = icons[0] || ''; this.emitUpdate(); });
    wc.on('audio-state-changed', (e) => { tab.audible = e.audible; this.emitUpdate(); });
    wc.on('found-in-page', (_e, r) => this.emit('found', r));
    wc.on('did-fail-load', (_e, code, desc, url, isMain) => {
      if (!isMain || code === -3) return;
      wc.loadURL(`cairn://error?code=${code}&desc=${encodeURIComponent(desc)}&url=${encodeURIComponent(url)}`).catch(() => {});
    });
    wc.on('render-process-gone', () => wc.loadURL(`cairn://error?code=crash&url=${encodeURIComponent(tab.url)}`).catch(() => {}));
    wc.setWindowOpenHandler(({ url, disposition }) => {
      if (/^https?:|^cairn:/.test(url)) this.create(url, { active: disposition !== 'background-tab', openerIndex: this.order.indexOf(tab.id) });
      return { action: 'deny' };
    });
    wc.on('will-navigate', (e, url) => {
      if (url.startsWith('cairn://auth-callback')) { e.preventDefault(); this.emit('auth-callback', url, tab); }
    });
    wc.on('will-redirect', (e, url) => {
      if (url.startsWith('cairn://auth-callback')) { e.preventDefault(); this.emit('auth-callback', url, tab); }
    });
    wc.on('context-menu', (_e, p) => this.contextMenu(tab, p));
    wc.on('before-input-event', (e, input) => this.emit('page-key', e, input, tab));
  }

  recordHistory(tab, url) {
    if (!/^https?:/.test(url)) return;
    const h = store.get().history;
    const last = h.data[h.data.length - 1];
    if (last && last.url === url) { last.title = tab.view.webContents.getTitle() || last.title; h.save(); return; }
    h.data.push({ url, title: tab.view.webContents.getTitle() || url, at: Date.now() });
    if (h.data.length > 20000) h.data.splice(0, h.data.length - 20000);
    h.save();
  }

  activate(id) {
    const tab = this.tabs.get(id);
    if (!tab) return;
    const prev = this.active;
    if (prev && prev !== tab) this.win.contentView.removeChildView(prev.view);
    this.activeId = id;
    if (!this.frozen) { this.win.contentView.addChildView(tab.view); tab.view.setBounds(this.rect); }
    if (!/^cairn:\/\/newtab/.test(tab.url)) tab.view.webContents.focus();
    this.emitUpdate();
  }

  close(id) {
    const tab = this.tabs.get(id);
    if (!tab) return;
    const idx = this.order.indexOf(id);
    if (/^https?:/.test(tab.url)) this.closed.push(tab.url);
    if (this.activeId === id) this.win.contentView.removeChildView(tab.view);
    this.tabs.delete(id);
    this.order.splice(idx, 1);
    tab.view.webContents.close();
    if (!this.order.length) { this.create(); return; }
    if (this.activeId === id) this.activate(this.order[Math.min(idx, this.order.length - 1)]);
    else this.emitUpdate();
  }

  reopenClosed() { const u = this.closed.pop(); if (u) this.create(u); }

  navigate(input, id = this.activeId) {
    const tab = this.tabs.get(id);
    if (!tab) return;
    tab.view.webContents.loadURL(resolveInput(input)).catch(() => {});
    tab.view.webContents.focus();
  }

  setRect(r) {
    this.rect = { x: Math.round(r.x), y: Math.round(r.y), width: Math.max(1, Math.round(r.width)), height: Math.max(1, Math.round(r.height)) };
    if (this.active && !this.frozen) this.active.view.setBounds(this.rect);
  }

  async freeze() {
    if (this.frozen || !this.active) return null;
    const img = await this.active.view.webContents.capturePage().catch(() => null);
    this.frozen = true;
    this.win.contentView.removeChildView(this.active.view);
    return img ? img.toDataURL() : null;
  }
  unfreeze() {
    if (!this.frozen) return;
    this.frozen = false;
    if (this.active) { this.win.contentView.addChildView(this.active.view); this.active.view.setBounds(this.rect); }
  }

  move(id, toIndex) {
    const i = this.order.indexOf(id);
    if (i < 0) return;
    this.order.splice(i, 1);
    this.order.splice(Math.max(0, Math.min(toIndex, this.order.length)), 0, id);
    this.emitUpdate();
  }

  list() {
    return this.order.map(id => {
      const t = this.tabs.get(id);
      return { id, title: t.title, url: t.url, favicon: t.favicon, loading: t.loading, audible: t.audible, agent: t.agent, blocked: t.blocked, canBack: t.canBack, canFwd: t.canFwd, active: id === this.activeId };
    });
  }
  emitUpdate() { this.emit('update', this.list()); }

  contextMenu(tab, p) {
    const wc = tab.view.webContents;
    const items = [];
    if (p.linkURL) items.push(
      { label: 'Open link in new tab', click: () => this.create(p.linkURL, { active: false, openerIndex: this.order.indexOf(tab.id) }) },
      { label: 'Copy link address', click: () => clipboard.writeText(p.linkURL) },
      { label: 'Ask Cairn about this link', click: () => this.emit('ask', `Open and summarize this link: ${p.linkURL}`) },
      { type: 'separator' });
    if (p.mediaType === 'image') items.push(
      { label: 'Open image in new tab', click: () => this.create(p.srcURL) },
      { label: 'Save image as…', click: () => wc.downloadURL(p.srcURL) },
      { label: 'Copy image', click: () => wc.copyImageAt(p.x, p.y) },
      { type: 'separator' });
    if (p.selectionText) items.push(
      { label: 'Copy', role: 'copy' },
      { label: `Ask Cairn: "${p.selectionText.slice(0, 24)}${p.selectionText.length > 24 ? '…' : ''}"`, click: () => this.emit('ask', `Explain this:\n"${p.selectionText.slice(0, 2000)}"`) },
      { label: 'Translate selection', click: () => this.emit('ask', `Translate this into English (or into my reply language if I have set one):\n"${p.selectionText.slice(0, 2000)}"`) },
      { label: 'Read selection aloud', click: () => this.emit('speak', p.selectionText) },
      { label: `Search for "${p.selectionText.slice(0, 20)}"`, click: () => this.create(resolveInput(p.selectionText)) },
      { type: 'separator' });
    if (p.isEditable) items.push({ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }, { type: 'separator' });
    if (!p.linkURL && !p.selectionText && !p.isEditable) items.push(
      { label: 'Back', enabled: wc.navigationHistory.canGoBack(), click: () => wc.navigationHistory.goBack() },
      { label: 'Forward', enabled: wc.navigationHistory.canGoForward(), click: () => wc.navigationHistory.goForward() },
      { label: 'Reload', click: () => wc.reload() },
      { type: 'separator' },
      { label: 'Summarize this page', click: () => this.emit('tool', 'summary') },
      { label: 'Make a mind map', click: () => this.emit('tool', 'mindmap') },
      { label: 'Read page aloud', click: () => this.emit('tool', 'read') },
      { label: 'Save page as PDF', click: () => this.emit('tool', 'pdf') },
      { label: 'Long screenshot', click: () => this.emit('tool', 'longshot') },
      { type: 'separator' },
      { label: 'View page source', click: () => this.create('view-source:' + wc.getURL()) });
    items.push({ label: 'Inspect', click: () => wc.inspectElement(p.x, p.y) });
    Menu.buildFromTemplate(items).popup({ window: this.win });
  }

  snapshotSession() { return this.order.map(id => this.tabs.get(id).url).filter(u => /^https?:/.test(u)); }
}

module.exports = { Tabs, resolveInput, SEARCH };
