import express from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import crypto from 'node:crypto';
import svgCaptcha from 'svg-captcha';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { sendMail } from './mail.js';

export const id = (p = '') => p + crypto.randomBytes(12).toString('base64url');
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

// ---------- Tokens ----------
export function issueTokens(userId, { deviceId = null, userAgent = '', ip = '' } = {}) {
  const sid = id('s_');
  const refresh = crypto.randomBytes(32).toString('base64url');
  db.prepare(`INSERT INTO sessions (id, user_id, device_id, refresh_hash, user_agent, ip, created_at, last_used_at)
              VALUES (?,?,?,?,?,?,?,?)`).run(sid, userId, deviceId, sha256(refresh), userAgent.slice(0, 200), ip, now(), now());
  return {
    accessToken: signAccess(userId, sid, deviceId),
    refreshToken: `${sid}.${refresh}`,
    expiresIn: config.accessTtlSec,
  };
}
function signAccess(userId, sid, deviceId) {
  return jwt.sign({ sub: userId, sid, did: deviceId || undefined }, config.jwtSecret, { expiresIn: config.accessTtlSec });
}

export function verifyAccess(token) {
  try {
    const p = jwt.verify(token, config.jwtSecret);
    const s = db.prepare('SELECT revoked FROM sessions WHERE id=?').get(p.sid);
    if (!s || s.revoked) return null;
    return p;
  } catch { return null; }
}

export function requireAuth(req, res, next) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : null;
  const p = token && verifyAccess(token);
  if (!p) return res.status(401).json({ error: 'unauthorized' });
  req.user = { id: p.sub, sid: p.sid, deviceId: p.did || null };
  next();
}

// ---------- Captcha (self-hosted, stateless signed token) ----------
function makeCaptcha() {
  const c = svgCaptcha.create({ size: 5, noise: 3, color: true, ignoreChars: '0oO1ilI', background: '#f4efe6' });
  const exp = now() + 5 * 60_000;
  const nonce = crypto.randomBytes(8).toString('hex');
  const sig = crypto.createHmac('sha256', config.jwtSecret).update(`${c.text.toLowerCase()}|${exp}|${nonce}`).digest('base64url');
  return { svg: c.data, token: `${exp}.${nonce}.${sig}`, ...(!config.isProd && process.env.CAPTCHA_DEV_ANSWER ? { devAnswer: c.text } : {}) };
}
const usedCaptcha = new Map();
function checkCaptcha(token, answer) {
  if (!token || !answer) return false;
  const [exp, nonce, sig] = String(token).split('.');
  if (!exp || Number(exp) < now() || usedCaptcha.has(nonce)) return false;
  const expect = crypto.createHmac('sha256', config.jwtSecret).update(`${String(answer).toLowerCase().trim()}|${exp}|${nonce}`).digest('base64url');
  const ok = sig.length === expect.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect));
  if (ok) { usedCaptcha.set(nonce, Number(exp)); if (usedCaptcha.size > 5000) for (const [k, v] of usedCaptcha) if (v < now()) usedCaptcha.delete(k); }
  return ok;
}

// ---------- Validation ----------
const emailS = z.string().trim().toLowerCase().email().max(200);
const passwordS = z.string().min(10, 'Password must be at least 10 characters').max(200)
  .refine(p => /[A-Za-z]/.test(p) && /\d/.test(p), 'Password needs letters and numbers');

export const publicUser = (u) => u && ({ id: u.id, email: u.email, name: u.name, plan: u.plan, emailVerified: !!u.email_verified, createdAt: u.created_at });

const authLimiter = rateLimit({ windowMs: 15 * 60_000, limit: 50, standardHeaders: true, legacyHeaders: false });

export const authRouter = express.Router();

authRouter.get('/captcha', (req, res) => res.json(makeCaptcha()));

