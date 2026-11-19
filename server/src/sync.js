import express from 'express';
import { z } from 'zod';
import { db, now, audit } from './db.js';
import { requireAuth } from './auth.js';
import { notify } from './hub.js';

/**
 * Zero-knowledge, item-level sync (Chrome-sync style) for bookmarks, history, open tabs, settings, profile,
 * passwords and passkeys.
 *
 * - Every item is AES-256-GCM encrypted on the device with a random 256-bit vault key. The vault key itself is
 *   stored here only in wrapped form (encrypted with a key derived from the user's password on the device),
 *   so the server can never read any synced data.
 * - Each change bumps a per-user revision counter. Clients pull `?since=<rev>` and push batches; conflicts are
 *   resolved last-writer-wins on the client-supplied `updatedAt`, which is what Chrome does for most types.
 * - After a push, other online devices get a `sync_changed` WebSocket message and pull immediately.
 */
export const syncRouter = express.Router();
const KINDS = ['bookmarks', 'history', 'tabs', 'settings', 'profile', 'passwords', 'passkeys', 'trackers', 'readlater'];
const MAX_ITEMS = { history: 5000, tabs: 400 };

const meta = (uid) => db.prepare('SELECT * FROM sync_meta WHERE user_id=?').get(uid)
  || (db.prepare('INSERT INTO sync_meta (user_id, rev) VALUES (?,0)').run(uid), db.prepare('SELECT * FROM sync_meta WHERE user_id=?').get(uid));

// Wrapped vault key (ciphertext). First device to sign in creates it; others download and unwrap locally.
syncRouter.get('/key', requireAuth, (req, res) => {
  const m = meta(req.user.id);
  res.json({ wrappedKey: m.wrapped_key || null, salt: m.key_salt || null, kdf: m.kdf || null, rev: m.rev });
});
syncRouter.put('/key', requireAuth, (req, res) => {
  const p = z.object({ wrappedKey: z.string().min(20).max(2000), salt: z.string().max(200), kdf: z.string().max(200), replace: z.boolean().default(false) }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: 'invalid key' });
  const m = meta(req.user.id);
  if (m.wrapped_key && !p.data.replace) return res.status(409).json({ error: 'key exists', wrappedKey: m.wrapped_key, salt: m.key_salt, kdf: m.kdf });
  db.prepare('UPDATE sync_meta SET wrapped_key=?, key_salt=?, kdf=? WHERE user_id=?').run(p.data.wrappedKey, p.data.salt, p.data.kdf, req.user.id);
  // Replacing the key (password reset without the old password) makes old ciphertext unreadable; wipe it.
  if (m.wrapped_key && p.data.replace) db.prepare('DELETE FROM sync_items WHERE user_id=?').run(req.user.id);
  audit(req.user.id, p.data.replace ? 'sync_key_replaced' : 'sync_key_created');
  res.json({ ok: true });
});

syncRouter.get('/changes', requireAuth, (req, res) => {
  const since = Math.max(0, Number(req.query.since) || 0);
  const limit = Math.min(2000, Math.max(1, Number(req.query.limit) || 1000));
  const rows = db.prepare('SELECT kind, item_id, rev, updated_at, deleted, ciphertext FROM sync_items WHERE user_id=? AND rev>? ORDER BY rev LIMIT ?').all(req.user.id, since, limit);
  const m = meta(req.user.id);
  res.json({ rev: m.rev, more: rows.length === limit, items: rows.map(r => ({ kind: r.kind, id: r.item_id, rev: r.rev, updatedAt: r.updated_at, deleted: !!r.deleted, data: r.deleted ? null : r.ciphertext })) });
});

const itemS = z.object({ kind: z.enum(KINDS), id: z.string().min(8).max(128), updatedAt: z.number().int().nonnegative(), deleted: z.boolean().default(false), data: z.string().max(200_000).nullable().optional() });
syncRouter.post('/changes', requireAuth, express.json({ limit: '20mb' }), (req, res) => {
  const p = z.object({ items: z.array(itemS).max(2000) }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: p.error.issues[0].message });
  const uid = req.user.id; let applied = 0, rejected = 0;
  const tx = db.transaction(() => {
    let rev = meta(uid).rev;
    const get = db.prepare('SELECT updated_at FROM sync_items WHERE user_id=? AND kind=? AND item_id=?');
    const put = db.prepare(`INSERT INTO sync_items (user_id, kind, item_id, rev, updated_at, deleted, ciphertext, device_id) VALUES (?,?,?,?,?,?,?,?)
      ON CONFLICT(user_id, kind, item_id) DO UPDATE SET rev=excluded.rev, updated_at=excluded.updated_at, deleted=excluded.deleted, ciphertext=excluded.ciphertext, device_id=excluded.device_id`);
    const t = now() + 5 * 60_000; // tolerate small clock skew, refuse far-future timestamps that would win forever
    for (const it of p.data.items) {
      const cur = get.get(uid, it.kind, it.id);
      if ((cur && cur.updated_at > it.updatedAt) || it.updatedAt > t || (!it.deleted && !it.data)) { rejected++; continue; }
      put.run(uid, it.kind, it.id, ++rev, it.updatedAt, it.deleted ? 1 : 0, it.deleted ? null : it.data, req.user.deviceId);
      applied++;
    }
    db.prepare('UPDATE sync_meta SET rev=? WHERE user_id=?').run(rev, uid);
    // Cap unbounded kinds (oldest first); tombstones older than 60 days are dropped.
    for (const [k, n] of Object.entries(MAX_ITEMS))
      db.prepare(`DELETE FROM sync_items WHERE user_id=? AND kind=? AND item_id IN (SELECT item_id FROM sync_items WHERE user_id=? AND kind=? ORDER BY updated_at DESC LIMIT -1 OFFSET ?)`).run(uid, k, uid, k, n);
    db.prepare('DELETE FROM sync_items WHERE user_id=? AND deleted=1 AND updated_at<?').run(uid, now() - 60 * 86_400_000);
    return rev;
  });
  const rev = tx();
  if (applied) notify(uid, { type: 'sync_changed', rev, from: req.user.deviceId || null });
  res.json({ ok: true, rev, applied, rejected });
});

syncRouter.delete('/', requireAuth, (req, res) => {
  db.prepare('DELETE FROM sync_items WHERE user_id=?').run(req.user.id);
  db.prepare('DELETE FROM sync_meta WHERE user_id=?').run(req.user.id);
  audit(req.user.id, 'sync_reset');
  notify(req.user.id, { type: 'sync_changed', rev: 0, reset: true });
  res.json({ ok: true });
});
