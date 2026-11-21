// Lightweight JSON persistence with debounced atomic writes + encrypted vault helpers.
const { app, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const dir = () => app.getPath('userData');

class JsonStore {
  constructor(name, defaults) {
    this.file = path.join(dir(), name + '.json');
    this.defaults = defaults;
    try { this.data = { ...structuredClone(defaults), ...JSON.parse(fs.readFileSync(this.file, 'utf8')) }; }
    catch { this.data = structuredClone(defaults); }
    if (Array.isArray(defaults)) { try { this.data = JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { this.data = []; } }
    this._t = null;
  }
  save() {
    clearTimeout(this._t);
    this._t = setTimeout(() => this.flush(), 400);
  }
  flush() {
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.data));
    fs.renameSync(tmp, this.file);
  }
}

class EncryptedStore {
  constructor(name, defaults) {
    this.file = path.join(dir(), name + '.enc');
    this.data = structuredClone(defaults);
    try {
      const buf = fs.readFileSync(this.file);
      const text = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8');
      this.data = JSON.parse(text);
    } catch { /* new vault */ }
  }
  flush() {
    const text = JSON.stringify(this.data);
    const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf8');
    fs.writeFileSync(this.file + '.tmp', buf);
    fs.renameSync(this.file + '.tmp', this.file);
  }
}

const DEFAULT_SETTINGS = {
  theme: 'dark',                 // dark | light | system
  searchEngine: 'cairn',         // cairn | duckduckgo | brave | bing
  searchAnswers: true,           // AI answers on Cairn Search
  startup: 'newtab',             // newtab | restore
  homepage: 'cairn://newtab',
  language: 'auto',              // assistant reply language: auto | en | hi | hinglish | ...
  sitePermissions: {},           // { origin: { geolocation: true, camera: false, ... } }
  agentConfirm: 'purchases',     // purchases | every-action
  agentMaxSteps: 40,
  shoppingCountry: 'IN',
  shield: true,                  // ad & tracker blocking
  doNotTrack: true,
  blockThirdPartyCookies: false,
  offerSavePasswords: true,
  autofillPasswords: true,
  autofillForms: true,
  downloadDir: '',
  askWhereToSave: false,
  voiceName: '',
  speechRate: 1,
  serverAuto: true,
  safeBrowsing: true,
  usageStats: true,              // anonymous counts only (telemetry.js)
  dohMode: 'off',
  dohUrl: '',
  serverUrl: process.env.CAIRN_SERVER || (app.isPackaged ? 'https://cairn-cloud.onrender.com' : 'http://localhost:8787'),
  panelWidth: 420,
  restoreTabs: [],
};

let stores;
function init() {
  stores = {
    settings: new JsonStore('settings', DEFAULT_SETTINGS),
    history: new JsonStore('history', []),
    bookmarks: new JsonStore('bookmarks', []),
    downloads: new JsonStore('downloads', []),
    threads: new JsonStore('threads', []),
    vault: new EncryptedStore('vault', { passwords: [], cloud: null }),
  };
  const sd = stores.settings.data;
  if (!sd.engineMigrated21) { if (sd.searchEngine === 'duckduckgo') sd.searchEngine = 'cairn'; sd.engineMigrated21 = true; stores.settings.save(); }
  return stores;
}
const get = () => stores;
function flushAll() { for (const s of Object.values(stores || {})) try { s.flush(); } catch {} }

module.exports = { init, get, flushAll, DEFAULT_SETTINGS };
