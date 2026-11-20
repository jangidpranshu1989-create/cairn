// Payment cards & addresses: kept in the OS-encrypted vault (safeStorage) and synced end-to-end encrypted.
// CVC is never stored. Cards only fill on https pages and after the user confirms.
const crypto = require('node:crypto');
const store = require('./store');

const vault = () => store.get().vault;
const KINDS = ['cards', 'addresses'];
const list = (kind) => { if (!KINDS.includes(kind)) return []; return vault().data[kind] || (vault().data[kind] = []); };
const digits = (s) => String(s || '').replace(/\D/g, '');

function luhn(n) { if (!/^\d{12,19}$/.test(n)) return false; let sum = 0, alt = false; for (let i = n.length - 1; i >= 0; i--) { let d = +n[i]; if (alt) { d *= 2; if (d > 9) d -= 9; } sum += d; alt = !alt; } return sum % 10 === 0; }
function brand(n) {
  if (/^4/.test(n)) return 'Visa'; if (/^(5[1-5]|2[2-7])/.test(n)) return 'Mastercard'; if (/^3[47]/.test(n)) return 'Amex';
  if (/^(508|60|65|81|82)/.test(n)) return 'RuPay'; if (/^(6011|64[4-9])/.test(n)) return 'Discover'; if (/^35/.test(n)) return 'JCB'; return 'Card';
}
const label = (kind, j) => kind === 'cards' ? `${j.nickname || brand(j.number)} •••• ${String(j.number).slice(-4)}` : (j.name || j.line1);
const sub = (kind, j) => kind === 'cards' ? [j.name, j.expMonth ? `Expires ${j.expMonth}/${String(j.expYear).slice(-2)}` : ''].filter(Boolean).join(' · ') : [j.line1, j.city, j.zip].filter(Boolean).join(', ');

function clean(kind, j) {
  const pick = (keys) => Object.fromEntries(keys.map(k => [k, String(j[k] ?? '').slice(0, 200)]));
  if (kind === 'cards') { const c = pick(['name', 'nickname', 'expMonth', 'expYear']); c.number = digits(j.number); return c; }
  return pick(['name', 'phone', 'email', 'line1', 'line2', 'city', 'state', 'zip', 'country']);
}
function find(kind, j) {
  return kind === 'cards' ? list(kind).find(x => x.number === digits(j.number))
    : list(kind).find(x => (x.line1 || '').trim().toLowerCase() === String(j.line1 || '').trim().toLowerCase() && (x.zip || '') === (j.zip || ''));
}
function save(kind, j, { id, at } = {}) {
  if (!KINDS.includes(kind)) return null;
  const c = clean(kind, j); if (kind === 'cards' && !luhn(c.number)) throw new Error('That card number doesn’t look right');
  if (kind === 'addresses' && !c.line1) throw new Error('Address is required');
  const ex = id ? list(kind).find(x => x.id === id) : find(kind, c);
  const now = at || Date.now();
  if (ex) Object.assign(ex, c, { updated: now }); else list(kind).unshift({ id: id || crypto.randomUUID(), ...c, updated: now });
  vault().flush(); return ex?.id || list(kind)[0].id;
}
/** Applies a synced item (keeps the remote id). */
function applyRemote(kind, j) {
  if (!KINDS.includes(kind) || !j.id) return null;
  const ex = list(kind).find(x => x.id === j.id);
  const { cvc, cvv, ...rest } = j;
  if (ex) { if ((j.updated || 0) >= (ex.updated || 0)) Object.assign(ex, rest); } else list(kind).unshift({ ...rest });
  vault().flush(); return j.id;
}
function remove(kind, id) { if (!KINDS.includes(kind)) return; vault().data[kind] = list(kind).filter(x => x.id !== id); vault().flush(); }
function clearSince(since = 0) { for (const k of KINDS) vault().data[k] = list(k).filter(x => (x.updated || 0) < since); vault().flush(); }
function query(kind) { return list(kind).map(j => ({ id: j.id, label: label(kind, j), sub: sub(kind, j) })); }
function get(kind, id) { return list(kind).find(x => x.id === id) || null; }
function all(kind) { return list(kind).map(j => kind === 'cards' ? { ...j, number: undefined, last4: j.number.slice(-4), brand: brand(j.number), label: label(kind, j), sub: sub(kind, j) } : { ...j, label: label(kind, j), sub: sub(kind, j) }); }
/** True if this captured form data is new (or changed) and worth offering to save. */
function isNew(kind, j) {
  const c = clean(kind, j); const ex = find(kind, c); if (!ex) return true;
  return kind === 'cards' ? (c.expMonth && (ex.expMonth !== c.expMonth || ex.expYear !== c.expYear)) : false;
}
module.exports = { KINDS, luhn, brand, label, save, applyRemote, remove, clearSince, query, get, all, isNew, list, clean };
