import 'dotenv/config';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const isProd = process.env.NODE_ENV === 'production';

// Supabase permanent storage: bring back the small files kept in DATA_DIR (generated secrets, OIDC signing key) before
// anything below reads them, so tokens and logins stay valid after a restart on a host with a temporary disk.
if (process.env.SUPABASE_URL && (process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY)) {
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY;
  const dir = process.env.DATA_DIR || './data'; fs.mkdirSync(dir, { recursive: true });
  try {
    const r = await fetch(`${process.env.SUPABASE_URL.replace(/\/+$/, '')}/storage/v1/object/${process.env.SUPABASE_BUCKET || 'cairn'}/files.json`,
      { headers: { Authorization: `Bearer ${key}`, apikey: key }, signal: AbortSignal.timeout(30_000) });
    if (r.ok) {
      const files = await r.json();
      for (const [name, b64] of Object.entries(files)) {
        if (/^[\w.-]+$/.test(name) && !fs.existsSync(path.join(dir, name))) fs.writeFileSync(path.join(dir, name), Buffer.from(b64, 'base64'), { mode: 0o600 });
      }
    }
  } catch (e) { console.error('[supabase] could not restore DATA_DIR files:', e.message); }
}

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
  freeFallback: process.env.AI_FREE_FALLBACK !== '0',
  freeModel: process.env.AI_FREE_MODEL || 'default',
  openrouterKey: process.env.OPENROUTER_API_KEY || '',
  openrouterModel: process.env.OPENROUTER_MODEL || 'openrouter/free',
  groqModel: process.env.GROQ_MODEL || 'openai/gpt-oss-120b',
  groqFastModel: process.env.GROQ_FAST_MODEL || 'openai/gpt-oss-20b',
  nvidiaModel: process.env.NVIDIA_MODEL || 'nvidia/nemotron-3.5-super-vl-120b-a12b',
  nvidiaFastModel: process.env.NVIDIA_FAST_MODEL || 'nvidia/nemotron-3.5-bolt-e4b',
  // Image understanding needs multimodal models (the text models above reject images).
  groqVisionModel: process.env.GROQ_VISION_MODEL || 'meta-llama/llama-4-scout-17b-16e-instruct',
  nvidiaVisionModel: process.env.NVIDIA_VISION_MODEL || 'nvidia/nemotron-3.5-super-vl-120b-a12b',
  // Optional: Google Gemini (free tier key from aistudio.google.com) as another vision + chat provider.
  geminiKey: process.env.GEMINI_API_KEY || '',
  geminiModel: process.env.GEMINI_MODEL || 'gemini-flash-latest',
  sttModel: process.env.STT_MODEL || 'whisper-large-v3-turbo',
  // Mail (password reset / verification)
  smtpUrl: process.env.SMTP_URL || '',
  tursoUrl: process.env.TURSO_DATABASE_URL || '',
  // Supabase (free): permanent copy of the database in a private Storage bucket.
  supabaseUrl: process.env.SUPABASE_URL || '',
  supabaseKey: process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY || '',
  supabaseBucket: process.env.SUPABASE_BUCKET || 'cairn',
  supabaseRequired: process.env.SUPABASE_REQUIRED === '1',
  snapshotSeconds: Number(process.env.SNAPSHOT_SECONDS || 5),
  tursoToken: process.env.TURSO_AUTH_TOKEN || '',
  mailFrom: process.env.MAIL_FROM || 'Cairn <no-reply@example.com>',
  // Limits
  maxTransferBytes: Number(process.env.MAX_TRANSFER_MB || 100) * 1024 * 1024,
  transferTtlHours: Number(process.env.TRANSFER_TTL_HOURS || 24),
  approvalTtlSec: Number(process.env.APPROVAL_TTL_SEC || 180),
  dailyAiRequestsFree: Number(process.env.DAILY_AI_FREE || 300),
  dailyAiRequestsAnon: Number(process.env.DAILY_AI_ANON || 150),
  dailyAiAnonPerIp: Number(process.env.DAILY_AI_ANON_PER_IP || 1500),
  dailyAiAnonGlobal: Number(process.env.DAILY_AI_ANON_GLOBAL || 40000),
};
