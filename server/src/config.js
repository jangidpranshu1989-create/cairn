import 'dotenv/config';
import crypto from 'node:crypto';

const isProd = process.env.NODE_ENV === 'production';

function required(name, fallback) {
  const v = process.env[name];
  if (v) return v;
  if (isProd && fallback === undefined) throw new Error(`Missing required env var ${name}`);
  return fallback;
}

export const config = {
  isProd,
  port: Number(process.env.PORT || 8787),
  publicUrl: process.env.PUBLIC_URL || 'http://localhost:8787',
  dataDir: process.env.DATA_DIR || './data',
  jwtSecret: required('JWT_SECRET', isProd ? undefined : crypto.randomBytes(32).toString('hex')),
  accessTtlSec: 15 * 60,
  refreshTtlSec: 60 * 60 * 24 * 30,
  // WebAuthn / passkeys
  rpId: process.env.RP_ID || 'localhost',
  rpName: process.env.RP_NAME || 'Saarthi',
  rpOrigins: (process.env.RP_ORIGINS || 'http://localhost:8787').split(',').map(s => s.trim()),
  corsOrigins: (process.env.CORS_ORIGINS || '*').split(',').map(s => s.trim()),
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
  mailFrom: process.env.MAIL_FROM || 'Saarthi <no-reply@saarthi.app>',
  // Limits
  maxTransferBytes: Number(process.env.MAX_TRANSFER_MB || 100) * 1024 * 1024,
  transferTtlHours: Number(process.env.TRANSFER_TTL_HOURS || 24),
  approvalTtlSec: Number(process.env.APPROVAL_TTL_SEC || 180),
  dailyAiRequestsFree: Number(process.env.DAILY_AI_FREE || 300),
};
