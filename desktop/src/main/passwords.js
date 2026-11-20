// Password manager: OS-encrypted vault (safeStorage), save prompts, origin-bound autofill, CSV import/export.
const crypto = require('node:crypto');
const store = require('./store');

const vault = () => store.get().vault;
const list = () => vault().data.passwords || (vault().data.passwords = []);
const originOf = (u) => { try { return new URL(u).origin; } catch { return ''; } };
// Treat www.example.com and example.com as the same site
const siteKey = (origin) => { try { const h = new URL(origin).hostname.replace(/^www\./, ''); return h; } catch { return origin; } };

function forOrigin(origin) {
  const k = siteKey(origin);
  return list().filter(p => siteKey(p.origin) === k);
}

function query(origin) { return forOrigin(origin).map(p => ({ id: p.id, username: p.username })); }

function fill(origin, id) {
  const p = list().find(x => x.id === id);
  if (!p || siteKey(p.origin) !== siteKey(origin)) return null; // never cross-site
  p.lastUsed = Date.now(); vault().flush();
  return { username: p.username, password: p.password };
}

function status(origin, username, password) {
  const ex = forOrigin(origin).find(p => p.username === username);
  if (!ex) return 'new';
  return ex.password === password ? 'same' : 'update';
}

function save(origin, username, password) {
  const ex = forOrigin(origin).find(p => p.username === username);
  if (ex) { ex.password = password; ex.updated = Date.now(); }
  else list().push({ id: crypto.randomUUID(), origin, username, password, created: Date.now(), updated: Date.now(), lastUsed: 0 });
  vault().flush();
}

function never(origin) { (vault().data.never ||= []).push(siteKey(origin)); vault().flush(); }
const isNever = (origin) => (vault().data.never || []).includes(siteKey(origin));

function clearSince(since = 0) { vault().data.passwords = list().filter(p => (p.updated || p.created || 0) < since); vault().flush(); }
function remove(id) { vault().data.passwords = list().filter(p => p.id !== id); vault().flush(); }
function update(id, fields) { const p = list().find(x => x.id === id); if (p) { Object.assign(p, fields, { updated: Date.now() }); vault().flush(); } }

function all() { return list().map(p => ({ id: p.id, origin: p.origin, username: p.username, updated: p.updated, weak: p.password.length < 10, reused: list().filter(o => o.password === p.password).length > 1 })); }
function reveal(id) { return list().find(p => p.id === id)?.password || null; }

function exportCsv() {
  const q = (s) => `"${String(s).replace(/"/g, '""')}"`;
  return 'name,url,username,password\n' + list().map(p => [siteKey(p.origin), p.origin, p.username, p.password].map(q).join(',')).join('\n');
}
function importCsv(text) {
  const rows = parseCsv(text);
  const head = rows.shift()?.map(h => h.toLowerCase()) || [];
  const iu = head.indexOf('url'), iun = head.indexOf('username'), ip = head.indexOf('password');
  let n = 0;
  for (const r of rows) {
    const origin = originOf(r[iu]); if (!origin || !r[ip]) continue;
    save(origin, r[iun] || '', r[ip]); n++;
  }
  return n;
}
function parseCsv(t) {
  const rows = []; let row = [], cell = '', q = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (q) { if (c === '"' && t[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') q = false; else cell += c; }
    else if (c === '"') q = true; else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') { if (c === '\r' && t[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = ''; }
    else cell += c;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.some(Boolean));
}

function generate(len = 18) {
  const sets = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!@#$%&*?-_'];
  const all = sets.join('');
  const pick = (s) => s[crypto.randomInt(0, s.length)];
  const chars = sets.map(pick);
  while (chars.length < len) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i--) { const j = crypto.randomInt(0, i + 1); [chars[i], chars[j]] = [chars[j], chars[i]]; }
  return chars.join('');
}

module.exports = { clearSince, query, fill, status, save, never, isNever, remove, update, all, reveal, exportCsv, importCsv, generate, originOf };
