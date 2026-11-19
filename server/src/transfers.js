import express from 'express';
import multer from 'multer';
import fs from 'node:fs';
import path from 'node:path';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { requireAuth, id } from './auth.js';
import { notify } from './hub.js';

const dir = path.join(config.dataDir, 'transfers');
const upload = multer({
  storage: multer.diskStorage({ destination: dir, filename: (_r, _f, cb) => cb(null, id('f_')) }),
  limits: { fileSize: config.maxTransferBytes },
});
const safeFolder = (s) => String(s || 'Cairn').replace(/[^\p{L}\p{N} _\-/]/gu, '').replace(/\.\.+/g, '').replace(/^\/+/, '').slice(0, 120) || 'Cairn';
const safeName = (s) => String(s || 'file').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 180);

export const transfersRouter = express.Router();

// Desktop -> phone: upload a file with the folder it should be saved to on the phone
transfersRouter.post('/', requireAuth, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file missing' });
  const t = {
    id: path.basename(req.file.path), filename: safeName(req.body.filename || req.file.originalname),
    mime: req.file.mimetype || 'application/octet-stream', size: req.file.size, folder: safeFolder(req.body.folder),
  };
  db.prepare('INSERT INTO transfers (id, user_id, filename, mime, size, target_folder, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?)')
    .run(t.id, req.user.id, t.filename, t.mime, t.size, t.folder, now(), now() + config.transferTtlHours * 3600_000);
  const live = notify(req.user.id, { type: 'transfer_ready', transfer: t }, 'android');
  audit(req.user.id, 'transfer_created', { id: t.id, size: t.size });
  res.json({ transfer: t, deliveredLive: live });
});

transfersRouter.get('/pending', requireAuth, (req, res) => {
  res.json(db.prepare("SELECT id, filename, mime, size, target_folder AS folder, created_at FROM transfers WHERE user_id=? AND status='pending' AND expires_at>?").all(req.user.id, now()));
});

transfersRouter.get('/:id/download', requireAuth, (req, res) => {
  const t = db.prepare('SELECT * FROM transfers WHERE id=? AND user_id=?').get(req.params.id, req.user.id);
  if (!t) return res.status(404).json({ error: 'not found' });
  res.setHeader('Content-Type', t.mime);
  res.setHeader('Content-Disposition', `attachment; filename*=UTF-8''${encodeURIComponent(t.filename)}`);
  fs.createReadStream(path.join(dir, t.id)).on('error', () => res.status(410).end()).pipe(res);
});

transfersRouter.post('/:id/ack', requireAuth, (req, res) => {
  const t = db.prepare('SELECT * FROM transfers WHERE id=? AND user_id=?').get(req.params.id, req.user.id);
  if (!t) return res.status(404).json({ error: 'not found' });
  db.prepare("UPDATE transfers SET status='delivered' WHERE id=?").run(t.id);
  fs.rm(path.join(dir, t.id), { force: true }, () => {});
  notify(req.user.id, { type: 'transfer_delivered', id: t.id, filename: t.filename, savedTo: req.body?.savedTo || t.target_folder }, 'desktop');
  res.json({ ok: true });
});

// Remove expired files hourly
setInterval(() => {
  for (const t of db.prepare('SELECT id FROM transfers WHERE expires_at<?').all(now())) fs.rm(path.join(dir, t.id), { force: true }, () => {});
  db.prepare('DELETE FROM transfers WHERE expires_at<?').run(now());
}, 3600_000).unref();
