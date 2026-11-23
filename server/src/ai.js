import express from 'express';
import multer from 'multer';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import crypto from 'node:crypto';
import rateLimit from 'express-rate-limit';
import { db, now } from './db.js';
import { config } from './config.js';
import { requireAuth } from './auth.js';
import { chat, transcribe, AllProvidersFailed } from './providers.js';

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
// Mobile carriers put thousands of phones behind one IP (CGNAT), so the per-IP token issue limit is generous; abuse is
// bounded by the per-IP daily AI ceiling in quota() instead.
const anonLimiter = rateLimit({ windowMs: 60 * 60_000, limit: 60, standardHeaders: true, legacyHeaders: false });
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
  if (req.user.anon) {
    // Fair use per network: anonymous installs on the same IP share one daily ceiling, so a single abuser rotating
    // device tokens can only exhaust their own network's allowance, never everyone's.
    const ip = req.ip || '';
    const perIp = db.prepare("SELECT COALESCE(SUM(u.ai_requests),0) n FROM usage u JOIN anon_devices d ON d.id=u.user_id WHERE u.day=? AND d.ip=?").get(day, ip).n;
    if (perIp >= config.dailyAiAnonPerIp) return busy(res, 'Lots of people on your network are using Cairn AI today. Sign in to your free Cairn Account to keep going.', 3600);
    // Last-resort safety valve for the whole server (set high; the per-IP ceiling does the real work).
    const all = db.prepare("SELECT COALESCE(SUM(ai_requests),0) n FROM usage WHERE day=? AND user_id LIKE 'anon_%'").get(day).n;
    if (all >= config.dailyAiAnonGlobal) return busy(res, 'Cairn AI is very busy right now. Sign in to your free Cairn Account to keep going.', 600);
  }
  const limit = req.user.anon ? config.dailyAiRequestsAnon : u?.plan === 'pro' ? 5000 : config.dailyAiRequestsFree;
  if ((row?.ai_requests || 0) >= limit) return busy(res, req.user.anon
    ? `Daily AI limit reached (${limit}). Sign in to your free Cairn Account for more.`
    : `Daily AI limit reached (${limit}). It resets at midnight UTC.`, 3600);
  // Only successful requests count against the quota.
  res.on('finish', () => { if (res.statusCode < 400) db.prepare('INSERT INTO usage (user_id, day, ai_requests) VALUES (?,?,1) ON CONFLICT(user_id, day) DO UPDATE SET ai_requests=ai_requests+1').run(req.user.id, day); });
  next();
}
function busy(res, error, retryAfter) { res.set('Retry-After', String(retryAfter)); return res.status(429).json({ error, code: 'quota' }); }

/**
 * Chat completions. Accepts Cairn's own body ({ messages, tools, tier, maxTokens, ... }) and plain OpenAI bodies
 * ({ model, messages, max_tokens, ... }), so every client path works. Upstream failures never leak as 404/402: when
 * every provider is busy the answer is 503 + Retry-After with the per-provider attempts.
 */
async function chatHandler(req, res) {
  const b = req.body || {};
  const { messages, tools, stream, temperature, json, preferred } = b;
  const tier = ['smart', 'fast', 'vision'].includes(b.tier) ? b.tier : /fast|mini|small|20b|8b/i.test(String(b.model || '')) ? 'fast' : 'smart';
  if (!Array.isArray(messages) || !messages.length) return res.status(400).json({ error: 'messages required' });
  try {
    // Server-side caps: the client can ask for less, never more.
    const cap = req.user.anon ? 3000 : 8192;
    const mt = Math.min(cap, Math.max(1, Number(b.maxTokens ?? b.max_tokens) || 1024));
    const msgs = messages.slice(-60);
    if (JSON.stringify(msgs).length > 400_000) return res.status(413).json({ error: 'Conversation too long' });
    const out = await chat({ messages: msgs, tools, tier, stream, temperature, maxTokens: mt, json: json || b.response_format?.type === 'json_object', preferred }, config);
    if (stream) {
      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('X-Provider', out._provider);
      // pipeline() (not .pipe()) so an upstream drop or the stream timeout ends this response instead of throwing an
      // unhandled 'error' that would take down the whole server.
      try { await pipeline(Readable.fromWeb(out.body), res); }
      catch (e) { if (!res.writableEnded) res.destroy(); if (!/aborted|premature close/i.test(String(e?.code || e?.message))) console.warn('[ai] stream ended early:', e?.name || e?.message); }
    } else res.json(out);
  } catch (e) {
    if (e instanceof AllProvidersFailed) { res.set('Retry-After', String(e.retryAfter)); return res.status(503).json({ error: e.message, code: 'ai_busy', attempts: e.attempts }); }
    // Anything an upstream provider said (404 model not found, 402 payment required, ...) is a gateway problem here.
    const st = e.status === 400 || e.status === 413 ? e.status : 502;
    res.status(st).json({ error: e.message, code: 'ai_upstream' });
  }
}
const CHAT_PATHS = ['/chat', '/chat/completions', '/completions', '/v1/chat/completions', '/'];
aiRouter.post(CHAT_PATHS, aiAuth, quota, chatHandler);

aiRouter.post('/stt', aiAuth, quota, upload.single('audio'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'audio required' });
  try {
    const text = await transcribe(new Blob([req.file.buffer], { type: req.file.mimetype }), req.file.originalname || 'audio.webm', config, req.body.language);
    res.json({ text });
  } catch (e) { res.status(e.status || 502).json({ error: e.message }); }
});

aiRouter.get(['/status', '/health'], (_req, res) => res.json({ ok: true, ready: !!(config.groqKey || config.nvidiaKey || config.freeFallback), groq: !!config.groqKey, nvidia: !!config.nvidiaKey, free: config.freeFallback, models: {
  smart: config.groqModel, fast: config.groqFastModel, nvidia: config.nvidiaModel } }));
