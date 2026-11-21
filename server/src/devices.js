import express from 'express';
import crypto from 'node:crypto';
import QRCode from 'qrcode';
import { z } from 'zod';
import rateLimit from 'express-rate-limit';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { requireAuth, issueTokens, id, publicUser } from './auth.js';
import { notify, onlineDevices } from './hub.js';

export const devicesRouter = express.Router();

devicesRouter.get('/', requireAuth, (req, res) => {
  const online = new Set(onlineDevices(req.user.id));
  const rows = db.prepare('SELECT id, kind, name, last_seen_at, created_at FROM devices WHERE user_id=? ORDER BY created_at DESC').all(req.user.id);
  res.json(rows.map(r => ({ ...r, online: online.has(r.id) })));
});

// Desktop asks for a short-lived pairing code to show as QR + digits.
// The QR also carries a 256-bit secret, so scanning pairs instantly and can't be guessed; the 6 digits are only a
// fallback for typing by hand and are protected by a server-wide wrong-attempt budget (see manualAllowed).
devicesRouter.post('/pair/start', requireAuth, async (req, res) => {
  let code;
  do { code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0'); }
  while (db.prepare('SELECT 1 FROM pair_codes WHERE code=?').get(code));
  const secret = crypto.randomBytes(32).toString('base64url');
  db.prepare('INSERT INTO pair_codes (code, user_id, expires_at, secret, created_at) VALUES (?,?,?,?,?)').run(code, req.user.id, now() + 5 * 60_000, sha(secret), now());
  const payload = `cairn://pair?code=${code}&s=${secret}&server=${encodeURIComponent(config.publicUrl)}`;
  res.json({ code, payload, qr: await QRCode.toDataURL(payload, { margin: 1, width: 280 }), expiresIn: 300 });
});

const sha = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

// Wrong 6-digit guesses across the whole server. A code typed by hand only works while fewer than MANUAL_BUDGET wrong
// guesses have happened since it was created, so even a botnet with endless IPs gets ~MANUAL_BUDGET tries per code
// (a 1 in ~30,000 chance) instead of a million. Scanning the QR (secret) is never affected.
const MANUAL_BUDGET = 30;
const failures = [];
function recordFailure() { failures.push(now()); while (failures.length && failures[0] < now() - 15 * 60_000) failures.shift(); if (failures.length > 5000) failures.splice(0, failures.length - 5000); }
function manualAllowed(row) { const since = row.created_at || (row.expires_at - 5 * 60_000); return failures.filter(t => t >= since).length < MANUAL_BUDGET; }
export const _pairTest = { reset: () => { failures.length = 0; } };

/** Validates an uncompressed P-256 public key (base64 SPKI DER) from the phone's Keystore. Returns it or null. */
export function parseApprovalKey(b64) {
  if (typeof b64 !== 'string' || b64.length > 400) return null;
  try {
    const k = crypto.createPublicKey({ key: Buffer.from(b64, 'base64'), format: 'der', type: 'spki' });
    if (k.asymmetricKeyType !== 'ec' || k.asymmetricKeyDetails?.namedCurve !== 'prime256v1') return null;
    return b64;
  } catch { return null; }
}

const pairLimiter = rateLimit({ windowMs: 10 * 60_000, limit: 20 });
// Phone redeems the code (no auth yet) and receives its own device session
devicesRouter.post('/pair/complete', pairLimiter, (req, res) => {
  const p = z.object({ code: z.string().regex(/^\d{6}$/), secret: z.string().max(100).optional(), name: z.string().max(80).default('Android phone'),
    pushToken: z.string().max(4096).optional(), approvalKey: z.string().max(400).optional() }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: 'Enter the 6-digit code shown in Cairn' });
  const row = db.prepare('SELECT * FROM pair_codes WHERE code=?').get(p.data.code);
  const bad = (msg) => { recordFailure(); return res.status(400).json({ error: msg }); };
  if (!row || row.expires_at < now()) return bad('Code expired. Generate a new one on your computer.');
  if (p.data.secret) {
    if (!row.secret || !crypto.timingSafeEqual(Buffer.from(sha(p.data.secret)), Buffer.from(row.secret))) return bad('This QR code is not valid. Generate a new one on your computer.');
  } else if (!manualAllowed(row)) {
    return res.status(429).json({ error: 'Too many wrong codes were tried. Scan the QR code on your computer instead.' });
  }
  const approvalKey = p.data.approvalKey ? parseApprovalKey(p.data.approvalKey) : null;
  if (p.data.approvalKey && !approvalKey) return res.status(400).json({ error: 'Invalid device key' });
  db.prepare('DELETE FROM pair_codes WHERE code=?').run(p.data.code);
  const did = id('d_');
  db.prepare('INSERT INTO devices (id, user_id, kind, name, push_token, last_seen_at, created_at, approval_key) VALUES (?,?,?,?,?,?,?,?)')
    .run(did, row.user_id, 'android', p.data.name, p.data.pushToken || null, now(), now(), approvalKey);
  audit(row.user_id, 'device_paired', { did, via: p.data.secret ? 'qr' : 'code' });
  notify(row.user_id, { type: 'device_paired', device: { id: did, name: p.data.name } }, 'desktop');
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(row.user_id);
  res.json({ deviceId: did, user: publicUser(user), ...issueTokens(row.user_id, { deviceId: did, userAgent: req.get('user-agent'), ip: req.ip }) });
});

devicesRouter.patch('/me', requireAuth, (req, res) => {
  if (!req.user.deviceId) return res.status(400).json({ error: 'not a device session' });
  const { pushToken, name } = req.body || {};
  if (pushToken) db.prepare('UPDATE devices SET push_token=? WHERE id=?').run(String(pushToken).slice(0, 4096), req.user.deviceId);
  if (name) db.prepare('UPDATE devices SET name=? WHERE id=?').run(String(name).slice(0, 80), req.user.deviceId);
  // Phones paired before 3.4 register their approval key once. It can never be replaced afterwards (a stolen token
  // must not be able to swap in its own key); re-pairing the phone creates a new device with a new key.
  if (req.body?.approvalKey !== undefined) {
    const k = parseApprovalKey(req.body.approvalKey);
    if (!k) return res.status(400).json({ error: 'Invalid device key' });
    const cur = db.prepare('SELECT approval_key FROM devices WHERE id=? AND user_id=?').get(req.user.deviceId, req.user.id);
    if (!cur) return res.status(404).json({ error: 'device not found' });
    if (cur.approval_key && cur.approval_key !== k) return res.status(409).json({ error: 'This phone already has a key. Pair it again to replace it.' });
    if (!cur.approval_key) { db.prepare('UPDATE devices SET approval_key=? WHERE id=?').run(k, req.user.deviceId); audit(req.user.id, 'device_key_set', { did: req.user.deviceId }); }
  }
  res.json({ ok: true });
});

devicesRouter.delete('/:id', requireAuth, (req, res) => {
  db.prepare('DELETE FROM devices WHERE id=? AND user_id=?').run(req.params.id, req.user.id);
  db.prepare('UPDATE sessions SET revoked=1 WHERE device_id=? AND user_id=?').run(req.params.id, req.user.id);
  notify(req.user.id, { type: 'device_removed', id: req.params.id });
  res.json({ ok: true });
});
