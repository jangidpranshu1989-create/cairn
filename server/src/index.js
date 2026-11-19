import express from 'express';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import helmet from 'helmet';
import cors from 'cors';
import rateLimit from 'express-rate-limit';
import { config } from './config.js';
import { db, now } from './db.js';
import { authRouter, requireAuth } from './auth.js';
import { passkeyRouter } from './passkey.js';
import { devicesRouter } from './devices.js';
import { approvalsRouter } from './approvals.js';
import { transfersRouter } from './transfers.js';
import { aiRouter } from './ai.js';
import { attachHub } from './hub.js';
import { syncRouter } from './sync.js';
import { readFileSync } from 'node:fs';

const VERSION = (() => { try { return JSON.parse(readFileSync(new URL('../package.json', import.meta.url))).version; } catch { return '0.0.0'; } })();
const app = express();
app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: {
  directives: { defaultSrc: ["'self'"], imgSrc: ["'self'", 'data:'], styleSrc: ["'self'", "'unsafe-inline'", 'https://api.fontshare.com'],
    fontSrc: ["'self'", 'https://cdn.fontshare.com'], scriptSrc: ["'self'"], connectSrc: ["'self'"] } } }));
// Bearer-token API (no cookies), so CORS only needs to allow the public site and Cairn's own internal pages.
app.use(cors({ credentials: false, origin: (o, cb) => cb(null, !o || config.corsOrigins.includes('*') || config.corsOrigins.includes(o) || /^cairn:\/\//.test(o)) }));
app.use(express.json({ limit: '4mb' }));
app.use('/api', rateLimit({ windowMs: 60_000, limit: 600, standardHeaders: true, legacyHeaders: false }));

app.get('/api/health', (_req, res) => res.json({ ok: true, time: now(), version: VERSION }));
app.use('/api/auth', authRouter);
app.use('/api/passkeys', passkeyRouter);
app.use('/api/devices', devicesRouter);
app.use('/api/approvals', approvalsRouter);
app.use('/api/transfers', transfersRouter);
app.use('/api/ai', aiRouter);
app.use('/api/sync/v2', syncRouter);

// Public website: contact form + Pro waitlist (rate-limited, validated, stored for the team)
const contactLimiter = rateLimit({ windowMs: 60 * 60_000, limit: 10, standardHeaders: true, legacyHeaders: false });
app.post('/api/public/contact', contactLimiter, (req, res) => {
  const b = req.body || {};
  const kind = b.kind === 'waitlist' ? 'waitlist' : 'contact';
  const email = String(b.email || '').trim().slice(0, 200);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: 'Please enter a valid email address' });
  if (b.website) return res.json({ ok: true }); // honeypot
  const body = String(b.message || '').slice(0, 5000);
  if (kind === 'contact' && body.trim().length < 5) return res.status(400).json({ error: 'Please write a short message' });
  db.prepare('INSERT INTO messages (kind, name, email, topic, body, ip, created_at) VALUES (?,?,?,?,?,?,?)')
    .run(kind, String(b.name || '').slice(0, 120), email, String(b.topic || '').slice(0, 60), body, req.ip, now());
  res.json({ ok: true });
});

// Encrypted sync blobs (client encrypts before upload; server never sees plaintext passwords)
const SYNC_KINDS = new Set(['bookmarks', 'settings', 'passwords', 'history']);
app.get('/api/sync/:kind', requireAuth, (req, res) => {
  if (!SYNC_KINDS.has(req.params.kind)) return res.status(400).json({ error: 'bad kind' });
  const row = db.prepare('SELECT data, updated_at FROM sync_blobs WHERE user_id=? AND kind=?').get(req.user.id, req.params.kind);
  res.json(row || { data: null, updated_at: 0 });
});
app.put('/api/sync/:kind', requireAuth, express.json({ limit: '10mb' }), (req, res) => {
  if (!SYNC_KINDS.has(req.params.kind)) return res.status(400).json({ error: 'bad kind' });
  const data = String(req.body?.data || '');
  db.prepare('INSERT INTO sync_blobs (user_id, kind, data, updated_at) VALUES (?,?,?,?) ON CONFLICT(user_id, kind) DO UPDATE SET data=excluded.data, updated_at=excluded.updated_at')
    .run(req.user.id, req.params.kind, data, now());
  res.json({ ok: true, updated_at: now() });
});

// Hosted account pages (sign-in, passkeys, reset) live in /public
const __dirname = path.dirname(fileURLToPath(import.meta.url));
app.use(express.static(path.join(__dirname, '..', 'public'), { extensions: ['html'] }));

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(err.status || 500).json({ error: err.code === 'LIMIT_FILE_SIZE' ? 'File too large' : 'Internal error' });
});

const server = http.createServer(app);
attachHub(server);
server.listen(config.port, () => console.log(`Cairn server on :${config.port} (groq=${!!config.groqKey}, nvidia=${!!config.nvidiaKey})`));
