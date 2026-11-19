import express from 'express';
import multer from 'multer';
import { Readable } from 'node:stream';
import { db } from './db.js';
import { config } from './config.js';
import { requireAuth } from './auth.js';
import { chat, transcribe } from './providers.js';

export const aiRouter = express.Router();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 25 * 1024 * 1024 } });

function quota(req, res, next) {
  const day = new Date().toISOString().slice(0, 10);
  const u = db.prepare('SELECT plan FROM users WHERE id=?').get(req.user.id);
  const row = db.prepare('SELECT ai_requests FROM usage WHERE user_id=? AND day=?').get(req.user.id, day);
  const limit = u?.plan === 'pro' ? 5000 : config.dailyAiRequestsFree;
  if ((row?.ai_requests || 0) >= limit) return res.status(429).json({ error: `Daily AI limit reached (${limit}). Upgrade to Pro or add your own API key in Settings.` });
  db.prepare('INSERT INTO usage (user_id, day, ai_requests) VALUES (?,?,1) ON CONFLICT(user_id, day) DO UPDATE SET ai_requests=ai_requests+1').run(req.user.id, day);
  next();
}

aiRouter.post('/chat', requireAuth, quota, async (req, res) => {
  const { messages, tools, tier, stream, temperature, maxTokens, json, preferred } = req.body || {};
  if (!Array.isArray(messages) || !messages.length) return res.status(400).json({ error: 'messages required' });
  try {
    const out = await chat({ messages, tools, tier, stream, temperature, maxTokens, json, preferred }, config);
    if (stream) {
      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('X-Provider', out._provider);
      Readable.fromWeb(out.body).pipe(res);
    } else res.json(out);
  } catch (e) { res.status(e.status && e.status < 600 ? e.status : 502).json({ error: e.message }); }
});

aiRouter.post('/stt', requireAuth, quota, upload.single('audio'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'audio required' });
  try {
    const text = await transcribe(new Blob([req.file.buffer], { type: req.file.mimetype }), req.file.originalname || 'audio.webm', config, req.body.language);
    res.json({ text });
  } catch (e) { res.status(e.status || 502).json({ error: e.message }); }
});

aiRouter.get('/status', (_req, res) => res.json({ groq: !!config.groqKey, nvidia: !!config.nvidiaKey, models: {
  smart: config.groqModel, fast: config.groqFastModel, nvidia: config.nvidiaModel } }));
