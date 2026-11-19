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

// Desktop asks for a short-lived pairing code to show as QR + digits
devicesRouter.post('/pair/start', requireAuth, async (req, res) => {
  let code;
  do { code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0'); }
  while (db.prepare('SELECT 1 FROM pair_codes WHERE code=?').get(code));
  db.prepare('INSERT INTO pair_codes (code, user_id, expires_at) VALUES (?,?,?)').run(code, req.user.id, now() + 5 * 60_000);
  const payload = `cairn://pair?code=${code}&server=${encodeURIComponent(config.publicUrl)}`;
  res.json({ code, payload, qr: await QRCode.toDataURL(payload, { margin: 1, width: 280 }), expiresIn: 300 });
});

const pairLimiter = rateLimit({ windowMs: 10 * 60_000, limit: 20 });
// Phone redeems the code (no auth yet) and receives its own device session
devicesRouter.post('/pair/complete', pairLimiter, (req, res) => {
  const p = z.object({ code: z.string().regex(/^\d{6}$/), name: z.string().max(80).default('Android phone'), pushToken: z.string().max(4096).optional() }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: 'Enter the 6-digit code shown in Cairn' });
  const row = db.prepare('SELECT * FROM pair_codes WHERE code=?').get(p.data.code);
  if (!row || row.expires_at < now()) return res.status(400).json({ error: 'Code expired. Generate a new one on your computer.' });
  db.prepare('DELETE FROM pair_codes WHERE code=?').run(p.data.code);
  const did = id('d_');
  db.prepare('INSERT INTO devices (id, user_id, kind, name, push_token, last_seen_at, created_at) VALUES (?,?,?,?,?,?,?)')
    .run(did, row.user_id, 'android', p.data.name, p.data.pushToken || null, now(), now());
  audit(row.user_id, 'device_paired', { did });
  notify(row.user_id, { type: 'device_paired', device: { id: did, name: p.data.name } }, 'desktop');
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(row.user_id);
  res.json({ deviceId: did, user: publicUser(user), ...issueTokens(row.user_id, { deviceId: did, userAgent: req.get('user-agent'), ip: req.ip }) });
});

devicesRouter.patch('/me', requireAuth, (req, res) => {
  if (!req.user.deviceId) return res.status(400).json({ error: 'not a device session' });
  const { pushToken, name } = req.body || {};
  if (pushToken) db.prepare('UPDATE devices SET push_token=? WHERE id=?').run(String(pushToken).slice(0, 4096), req.user.deviceId);
  if (name) db.prepare('UPDATE devices SET name=? WHERE id=?').run(String(name).slice(0, 80), req.user.deviceId);
  res.json({ ok: true });
});

devicesRouter.delete('/:id', requireAuth, (req, res) => {
  db.prepare('DELETE FROM devices WHERE id=? AND user_id=?').run(req.params.id, req.user.id);
  db.prepare('UPDATE sessions SET revoked=1 WHERE device_id=? AND user_id=?').run(req.params.id, req.user.id);
  notify(req.user.id, { type: 'device_removed', id: req.params.id });
  res.json({ ok: true });
});