authRouter.post('/signup', authLimiter, async (req, res) => {
  const parsed = z.object({ email: emailS, password: passwordS, name: z.string().trim().max(80).default(''),
    captchaToken: z.string(), captchaAnswer: z.string() }).safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: parsed.error.issues[0].message });
  const { email, password, name, captchaToken, captchaAnswer } = parsed.data;
  if (!checkCaptcha(captchaToken, captchaAnswer)) return res.status(400).json({ error: 'Captcha incorrect, please try again', captcha: true });
  if (db.prepare('SELECT 1 FROM users WHERE email=?').get(email)) return res.status(409).json({ error: 'An account with this email already exists' });
  const uid = id('u_');
  db.prepare('INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?,?,?,?,?)')
    .run(uid, email, name, await bcrypt.hash(password, 12), now());
  audit(uid, 'signup');
  const verifyToken = jwt.sign({ sub: uid, purpose: 'verify' }, config.jwtSecret, { expiresIn: '2d' });
  sendMail(email, 'Verify your Saarthi account', `Welcome to Saarthi.\n\nVerify your email: ${config.publicUrl}/api/auth/verify?t=${verifyToken}`).catch(() => {});
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(uid);
  res.json({ user: publicUser(user), ...issueTokens(uid, { userAgent: req.get('user-agent'), ip: req.ip }) });
});

authRouter.get('/verify', (req, res) => {
  try {
    const p = jwt.verify(String(req.query.t), config.jwtSecret);
    if (p.purpose !== 'verify') throw new Error();
    db.prepare('UPDATE users SET email_verified=1 WHERE id=?').run(p.sub);
    res.redirect('/account.html?verified=1');
  } catch { res.status(400).send('Verification link is invalid or expired.'); }
});

authRouter.post('/login', authLimiter, async (req, res) => {
  const parsed = z.object({ email: emailS, password: z.string().max(200), captchaToken: z.string().optional(), captchaAnswer: z.string().optional() }).safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: 'Enter a valid email and password' });
  const { email, password, captchaToken, captchaAnswer } = parsed.data;
  const user = db.prepare('SELECT * FROM users WHERE email=?').get(email);
  // After 3 failures a captcha is required; after 10 the account is locked for 15 minutes.
  if (user && user.locked_until > now()) return res.status(429).json({ error: 'Too many attempts. Try again in a few minutes.' });
  if (user && user.failed_logins >= 3 && !checkCaptcha(captchaToken, captchaAnswer)) return res.status(400).json({ error: 'Please solve the captcha', captcha: true });
  const ok = user && user.password_hash && await bcrypt.compare(password, user.password_hash);
  if (!ok) {
    if (user) {
      const f = user.failed_logins + 1;
      db.prepare('UPDATE users SET failed_logins=?, locked_until=? WHERE id=?').run(f, f >= 10 ? now() + 15 * 60_000 : 0, user.id);
      audit(user.id, 'login_failed', { ip: req.ip });
    }
    return res.status(401).json({ error: 'Email or password is incorrect', captcha: !!user && user.failed_logins + 1 >= 3 });
  }
  db.prepare('UPDATE users SET failed_logins=0, locked_until=0 WHERE id=?').run(user.id);
  audit(user.id, 'login', { ip: req.ip });
  res.json({ user: publicUser(user), ...issueTokens(user.id, { userAgent: req.get('user-agent'), ip: req.ip }) });
});

authRouter.post('/refresh', (req, res) => {
  const [sid, secret] = String(req.body?.refreshToken || '').split('.');
  const s = sid && db.prepare('SELECT * FROM sessions WHERE id=?').get(sid);
  if (!s || s.revoked || !secret || s.refresh_hash !== sha256(secret) || s.created_at + config.refreshTtlSec * 1000 < now())
    return res.status(401).json({ error: 'Session expired, please sign in again' });
  // Rotate refresh token on every use
  const next = crypto.randomBytes(32).toString('base64url');
  db.prepare('UPDATE sessions SET refresh_hash=?, last_used_at=? WHERE id=?').run(sha256(next), now(), sid);
  res.json({ accessToken: signAccess(s.user_id, sid, s.device_id), refreshToken: `${sid}.${next}`, expiresIn: config.accessTtlSec });
});

