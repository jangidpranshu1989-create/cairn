// Cairn synced passkeys on desktop: the same ES256 virtual authenticator as Android, so a passkey made on the phone
// signs in on this computer (and vice-versa). Keys live in the OS-encrypted vault and sync end-to-end encrypted.
// Requests the user cancels, or sites with no Cairn passkey, fall back to Chromium's native WebAuthn.
const crypto = require('node:crypto');
const store = require('./store');

const AAGUID = Buffer.from('cairn-pk-v1-2026');   // same 16 bytes as Android
const vault = () => store.get().vault;
const list = () => vault().data.passkeys || (vault().data.passkeys = []);
const b64u = (b) => Buffer.from(b).toString('base64url');
const unb64u = (s) => Buffer.from(String(s || ''), 'base64url');
const sha256 = (b) => crypto.createHash('sha256').update(b).digest();

class Fallback extends Error { constructor() { super('fallback'); this.fallback = true; } }
class WebAuthnError extends Error { constructor(name, msg) { super(msg); this.name = name; } }

// Minimal CBOR encoder (maps with int/string keys, byte/text strings, ints) — enough for attestation + COSE.
function cbor(v) {
  const out = [];
  const head = (major, n) => {
    const m = major << 5;
    if (n < 24) out.push(Buffer.from([m | n]));
    else if (n < 0x100) out.push(Buffer.from([m | 24, n]));
    else if (n < 0x10000) out.push(Buffer.from([m | 25, n >> 8, n & 255]));
    else { const b = Buffer.alloc(5); b[0] = m | 26; b.writeUInt32BE(n, 1); out.push(b); }
  };
  const w = (x) => {
    if (typeof x === 'number') x >= 0 ? head(0, x) : head(1, -1 - x);
    else if (Buffer.isBuffer(x)) { head(2, x.length); out.push(x); }
    else if (typeof x === 'string') { const b = Buffer.from(x, 'utf8'); head(3, b.length); out.push(b); }
    else if (Array.isArray(x)) { head(4, x.length); x.forEach(w); }
    else if (x instanceof Map) { head(5, x.size); for (const [k, y] of x) { w(k); w(y); } }
    else if (x && typeof x === 'object') { const e = Object.entries(x); head(5, e.length); for (const [k, y] of e) { w(k); w(y); } }
    else throw new Error('cbor: unsupported');
  };
  w(v); return Buffer.concat(out);
}

function checkRp(origin, rpId) {
  let u; try { u = new URL(origin); } catch { throw new WebAuthnError('SecurityError', 'Invalid origin'); }
  const host = u.hostname.toLowerCase();
  const secure = u.protocol === 'https:' || (u.protocol === 'http:' && (host === 'localhost' || host.endsWith('.localhost')));
  if (!secure) throw new WebAuthnError('SecurityError', 'Passkeys need a secure (https) page');
  const rp = String(rpId || '').toLowerCase();
  if (!(host === rp || host.endsWith('.' + rp)) || (!rp.includes('.') && rp !== 'localhost')) throw new WebAuthnError('SecurityError', 'The relying party ID is not valid for this page');
}
// Same serialisation as Android/Chromium: type, challenge, origin, crossOrigin
const clientData = (type, challenge, origin) => Buffer.from(JSON.stringify({ type, challenge, origin, crossOrigin: false }));
function authData(rpId, flags, attested) {
  const head = Buffer.alloc(37); sha256(Buffer.from(rpId)).copy(head, 0); head[32] = flags; head.writeUInt32BE(0, 33);
  return attested ? Buffer.concat([head, attested]) : head;
}
const UP = 0x01, UV = 0x04, BE = 0x08, BS = 0x10, AT = 0x40;

/**
 * verify(kind, rpId, label) -> Promise<{ ok: boolean, uv: boolean }>; supplied by main (Touch ID or a confirm dialog).
 */
async function create(origin, o, verify) {
  const rpId = (o.rp && o.rp.id) || new URL(origin).hostname;
  checkRp(origin, rpId);
  if (!(o.pubKeyCredParams || []).some(p => p.alg === -7)) throw new Fallback();
  const user = o.user || {};
  const excl = new Set((o.excludeCredentials || []).map(c => c.id));
  if (list().some(p => p.rpId === rpId && excl.has(p.credId))) throw new WebAuthnError('InvalidStateError', 'You already have a passkey for this account in Cairn');
  const name = user.name || user.displayName || '';
  const v = await verify('create', rpId, name);
  if (!v.ok) throw new Fallback();
  if (o.authenticatorSelection?.userVerification === 'required' && !v.uv) throw new Fallback();
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const jwk = publicKey.export({ format: 'jwk' });
  const credId = crypto.randomBytes(16);
  const cose = cbor(new Map([[1, 2], [3, -7], [-1, 1], [-2, unb64u(jwk.x)], [-3, unb64u(jwk.y)]]));
  const attested = Buffer.concat([AAGUID, Buffer.from([credId.length >> 8, credId.length & 255]), credId, cose]);
  const ad = authData(rpId, UP | (v.uv ? UV : 0) | BE | BS | AT, attested);
  const att = cbor(new Map([['fmt', 'none'], ['attStmt', new Map()], ['authData', ad]]));
  const cd = clientData('webauthn.create', o.challenge, origin);
  const now = Date.now();
  list().push({ credId: b64u(credId), rpId, userHandle: user.id || '', userName: name, displayName: user.displayName || '', priv: b64u(privateKey.export({ format: 'der', type: 'pkcs8' })), created: now, updated: now });
  vault().flush();
  return { id: b64u(credId), clientDataJSON: b64u(cd), attestationObject: b64u(att), authenticatorData: b64u(ad),
    publicKey: b64u(publicKey.export({ format: 'der', type: 'spki' })), alg: -7, transports: ['internal', 'hybrid'] };
}

async function get(origin, o, verify, choose) {
  const rpId = o.rpId || new URL(origin).hostname;
  checkRp(origin, rpId);
  const allow = new Set((o.allowCredentials || []).map(c => c.id));
  const cands = list().filter(p => p.rpId === rpId && (!allow.size || allow.has(p.credId)));
  if (!cands.length) throw new Fallback();
  const pick = cands.length === 1 ? cands[0] : await choose(rpId, cands);
  if (!pick) throw new Fallback();
  const v = await verify('get', rpId, pick.userName || pick.displayName);
  if (!v.ok) throw new Fallback();
  if (o.userVerification === 'required' && !v.uv) throw new Fallback();
  const ad = authData(rpId, UP | (v.uv ? UV : 0) | BE | BS);
  const cd = clientData('webauthn.get', o.challenge, origin);
  const key = crypto.createPrivateKey({ key: unb64u(pick.priv), format: 'der', type: 'pkcs8' });
  const sig = crypto.sign('sha256', Buffer.concat([ad, sha256(cd)]), key);   // DER-encoded ECDSA, as WebAuthn expects
  pick.lastUsed = Date.now(); vault().flush();
  return { id: pick.credId, clientDataJSON: b64u(cd), authenticatorData: b64u(ad), signature: b64u(sig), userHandle: pick.userHandle || null };
}

function all() { return list().map(p => ({ credId: p.credId, rpId: p.rpId, userName: p.userName, displayName: p.displayName, created: p.created, lastUsed: p.lastUsed || 0 })); }
function remove(credId) { vault().data.passkeys = list().filter(p => p.credId !== credId); vault().flush(); }

module.exports = { create, get, all, remove, checkRp, cbor, Fallback, WebAuthnError, _test: { authData, clientData } };
