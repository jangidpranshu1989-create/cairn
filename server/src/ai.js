import express from 'express';
import multer from 'multer';
import { Readable } from 'node:stream';
import crypto from 'node:crypto';
import rateLimit from 'express-rate-limit';
import { db, now } from './db.js';
import { config } from './config.js';
import { requireAuth } from './auth.js';
import { chat, transcribe } from './providers.js';

export const aiRouter = express.Router();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 25 * 1024 * 1024 } });

// Signed-in users OR anonymous built-in devices ("Bearer anon_<id>.<secret>") may use the AI proxy.
function aiAuth(req, res, next) {
  const h = req.headers.authorization || '';
  if (h.startsWith('Bearer anon_')) {
    const [aid, secret] = h.slice(7).split('.');
    const d = db.prepare('SELECT * FROM anon_devices WHERE id=?').get(aid);
    if (!d || !secret || d.token_hash !== crypto.createHash('sha256').update(secret).digest('hex')) return res.status(401).json({ error: 'unauthorized' });
    req.user = { id: aid, anon: true }; return next();
  }
  return requireAuth(req, res, next);
}
const anonLimiter = rateLimit({ windowMs: 60 * 60_000, limit: 5, standardHeaders: true, legacyHeaders: false });
// Issue a built-in AI token to a fresh install so people can use Cairn's AI without bringing their own key.
aiRouter.post('/device', anonLimiter, (req, res) => {
  const aid = 'anon_' + crypto.randomBytes(12).toString('base64url');
  const secret = crypto.randomBytes(32).toString('base64url');
  db.prepare('INSERT INTO anon_devices (id, token_hash, platform, created_at, ip) VALUES (?,?,?,?,?)')
    .run(aid, crypto.createHash('sha256').update(secret).digest('hex'), String(req.body?.platform || '').slice(0, 20), now(), req.ip);
  res.json({ token: `${aid}.${secret}`, dailyLimit: config.dailyAiRequestsAnon });
});

function quota(req, res, next) {
  const day = new Date().toISOString().slice(0, 10);
  const u = req.user.anon ? null : db.prepare('SELECT plan FROM users WHERE id=?').get(req.user.id);
  const row = db.prepare('SELECT ai_requests FROM usage WHERE user_id=? AND day=?').get(req.user.id, day);
  // Global ceiling for all anonymous installs together, so the built-in AI can't be farmed with rotating IPs.
  if (req.user.anon) {
    const all = db.prepare("SELECT COALESCE(SUM(ai_requests),0) n FROM usage WHERE day=? AND user_id LIKE 'anon_%'").get(day).n;
    if (all >= config.dailyAiAnonGlobal) return res.status(429).json({ error: 'Cairn AI is busy right now. Sign in to your Cairn Account to keep going.' });
  }
  const limit = req.user.anon ? config.dailyAiRequestsAnon : u?.plan === 'pro' ? 5000 : config.dailyAiRequestsFree;
  if ((row?.ai_requests || 0) >= limit) return res.status(429).json({ error: req.user.anon
    ? `Daily AI limit reached (${limit}). Sign in to your Cairn Account for more.`
    : `Daily AI limit reached (${limit}). It resets at midnight UTC.` });
  // Only successful requests count against the quota.
  res.on('finish', () => { if (res.statusCode < 400) db.prepare('INSERT INTO usage (user_id, day, ai_requests) VALUES (?,?,1) ON CONFLICT(user_id, day) DO UPDATE SET ai_requests=ai_requests+1').run(req.user.id, day); });
  next();
}

aiRouter.post('/chat', aiAuth, quota, async (req, res) => {
  const { messages, tools, tier, stream, temperature, maxTokens, json, preferred } = req.body || {};
  if (!Array.isArray(messages) || !messages.length) return res.status(400).json({ error: 'messages required' });
  try {
    // Server-side caps: the client can ask for less, never more.
    const cap = req.user.anon ? 2048 : 8192;
    const mt = Math.min(cap, Math.max(1, Number(maxTokens) || 1024));
    const msgs = messages.slice(-60);
    if (JSON.stringify(msgs).length > 400_000) return res.status(413).json({ error: 'Conversation too long' });
    const out = await chat({ messages: msgs, tools, tier, stream, temperature, maxTokens: mt, json, preferred }, config);
    if (stream) {
      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('X-Provider', out._provider);
      Readable.fromWeb(out.body).pipe(res);
    } else res.json(out);
  } catch (e) { res.status(e.status && e.status < 600 ? e.status : 502).json({ error: e.message }); }
});

aiRouter.post('/stt', aiAuth, quota, upload.single('audio'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'audio required' });
  try {
    const text = await transcribe(new Blob([req.file.buffer], { type: req.file.mimetype }), req.file.originalname || 'audio.webm', config, req.body.language);
    res.json({ text });
  } catch (e) { res.status(e.status || 502).json({ error: e.message }); }
});

aiRouter.get('/status', (_req, res) => res.json({ ready: !!(config.groqKey || config.nvidiaKey || config.freeFallback), groq: !!config.groqKey, nvidia: !!config.nvidiaKey, free: config.freeFallback, models: {
  smart: config.groqModel, fast: config.groqFastModel, nvidia: config.nvidiaModel } }));
