import express from 'express';
import { z } from 'zod';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { requireAuth, id } from './auth.js';
import { notify } from './hub.js';

export const approvalsRouter = express.Router();
const waiters = new Map(); // approvalId -> Set<res>

const shape = (a) => a && ({ id: a.id, kind: a.kind, title: a.title, details: JSON.parse(a.details), status: a.status,
  createdAt: a.created_at, expiresAt: a.expires_at, decidedAt: a.decided_at });

// Desktop agent creates an approval request (e.g. "Pay ₹54,999 on Flipkart")
approvalsRouter.post('/', requireAuth, (req, res) => {
  const p = z.object({
    kind: z.enum(['payment', 'purchase', 'login', 'sensitive']),
    title: z.string().min(1).max(160),
    details: z.object({
      merchant: z.string().max(120).optional(), amount: z.string().max(40).optional(), item: z.string().max(300).optional(),
      url: z.string().max(2000).optional(), note: z.string().max(600).optional(),
    }).default({}),
  }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: p.error.issues[0].message });
  const aid = id('a_');
  db.prepare('INSERT INTO approvals (id, user_id, kind, title, details, created_at, expires_at) VALUES (?,?,?,?,?,?,?)')
    .run(aid, req.user.id, p.data.kind, p.data.title, JSON.stringify(p.data.details), now(), now() + config.approvalTtlSec * 1000);
  const a = shape(db.prepare('SELECT * FROM approvals WHERE id=?').get(aid));
  const phones = db.prepare("SELECT COUNT(*) n FROM devices WHERE user_id=? AND kind='android'").get(req.user.id).n;
  const delivered = notify(req.user.id, { type: 'approval_request', approval: a }, 'android');
  audit(req.user.id, 'approval_requested', { aid, kind: a.kind });
  res.json({ approval: a, pairedPhones: phones, deliveredLive: delivered });
});

approvalsRouter.get('/pending', requireAuth, (req, res) => {
  const rows = db.prepare("SELECT * FROM approvals WHERE user_id=? AND status='pending' AND expires_at>? ORDER BY created_at DESC").all(req.user.id, now());
  res.json(rows.map(shape));
});

approvalsRouter.get('/:id', requireAuth, (req, res) => {
  const a = db.prepare('SELECT * FROM approvals WHERE id=? AND user_id=?').get(req.params.id, req.user.id);
  if (!a) return res.status(404).json({ error: 'not found' });
  res.json(shape(a));
});

// Long-poll until decided (max ~55s per call; client re-polls)
approvalsRouter.get('/:id/wait', requireAuth, (req, res) => {
  const a = db.prepare('SELECT * FROM approvals WHERE id=? AND user_id=?').get(req.params.id, req.user.id);
  if (!a) return res.status(404).json({ error: 'not found' });
  if (a.status !== 'pending' || a.expires_at < now()) return res.json(shape({ ...a, status: a.status === 'pending' ? 'expired' : a.status }));
  if (!waiters.has(a.id)) waiters.set(a.id, new Set());
  waiters.get(a.id).add(res);
  const t = setTimeout(() => { waiters.get(a.id)?.delete(res); res.json(shape(db.prepare('SELECT * FROM approvals WHERE id=?').get(a.id))); }, 55_000);
  res.on('close', () => { clearTimeout(t); waiters.get(a.id)?.delete(res); });
});

// Phone (or desktop fallback dialog) decides
approvalsRouter.post('/:id/decision', requireAuth, (req, res) => {
  const p = z.object({ approve: z.boolean(), verified: z.boolean().default(false) }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: 'invalid' });
  const a = db.prepare('SELECT * FROM approvals WHERE id=? AND user_id=?').get(req.params.id, req.user.id);
  if (!a) return res.status(404).json({ error: 'not found' });
  if (a.status !== 'pending' || a.expires_at < now()) return res.status(409).json({ error: 'This request is no longer pending' });
  // Payment approvals must come from a paired phone after biometric/device-credential verification
  const phone = req.user.deviceId && db.prepare("SELECT 1 FROM devices WHERE id=? AND user_id=? AND kind='android'").get(req.user.deviceId, req.user.id);
  if (p.data.approve && (a.kind === 'payment' || a.kind === 'purchase') && (!phone || !p.data.verified))
    return res.status(403).json({ error: 'Payment approval must be confirmed on your paired phone with fingerprint/face/PIN' });
  const status = p.data.approve ? 'approved' : 'denied';
  db.prepare('UPDATE approvals SET status=?, decided_by=?, decided_at=? WHERE id=?').run(status, req.user.deviceId || 'desktop', now(), a.id);
  const out = shape(db.prepare('SELECT * FROM approvals WHERE id=?').get(a.id));
  for (const r of waiters.get(a.id) || []) r.json(out);
  waiters.delete(a.id);
  notify(req.user.id, { type: 'approval_decided', approval: out });
  audit(req.user.id, 'approval_' + status, { aid: a.id });
  res.json(out);
});