authRouter.post('/logout', requireAuth, (req, res) => {
  db.prepare('UPDATE sessions SET revoked=1 WHERE id=?').run(req.user.sid);
  res.json({ ok: true });
});

authRouter.get('/me', requireAuth, (req, res) => {
  const u = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  if (!u) return res.status(404).json({ error: 'not found' });
  const passkeys = db.prepare('SELECT id, name, created_at FROM passkeys WHERE user_id=?').all(u.id);
  const sessions = db.prepare('SELECT id, user_agent, ip, created_at, last_used_at FROM sessions WHERE user_id=? AND revoked=0 ORDER BY last_used_at DESC').all(u.id);
  res.json({ user: publicUser(u), passkeys, sessions: sessions.map(s => ({ ...s, current: s.id === req.user.sid })) });
});

authRouter.patch('/me', requireAuth, (req, res) => {
  const p = z.object({ name: z.string().trim().max(80) }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: 'Invalid name' });
  db.prepare('UPDATE users SET name=? WHERE id=?').run(p.data.name, req.user.id);
  res.json({ ok: true });
});

authRouter.post('/change-password', requireAuth, async (req, res) => {
  const p = z.object({ current: z.string(), next: passwordS }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: p.error.issues[0].message });
  const u = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  if (u.password_hash && !(await bcrypt.compare(p.data.current, u.password_hash))) return res.status(401).json({ error: 'Current password is incorrect' });
  db.prepare('UPDATE users SET password_hash=? WHERE id=?').run(await bcrypt.hash(p.data.next, 12), u.id);
  db.prepare('UPDATE sessions SET revoked=1 WHERE user_id=? AND id<>?').run(u.id, req.user.sid);
  audit(u.id, 'password_changed');
  res.json({ ok: true });
});

authRouter.post('/forgot', authLimiter, async (req, res) => {
  const email = emailS.safeParse(req.body?.email);
  if (email.success) {
    const u = db.prepare('SELECT id FROM users WHERE email=?').get(email.data);
    if (u) {
      const token = crypto.randomBytes(32).toString('base64url');
      db.prepare('INSERT INTO reset_tokens (token_hash, user_id, expires_at) VALUES (?,?,?)').run(sha256(token), u.id, now() + 30 * 60_000);
      await sendMail(email.data, 'Reset your Saarthi password', `Reset your password (valid 30 minutes): ${config.publicUrl}/reset.html?t=${token}`).catch(() => {});
    }
  }
  res.json({ ok: true }); // never reveal whether an email exists
});

authRouter.post('/reset', authLimiter, async (req, res) => {
  const p = z.object({ token: z.string(), password: passwordS }).safeParse(req.body);
  if (!p.success) return res.status(400).json({ error: p.error.issues[0].message });
  const row = db.prepare('SELECT * FROM reset_tokens WHERE token_hash=?').get(sha256(p.data.token));
  if (!row || row.expires_at < now()) return res.status(400).json({ error: 'Reset link is invalid or expired' });
  db.prepare('UPDATE users SET password_hash=?, failed_logins=0, locked_until=0 WHERE id=?').run(await bcrypt.hash(p.data.password, 12), row.user_id);
  db.prepare('DELETE FROM reset_tokens WHERE user_id=?').run(row.user_id);
  db.prepare('UPDATE sessions SET revoked=1 WHERE user_id=?').run(row.user_id);
  audit(row.user_id, 'password_reset');
  res.json({ ok: true });
});

authRouter.delete('/sessions/:sid', requireAuth, (req, res) => {
  db.prepare('UPDATE sessions SET revoked=1 WHERE id=? AND user_id=?').run(req.params.sid, req.user.id);
  res.json({ ok: true });
});

authRouter.delete('/account', requireAuth, async (req, res) => {
  const u = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  if (u.password_hash && !(await bcrypt.compare(String(req.body?.password || ''), u.password_hash))) return res.status(401).json({ error: 'Password is incorrect' });
  db.prepare('DELETE FROM users WHERE id=?').run(u.id);
  audit(u.id, 'account_deleted');
  res.json({ ok: true });
});
