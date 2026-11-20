import Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

fs.mkdirSync(config.dataDir, { recursive: true });
fs.mkdirSync(path.join(config.dataDir, 'transfers'), { recursive: true });

export const db = new Database(path.join(config.dataDir, 'cairn.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT UNIQUE NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  password_hash TEXT,
  email_verified INTEGER NOT NULL DEFAULT 0,
  plan TEXT NOT NULL DEFAULT 'free',
  failed_logins INTEGER NOT NULL DEFAULT 0,
  locked_until INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash TEXT NOT NULL,
  device_id TEXT,
  user_agent TEXT,
  ip TEXT,
  created_at INTEGER NOT NULL,
  last_used_at INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS passkeys (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  public_key BLOB NOT NULL,
  counter INTEGER NOT NULL DEFAULT 0,
  transports TEXT,
  name TEXT,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS challenges (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,             -- desktop | android
  name TEXT NOT NULL,
  push_token TEXT,
  last_seen_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_codes (
  code TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS approvals (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,             -- payment | purchase | login | sensitive
  title TEXT NOT NULL,
  details TEXT NOT NULL,          -- JSON
  status TEXT NOT NULL DEFAULT 'pending', -- pending | approved | denied | expired
  decided_by TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  decided_at INTEGER
);
CREATE TABLE IF NOT EXISTS transfers (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  filename TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  target_folder TEXT NOT NULL DEFAULT 'Cairn',
  status TEXT NOT NULL DEFAULT 'pending', -- pending | delivered
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sync_blobs (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,             -- bookmarks | settings | passwords (client-side encrypted)
  data TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, kind)
);
CREATE TABLE IF NOT EXISTS usage (
  user_id TEXT NOT NULL,
  day TEXT NOT NULL,
  ai_requests INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, day)
);
CREATE TABLE IF NOT EXISTS reset_tokens (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT, email TEXT NOT NULL, topic TEXT, body TEXT, ip TEXT, created_at INTEGER NOT NULL
);
-- Item-level end-to-end encrypted sync. The server only ever stores ciphertext; item_id is an HMAC chosen by the client.
CREATE TABLE IF NOT EXISTS sync_items (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  item_id TEXT NOT NULL,
  rev INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  ciphertext TEXT,
  device_id TEXT,
  PRIMARY KEY (user_id, kind, item_id)
);
CREATE INDEX IF NOT EXISTS sync_items_rev ON sync_items(user_id, rev);
CREATE TABLE IF NOT EXISTS sync_meta (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  rev INTEGER NOT NULL DEFAULT 0,
  wrapped_key TEXT,               -- vault key wrapped client-side with a key derived from the master password
  key_salt TEXT,
  kdf TEXT
);
-- Built-in AI for people without an account: a per-install anonymous token with its own small daily quota.
CREATE TABLE IF NOT EXISTS anon_devices (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  platform TEXT,
  created_at INTEGER NOT NULL,
  ip TEXT
);
CREATE TABLE IF NOT EXISTS audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT,
  action TEXT NOT NULL,
  meta TEXT,
  at INTEGER NOT NULL
);
`);

// Lightweight migrations for databases created by older versions
const cols = (t) => db.prepare(`PRAGMA table_info(${t})`).all().map(c => c.name);
if (!cols('sessions').includes('prev_hash')) db.exec('ALTER TABLE sessions ADD COLUMN prev_hash TEXT');
if (!cols('sessions').includes('auth_at')) db.exec('ALTER TABLE sessions ADD COLUMN auth_at INTEGER');
if (!cols('sync_meta').includes('kcv')) db.exec('ALTER TABLE sync_meta ADD COLUMN kcv TEXT');

export const now = () => Date.now();
export function audit(userId, action, meta = {}) {
  db.prepare('INSERT INTO audit (user_id, action, meta, at) VALUES (?,?,?,?)').run(userId, action, JSON.stringify(meta), now());
}

// Periodic cleanup of expired rows
setInterval(() => {
  const t = now();
  db.prepare('DELETE FROM challenges WHERE expires_at < ?').run(t);
  db.prepare('DELETE FROM pair_codes WHERE expires_at < ?').run(t);
  db.prepare("UPDATE approvals SET status='expired' WHERE status='pending' AND expires_at < ?").run(t);
  db.prepare('DELETE FROM reset_tokens WHERE expires_at < ?').run(t);
}, 30_000).unref();
// Retention policy (see privacy policy): approvals 30 days, contact messages & security log 12 months
const DAY = 86_400_000;
function retention() {
  const t = now();
  db.prepare('DELETE FROM approvals WHERE created_at < ?').run(t - 30 * DAY);
  db.prepare('DELETE FROM messages WHERE created_at < ?').run(t - 365 * DAY);
  db.prepare('DELETE FROM audit WHERE at < ?').run(t - 365 * DAY);
  db.prepare('DELETE FROM usage WHERE day < ?').run(new Date(t - 90 * DAY).toISOString().slice(0, 10));
}
retention(); setInterval(retention, 6 * 3600_000).unref();
