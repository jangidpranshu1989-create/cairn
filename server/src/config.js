import 'dotenv/config';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const isProd = process.env.NODE_ENV === 'production';

// Secrets that must survive restarts: env var if set, otherwise generated once and kept in DATA_DIR (0600).
function persistedSecret(name, file) {
  if (process.env[name]) return process.env[name];
  const dir = process.env.DATA_DIR || './data'; fs.mkdirSync(dir, { recursive: true });
  const f = path.join(dir, file);
  try { return fs.readFileSync(f, 'utf8').trim(); } catch {}
  const v = crypto.randomBytes(48).toString('base64url');
  fs.writeFileSync(f, v, { mode: 0o600 }); return v;
}

function required(name, fallback) {
  const v = process.env[name];
  if (v) return v;
  if (isProd && fallback === undefined) throw new Error(`Missing required env var ${name}`);
  return fallback;
}

export const config = {
  isProd,
  port: Number(process.env.PORT || 8787),
  publicUrl: (process.env.PUBLIC_URL || 'http://localhost:8787').replace(/\/$/, ''),
  dataDir: process.env.DATA_DIR || './data',
  jwtSecret: persistedSecret('JWT_SECRET', 'jwt.secret'),
  accessTtlSec: 15 * 60,
  refreshTtlSec: 60 * 60 * 24 * 30,
  // WebAuthn / passkeys
  rpId: process.env.RP_ID || new URL(process.env.PUBLIC_URL || 'http://localhost:8787').hostname,
  rpName: process.env.RP_NAME || 'Cairn',
  rpOrigins: (process.env.RP_ORIGINS || new URL(process.env.PUBLIC_URL || 'http://localhost:8787').origin).split(',').map(s => s.trim()),
  // Production defaults to the public site only (desktop pages use cairn:// and native clients send no Origin).
  corsOrigins: (process.env.CORS_ORIGINS || (isProd ? (process.env.PUBLIC_URL || '') : '*')).split(',').map(s => s.trim()).filter(Boolean),
  // AI providers
  groqKey: process.env.GROQ_API_KEY || '',
  nvidiaKey: process.env.NVIDIA_API_KEY || '',
  groqModel: process.env.GROQ_MODEL || 'openai/gpt-oss-120b',
  groqFastModel: process.env.GROQ_FAST_MODEL || 'openai/gpt-oss-20b',
  nvidiaModel: process.env.NVIDIA_MODEL || 'nvidia/nemotron-3.5-super-vl-120b-a12b',
  nvidiaFastModel: process.env.NVIDIA_FAST_MODEL || 'nvidia/nemotron-3.5-bolt-e4b',
  sttModel: process.env.STT_MODEL || 'whisper-large-v3-turbo',
  // Mail (password reset / verification)
  smtpUrl: process.env.SMTP_URL || '',
  mailFrom: process.env.MAIL_FROM || 'Cairn <no-reply@cairn.app>',
  // Limits
  maxTransferBytes: Number(process.env.MAX_TRANSFER_MB || 100) * 1024 * 1024,
  transferTtlHours: Number(process.env.TRANSFER_TTL_HOURS || 24),
  approvalTtlSec: Number(process.env.APPROVAL_TTL_SEC || 180),
  dailyAiRequestsFree: Number(process.env.DAILY_AI_FREE || 300),
  dailyAiRequestsAnon: Number(process.env.DAILY_AI_ANON || 60),
};
