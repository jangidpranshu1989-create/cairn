import express from 'express';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import helmet from 'helmet';
import { strictCors, securityHeaders } from './security.js';
import rateLimit from 'express-rate-limit';
import { config } from './config.js';
import { db, now } from './db.js';
import { supabaseEnabled } from './supabase.js';
import { authRouter, requireAuth } from './auth.js';
import { passkeyRouter } from './passkey.js';
import { devicesRouter } from './devices.js';
import { approvalsRouter } from './approvals.js';
import { transfersRouter } from './transfers.js';
import { aiRouter } from './ai.js';
import { attachHub } from './hub.js';
import { syncRouter } from './sync.js';
import { oidcRouter, oauthApi } from './oidc.js';
import { safetyRouter, startSafety } from './safety.js';
import { statsRouter } from './stats.js';
import { readFileSync } from 'node:fs';

const VERSION = (() => { try { return JSON.parse(readFileSync(new URL('../package.json', import.meta.url))).version; } catch { return '0.0.0'; } })();
const app = express();
app.set('trust proxy', 1);
app.disable('x-powered-by');
app.use(helmet({ contentSecurityPolicy: {
  directives: { defaultSrc: ["'self'"], imgSrc: ["'self'", 'data:', 'https:'], styleSrc: ["'self'", "'unsafe-inline'", 'https://api.fontshare.com'],
    fontSrc: ["'self'", 'https://cdn.fontshare.com'], scriptSrc: ["'self'"], connectSrc: ["'self'"], frameAncestors: ["'none'"] } },
  frameguard: { action: 'deny' }, crossOriginEmbedderPolicy: { policy: 'credentialless' }, crossOriginOpenerPolicy: { policy: 'same-origin' } }));
app.use(securityHeaders);
// Bearer-token API (no cookies): strict origin allowlist, public OIDC endpoints get '*' without credentials.
app.use(strictCors);
app.use(express.json({ limit: '4mb' }));
app.use('/api', rateLimit({ windowMs: 60_000, limit: 600, standardHeaders: true, legacyHeaders: false }));

app.get('/api/health', (_req, res) => res.set('Cache-Control', 'no-store').json({ ok: true, time: now(), uptime: Math.round(process.uptime()), version: VERSION, storage: config.tursoUrl ? 'turso' : supabaseEnabled() ? 'supabase' : 'local' }));
app.use('/api/auth', authRouter);
app.use('/api/passkeys', passkeyRouter);
app.use('/api/devices', devicesRouter);
app.use('/api/approvals', approvalsRouter);
app.use('/api/transfers', transfersRouter);
app.use('/api/ai', aiRouter);
// Aliases so any OpenAI-style client path reaches the same AI proxy instead of a 404.
app.use(['/api/v1/ai', '/api/chat', '/v1', '/api/v1'], (req, _res, next) => { if (req.method === 'POST' && /^\/(chat(\/completions)?|completions)?\/?$/.test(req.path)) req.url = '/chat'; next(); }, aiRouter);
app.use('/api/sync/v2', syncRouter);
app.use('/api/oauth', oauthApi);
app.use('/api/safety', safetyRouter);
app.use('/api/admin', statsRouter);
startSafety();
app.use('/', oidcRouter);

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
// Legacy /account.html (used by older desktop builds for ?handoff=desktop) -> new Accounts portal. Relative redirects keep proxy prefixes.
let _404;
const page404 = () => (_404 ??= readFileSync(path.join(__dirname, '..', 'web', '404.html'), 'utf8')).replaceAll('{{BASE}}', config.publicUrl);
const qs = (req) => { const i = req.originalUrl.indexOf('?'); return i < 0 ? '' : req.originalUrl.slice(i); };
app.get(['/account', '/account.html'], (req, res) => res.redirect(302, 'accounts/' + qs(req)));
app.get('/', (req, res) => res.redirect(302, 'accounts/' + qs(req)));
// The SDK is embedded by third-party sites, so it must be loadable cross-origin (helmet defaults CORP to same-origin).
app.use('/sdk', (_req, res, next) => { res.set('Cross-Origin-Resource-Policy', 'cross-origin'); next(); });
app.use(express.static(path.join(__dirname, '..', 'web'), { extensions: ['html'] }));
app.use((req, res) => {
  if (req.method === 'GET' && !req.path.startsWith('/api/') && req.accepts(['html', 'json']) === 'html')
    return res.status(404).type('html').send(page404());
  res.status(404).json({ error: 'Not found' });
});

let _err;
const pageError = (code, title, msg) => (_err ??= readFileSync(path.join(__dirname, '..', 'web', 'error.html'), 'utf8'))
  .replaceAll('{{BASE}}', config.publicUrl).replaceAll('{{CODE}}', String(code)).replaceAll('{{TITLE}}', title).replaceAll('{{MSG}}', msg);
app.use((err, req, res, _next) => {
  const status = err.status || err.statusCode || 500;
  if (status >= 500) console.error(err);
  const msg = err.code === 'LIMIT_FILE_SIZE' ? 'File too large' : err.type === 'entity.parse.failed' ? 'Invalid JSON' : err.type === 'entity.too.large' ? 'Request too large' : status >= 500 ? 'Internal error' : 'Bad request';
  if (res.headersSent) return;
  if (req.method === 'GET' && !req.path.startsWith('/api/') && req.accepts(['html', 'json']) === 'html')
    return res.status(status).type('html').send(pageError(status, status >= 500 ? 'Something went wrong on our side' : 'That request didn\u2019t work', status >= 500 ? 'Cairn hit an unexpected error. Please try again in a moment.' : 'The link or request looks incomplete. Go back and try again.'));
  res.status(status).json({ error: msg });
});

const server = http.createServer(app);
attachHub(server);
server.listen(config.port, () => console.log(`Cairn server on :${config.port} · issuer ${config.publicUrl} · AI providers: groq=${!!config.groqKey} nvidia=${!!config.nvidiaKey}`));
