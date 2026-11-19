import express from 'express';
import {
  generateRegistrationOptions, verifyRegistrationResponse,
  generateAuthenticationOptions, verifyAuthenticationResponse,
} from '@simplewebauthn/server';
import crypto from 'node:crypto';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { requireAuth, issueTokens, publicUser } from './auth.js';

export const passkeyRouter = express.Router();

const putChallenge = (key, value) =>
  db.prepare('INSERT OR REPLACE INTO challenges (key, value, expires_at) VALUES (?,?,?)').run(key, value, now() + 5 * 60_000);
const takeChallenge = (key) => {
  const row = db.prepare('SELECT value, expires_at FROM challenges WHERE key=?').get(key);
  db.prepare('DELETE FROM challenges WHERE key=?').run(key);
  return row && row.expires_at > now() ? row.value : null;
};

// Register a new passkey for the signed-in user
passkeyRouter.post('/register/options', requireAuth, async (req, res) => {
  const u = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  const existing = db.prepare('SELECT id, transports FROM passkeys WHERE user_id=?').all(u.id);
  const options = await generateRegistrationOptions({
    rpName: config.rpName,
    rpID: config.rpId,
    userName: u.email,
    userDisplayName: u.name || u.email,
    userID: new TextEncoder().encode(u.id),
    attestationType: 'none',
    excludeCredentials: existing.map(c => ({ id: c.id, transports: c.transports ? JSON.parse(c.transports) : undefined })),
    authenticatorSelection: { residentKey: 'required', userVerification: 'preferred' },
  });
  putChallenge(`reg:${u.id}`, options.challenge);
  res.json(options);
});

passkeyRouter.post('/register/verify', requireAuth, async (req, res) => {
  const expectedChallenge = takeChallenge(`reg:${req.user.id}`);
  if (!expectedChallenge) return res.status(400).json({ error: 'Challenge expired, try again' });
  try {
    const v = await verifyRegistrationResponse({
      response: req.body.response, expectedChallenge,
      expectedOrigin: config.rpOrigins, expectedRPID: config.rpId,
    });
    if (!v.verified) throw new Error('not verified');
    const c = v.registrationInfo.credential;
    db.prepare('INSERT INTO passkeys (id, user_id, public_key, counter, transports, name, created_at) VALUES (?,?,?,?,?,?,?)')
      .run(c.id, req.user.id, Buffer.from(c.publicKey), c.counter, JSON.stringify(c.transports || []), String(req.body.name || 'Passkey').slice(0, 60), now());
    audit(req.user.id, 'passkey_added');
    res.json({ ok: true });
  } catch (e) { res.status(400).json({ error: 'Passkey registration failed: ' + e.message }); }
});

// Sign in with a passkey (discoverable credentials, no email needed)
passkeyRouter.post('/login/options', async (req, res) => {
  const options = await generateAuthenticationOptions({ rpID: config.rpId, userVerification: 'preferred' });
  const flow = crypto.randomBytes(16).toString('hex');
  putChallenge(`auth:${flow}`, options.challenge);
  res.json({ ...options, flow });
});

passkeyRouter.post('/login/verify', async (req, res) => {
  const expectedChallenge = takeChallenge(`auth:${req.body.flow}`);
  if (!expectedChallenge) return res.status(400).json({ error: 'Challenge expired, try again' });
  const cred = db.prepare('SELECT * FROM passkeys WHERE id=?').get(req.body.response?.id);
  if (!cred) return res.status(401).json({ error: 'This passkey is not registered' });
  try {
    const v = await verifyAuthenticationResponse({
      response: req.body.response, expectedChallenge,
      expectedOrigin: config.rpOrigins, expectedRPID: config.rpId,
      credential: { id: cred.id, publicKey: new Uint8Array(cred.public_key), counter: cred.counter, transports: JSON.parse(cred.transports || '[]') },
    });
    if (!v.verified) throw new Error('not verified');
    db.prepare('UPDATE passkeys SET counter=? WHERE id=?').run(v.authenticationInfo.newCounter, cred.id);
    const u = db.prepare('SELECT * FROM users WHERE id=?').get(cred.user_id);
    audit(u.id, 'login_passkey');
    res.json({ user: publicUser(u), ...issueTokens(u.id, { userAgent: req.get('user-agent'), ip: req.ip }) });
  } catch (e) { res.status(401).json({ error: 'Passkey sign-in failed: ' + e.message }); }
});

passkeyRouter.delete('/:id', requireAuth, (req, res) => {
  db.prepare('DELETE FROM passkeys WHERE id=? AND user_id=?').run(req.params.id, req.user.id);
  res.json({ ok: true });
});
