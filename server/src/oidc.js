// "Continue with Cairn": a standards-based OAuth 2.0 + OpenID Connect provider.
//   - Authorization Code flow with PKCE (S256), required for public clients (SPAs, mobile apps)
//   - client_secret_basic / client_secret_post for confidential (server-side) clients
//   - RS256-signed ID tokens and access tokens, published JWKS, discovery document
//   - Refresh tokens (offline_access) with rotation + reuse detection
//   - Per-user consent records ("Apps with access to your account") that can be revoked
// Sign-in and consent UI live in /public/accounts (the Cairn Account portal); Cairn browsers on Android and
// desktop recognise /oauth/authorize and finish the flow natively with one tap.
import express from 'express';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { db, now, audit } from './db.js';
import { config } from './config.js';
import { requireAuth, id } from './auth.js';

db.exec(`
CREATE TABLE IF NOT EXISTS oauth_clients (
  id TEXT PRIMARY KEY, secret_hash TEXT, name TEXT NOT NULL, homepage TEXT NOT NULL DEFAULT '', logo TEXT NOT NULL DEFAULT '',
  redirect_uris TEXT NOT NULL, owner_id TEXT, type TEXT NOT NULL DEFAULT 'public', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_codes (
  hash TEXT PRIMARY KEY, client_id TEXT NOT NULL, user_id TEXT NOT NULL, redirect_uri TEXT NOT NULL, scope TEXT NOT NULL,
  nonce TEXT, challenge TEXT, auth_time INTEGER NOT NULL, exp INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS oauth_refresh (
  id TEXT PRIMARY KEY, hash TEXT NOT NULL, prev_hash TEXT, client_id TEXT NOT NULL, user_id TEXT NOT NULL, scope TEXT NOT NULL,
  auth_time INTEGER NOT NULL, exp INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_consents (
  user_id TEXT NOT NULL, client_id TEXT NOT NULL, scope TEXT NOT NULL, created_at INTEGER NOT NULL, last_used_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, client_id)
);
`);

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const b64u = (b) => Buffer.from(b).toString('base64url');
const SCOPES = {
  openid: 'Sign you in with your Cairn Account',
  profile: 'See your name',
  email: 'See your email address',
  offline_access: 'Stay signed in when you are not using the app',
};
const CODE_TTL = 60_000, ID_TTL = 3600, AT_TTL = 3600, RT_TTL = 90 * 24 * 3600_000;

// ---------- Signing key (RSA-2048, persisted in DATA_DIR) ----------
const keyFile = path.join(config.dataDir, 'oidc-signing.pem');
let pem;
try { pem = fs.readFileSync(keyFile, 'utf8'); } catch {
  pem = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  fs.mkdirSync(config.dataDir, { recursive: true }); fs.writeFileSync(keyFile, pem, { mode: 0o600 });
}
const privateKey = crypto.createPrivateKey(pem);
const publicJwk = crypto.createPublicKey(privateKey).export({ format: 'jwk' });
const kid = b64u(crypto.createHash('sha256').update(publicJwk.n).digest()).slice(0, 16);
const JWKS = { keys: [{ ...publicJwk, kid, use: 'sig', alg: 'RS256' }] };
const sign = (payload, ttl) => jwt.sign(payload, privateKey, { algorithm: 'RS256', keyid: kid, expiresIn: ttl, issuer: config.publicUrl });

// ---------- Clients ----------
const getClient = (cid) => {
  const c = cid && db.prepare('SELECT * FROM oauth_clients WHERE id=?').get(String(cid));
  return c && { ...c, redirect_uris: JSON.parse(c.redirect_uris) };
};
const host = (u) => { try { return new URL(u).host; } catch { return ''; } };
function validRedirect(u) {
  try {
    const x = new URL(u);
    if (x.hash) return false;
    if (x.protocol === 'https:') return true;
    if (x.protocol === 'http:') return ['localhost', '127.0.0.1', '[::1]'].includes(x.hostname);  // RFC 8252 loopback
    return /^[a-z][a-z0-9+.-]*\.[a-z0-9+.-]+:$/.test(x.protocol);  // private-use scheme for native apps, e.g. com.example.app:/cb
  } catch { return false; }
}

/** Validate an authorization request. Errors that must NOT redirect (bad client / redirect_uri) return {fatal}. */
function checkRequest(q) {
  const client = getClient(q.client_id);
  if (!client) return { fatal: 'Unknown app (client_id).' };
  const redirect = String(q.redirect_uri || '');
  if (!client.redirect_uris.includes(redirect)) return { fatal: 'This app sent a redirect address that is not registered with Cairn.' };
  const fail = (error, desc) => ({ client, redirect, error, desc });
  if (q.response_type !== 'code') return fail('unsupported_response_type', 'Only response_type=code is supported');
  const scopes = [...new Set(String(q.scope || 'openid').split(/\s+/).filter(Boolean))];
  if (!scopes.includes('openid')) return fail('invalid_scope', 'scope must include openid');
  if (scopes.some(s => !SCOPES[s])) return fail('invalid_scope', 'Unknown scope');
  const challenge = q.code_challenge ? String(q.code_challenge) : null;
  if (challenge && (q.code_challenge_method || 'plain') !== 'S256') return fail('invalid_request', 'code_challenge_method must be S256');
  if (challenge && !/^[A-Za-z0-9_-]{43,128}$/.test(challenge)) return fail('invalid_request', 'Bad code_challenge');
  if (client.type === 'public' && !challenge) return fail('invalid_request', 'PKCE (code_challenge) is required for this app');
  return { client, redirect, scopes, challenge, state: q.state ? String(q.state).slice(0, 500) : undefined, nonce: q.nonce ? String(q.nonce).slice(0, 500) : undefined, prompt: String(q.prompt || '') };
}
function redirectWith(uri, params) {
  const u = new URL(uri);
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null) u.searchParams.set(k, v);
  return u.toString();
}
const clientInfo = (c) => ({ id: c.id, name: c.name, homepage: c.homepage, logo: c.logo, domain: host(c.homepage || c.redirect_uris[0]), firstParty: c.owner_id === null });

// ---------- Routers ----------
export const oidcRouter = express.Router();     // mounted at "/" (well-known + /oauth/*)
export const oauthApi = express.Router();       // mounted at "/api/oauth" (portal + native one-tap)

const openCors = (req, res, next) => {   // token/userinfo/jwks are called cross-origin by SPAs (no cookies, Bearer only)
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
  res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  if (req.method === 'OPTIONS') return res.status(204).end();
  next();
};
const tokenLimiter = rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: true, legacyHeaders: false });

oidcRouter.get('/.well-known/openid-configuration', openCors, (_req, res) => {
  const I = config.publicUrl;
  res.json({
    issuer: I,
    authorization_endpoint: `${I}/oauth/authorize`,
    token_endpoint: `${I}/oauth/token`,
    userinfo_endpoint: `${I}/oauth/userinfo`,
    revocation_endpoint: `${I}/oauth/revoke`,
    jwks_uri: `${I}/oauth/jwks`,
    end_session_endpoint: `${I}/accounts/?signout=1`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    subject_types_supported: ['public'],
    id_token_signing_alg_values_supported: ['RS256'],
    scopes_supported: Object.keys(SCOPES),
    token_endpoint_auth_methods_supported: ['none', 'client_secret_basic', 'client_secret_post'],
    code_challenge_methods_supported: ['S256'],
    claims_supported: ['sub', 'iss', 'aud', 'exp', 'iat', 'auth_time', 'nonce', 'name', 'email', 'email_verified'],
  });
});
oidcRouter.get('/oauth/jwks', openCors, (_req, res) => res.set('Cache-Control', 'public, max-age=3600').json(JWKS));

// Browser entry point. Bad client/redirect: show an error page (never redirect to an unverified URL).
oidcRouter.get('/oauth/authorize', (req, res) => {
  const r = checkRequest(req.query);
  if (r.fatal) return res.redirect(302, `${config.publicUrl}/accounts/consent.html?error=${encodeURIComponent(r.fatal)}`);
  if (r.error) return res.redirect(302, redirectWith(r.redirect, { error: r.error, error_description: r.desc, state: req.query.state, iss: config.publicUrl }));
  const qs = new URLSearchParams(req.query).toString();
  res.redirect(302, `${config.publicUrl}/accounts/consent.html?${qs}`);
});

// Portal / native: describe the request so the UI can show "Continue as …".
oauthApi.get('/request', (req, res) => {
  const r = checkRequest(req.query);
  if (r.fatal) return res.status(400).json({ error: r.fatal });
  if (r.error) return res.status(400).json({ error: r.desc, redirect: redirectWith(r.redirect, { error: r.error, error_description: r.desc, state: req.query.state, iss: config.publicUrl }) });
  res.json({ client: clientInfo(r.client), scopes: r.scopes.map(s => ({ id: s, label: SCOPES[s] })), prompt: r.prompt });
});

// Signed-in user approves (or denies). Returns the redirect URL carrying the code.
oauthApi.post('/approve', requireAuth, (req, res) => {
  const b = req.body || {};
  const r = checkRequest(b);
  if (r.fatal) return res.status(400).json({ error: r.fatal });
  if (r.error) return res.status(400).json({ error: r.desc });
  if (b.approve === false) return res.json({ redirect: redirectWith(r.redirect, { error: 'access_denied', state: r.state, iss: config.publicUrl }) });
  const user = db.prepare('SELECT * FROM users WHERE id=?').get(req.user.id);
  if (!user) return res.status(401).json({ error: 'unauthorized' });
  const sess = db.prepare('SELECT auth_at FROM sessions WHERE id=?').get(req.user.sid);
  const code = crypto.randomBytes(32).toString('base64url');
  db.prepare('INSERT INTO oauth_codes (hash, client_id, user_id, redirect_uri, scope, nonce, challenge, auth_time, exp) VALUES (?,?,?,?,?,?,?,?,?)')
    .run(sha256(code), r.client.id, user.id, r.redirect, r.scopes.join(' '), r.nonce || null, r.challenge, Math.floor((sess?.auth_at || now()) / 1000), now() + CODE_TTL);
  db.prepare(`INSERT INTO oauth_consents (user_id, client_id, scope, created_at, last_used_at) VALUES (?,?,?,?,?)
              ON CONFLICT(user_id, client_id) DO UPDATE SET scope=excluded.scope, last_used_at=excluded.last_used_at`).run(user.id, r.client.id, r.scopes.join(' '), now(), now());
  audit(user.id, 'oauth_consent', { client: r.client.id, scope: r.scopes.join(' '), via: String(b.via || 'web').slice(0, 20) });
  db.prepare('DELETE FROM oauth_codes WHERE exp < ?').run(now() - 3600_000);
  res.json({ redirect: redirectWith(r.redirect, { code, state: r.state, iss: config.publicUrl }) });
});

// ---------- Token endpoint ----------
function clientAuth(req) {
  let cid = req.body.client_id, secret = req.body.client_secret;
  const h = req.headers.authorization || '';
  if (h.startsWith('Basic ')) {
    const [u, p] = Buffer.from(h.slice(6), 'base64').toString().split(':');
    cid = decodeURIComponent(u || ''); secret = decodeURIComponent(p || '');
  }
  const c = getClient(cid);
  if (!c) return null;
  if (c.type === 'confidential') { if (!secret || !c.secret_hash || !bcrypt.compareSync(String(secret), c.secret_hash)) return null; }
  return c;
}
const tErr = (res, code, error, desc) => res.status(code).set('Cache-Control', 'no-store').json({ error, error_description: desc });

function mintTokens(client, user, scope, authTime, nonce, refresh) {
  const scopes = scope.split(' ');
  const at = sign({ sub: user.id, client_id: client.id, aud: config.publicUrl, scope, typ: 'at+jwt', jti: id() }, AT_TTL);
  const claims = { sub: user.id, aud: client.id, auth_time: authTime, nonce: nonce || undefined,
    at_hash: b64u(crypto.createHash('sha256').update(at).digest().subarray(0, 16)) };
  if (scopes.includes('profile')) claims.name = user.name;
  if (scopes.includes('email')) { claims.email = user.email; claims.email_verified = !!user.email_verified; }
  const out = { access_token: at, token_type: 'Bearer', expires_in: AT_TTL, scope, id_token: sign(claims, ID_TTL) };
  if (scopes.includes('offline_access')) {
    if (refresh) out.refresh_token = refresh;
    else {
      const rid = id('rt_'), secret = crypto.randomBytes(32).toString('base64url');
      db.prepare('INSERT INTO oauth_refresh (id, hash, client_id, user_id, scope, auth_time, exp, created_at) VALUES (?,?,?,?,?,?,?,?)')
        .run(rid, sha256(secret), client.id, user.id, scope, authTime, now() + RT_TTL, now());
      out.refresh_token = `${rid}.${secret}`;
    }
  }
  return out;
}

oidcRouter.options('/oauth/token', openCors);
oidcRouter.post('/oauth/token', openCors, tokenLimiter, express.urlencoded({ extended: false }), express.json(), (req, res) => {
  req.body ||= {};
  const client = clientAuth(req);
  if (!client) return tErr(res, 401, 'invalid_client', 'Client authentication failed');
  const g = req.body.grant_type;
  if (g === 'authorization_code') {
    const row = db.prepare('SELECT * FROM oauth_codes WHERE hash=?').get(sha256(String(req.body.code || '')));
    if (!row || row.client_id !== client.id || row.exp < now()) return tErr(res, 400, 'invalid_grant', 'Code is invalid or expired');
    if (row.used) {   // replay: revoke everything issued from this grant
      db.prepare('UPDATE oauth_refresh SET revoked=1 WHERE user_id=? AND client_id=?').run(row.user_id, row.client_id);
      return tErr(res, 400, 'invalid_grant', 'Code already used');
    }
    if (row.redirect_uri !== req.body.redirect_uri) return tErr(res, 400, 'invalid_grant', 'redirect_uri mismatch');
    if (row.challenge) {
      const v = String(req.body.code_verifier || '');
      if (!/^[A-Za-z0-9._~-]{43,128}$/.test(v) || b64u(crypto.createHash('sha256').update(v).digest()) !== row.challenge) return tErr(res, 400, 'invalid_grant', 'PKCE verification failed');
    } else if (client.type === 'public') return tErr(res, 400, 'invalid_grant', 'PKCE required');
    db.prepare('UPDATE oauth_codes SET used=1 WHERE hash=?').run(row.hash);
    const user = db.prepare('SELECT * FROM users WHERE id=?').get(row.user_id);
    if (!user) return tErr(res, 400, 'invalid_grant', 'User no longer exists');
    return res.set('Cache-Control', 'no-store').json(mintTokens(client, user, row.scope, row.auth_time, row.nonce));
  }
  if (g === 'refresh_token') {
    const [rid, secret] = String(req.body.refresh_token || '').split('.');
    const row = rid && db.prepare('SELECT * FROM oauth_refresh WHERE id=?').get(rid);
    if (!row || row.client_id !== client.id || row.revoked || row.exp < now()) return tErr(res, 400, 'invalid_grant', 'Refresh token is invalid');
    if (row.prev_hash === sha256(secret || '') ) { db.prepare('UPDATE oauth_refresh SET revoked=1 WHERE id=?').run(rid); return tErr(res, 400, 'invalid_grant', 'Refresh token reuse detected'); }
    if (row.hash !== sha256(secret || '')) return tErr(res, 400, 'invalid_grant', 'Refresh token is invalid');
    if (!db.prepare('SELECT 1 FROM oauth_consents WHERE user_id=? AND client_id=?').get(row.user_id, client.id)) return tErr(res, 400, 'invalid_grant', 'Access was removed');
    const user = db.prepare('SELECT * FROM users WHERE id=?').get(row.user_id);
    if (!user) return tErr(res, 400, 'invalid_grant', 'User no longer exists');
    const next = crypto.randomBytes(32).toString('base64url');
    db.prepare('UPDATE oauth_refresh SET prev_hash=hash, hash=? WHERE id=?').run(sha256(next), rid);
    db.prepare('UPDATE oauth_consents SET last_used_at=? WHERE user_id=? AND client_id=?').run(now(), user.id, client.id);
    return res.set('Cache-Control', 'no-store').json(mintTokens(client, user, row.scope, row.auth_time, null, `${rid}.${next}`));
  }
  return tErr(res, 400, 'unsupported_grant_type', 'grant_type must be authorization_code or refresh_token');
});

function bearerAt(req) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : (req.body?.access_token || '');
  try {
    const p = jwt.verify(t, crypto.createPublicKey(privateKey), { algorithms: ['RS256'], issuer: config.publicUrl, audience: config.publicUrl });
    if (p.typ !== 'at+jwt') return null;
    if (!db.prepare('SELECT 1 FROM oauth_consents WHERE user_id=? AND client_id=?').get(p.sub, p.client_id)) return null;
    return p;
  } catch { return null; }
}
const userinfo = (req, res) => {
  const p = bearerAt(req);
  if (!p) return res.status(401).set('WWW-Authenticate', 'Bearer error="invalid_token"').json({ error: 'invalid_token' });
  const u = db.prepare('SELECT * FROM users WHERE id=?').get(p.sub);
  if (!u) return res.status(401).json({ error: 'invalid_token' });
  const s = p.scope.split(' '); const out = { sub: u.id };
  if (s.includes('profile')) out.name = u.name;
  if (s.includes('email')) { out.email = u.email; out.email_verified = !!u.email_verified; }
  res.set('Cache-Control', 'no-store').json(out);
};
oidcRouter.options('/oauth/userinfo', openCors);
oidcRouter.get('/oauth/userinfo', openCors, userinfo);
oidcRouter.post('/oauth/userinfo', openCors, express.urlencoded({ extended: false }), userinfo);

oidcRouter.options('/oauth/revoke', openCors);
oidcRouter.post('/oauth/revoke', openCors, express.urlencoded({ extended: false }), express.json(), (req, res) => {
  req.body ||= {};
  const client = clientAuth(req);
  if (!client) return tErr(res, 401, 'invalid_client', 'Client authentication failed');
  const [rid] = String(req.body.token || '').split('.');
  if (rid?.startsWith('rt_')) db.prepare('UPDATE oauth_refresh SET revoked=1 WHERE id=? AND client_id=?').run(rid, client.id);
  res.status(200).end();   // RFC 7009: always 200
});

// ---------- Account portal: apps with access ----------
oauthApi.get('/consents', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT c.*, k.name, k.homepage, k.logo, k.redirect_uris, k.owner_id FROM oauth_consents c JOIN oauth_clients k ON k.id=c.client_id WHERE c.user_id=? ORDER BY c.last_used_at DESC').all(req.user.id);
  res.json({ apps: rows.map(r => ({ clientId: r.client_id, name: r.name, domain: host(r.homepage || JSON.parse(r.redirect_uris)[0]), logo: r.logo, scope: r.scope, createdAt: r.created_at, lastUsedAt: r.last_used_at })) });
});
oauthApi.delete('/consents/:cid', requireAuth, (req, res) => {
  db.prepare('DELETE FROM oauth_consents WHERE user_id=? AND client_id=?').run(req.user.id, req.params.cid);
  db.prepare('UPDATE oauth_refresh SET revoked=1 WHERE user_id=? AND client_id=?').run(req.user.id, req.params.cid);
  audit(req.user.id, 'oauth_revoke', { client: req.params.cid });
  res.json({ ok: true });
});

// ---------- Developer console: register your own apps ----------
const clientS = z.object({
  name: z.string().trim().min(2).max(60),
  homepage: z.string().trim().url().max(300).optional().or(z.literal('')),
  logo: z.string().trim().url().max(300).optional().or(z.literal('')),
  redirectUris: z.array(z.string().trim().max(500)).min(1).max(10),
  type: z.enum(['public', 'confidential']).default('public'),
});
oauthApi.get('/clients', requireAuth, (req, res) => {
  const rows = db.prepare('SELECT * FROM oauth_clients WHERE owner_id=? ORDER BY created_at DESC').all(req.user.id);
  res.json({ clients: rows.map(c => ({ id: c.id, name: c.name, homepage: c.homepage, logo: c.logo, type: c.type, redirectUris: JSON.parse(c.redirect_uris), createdAt: c.created_at })) });
});
oauthApi.post('/clients', requireAuth, (req, res) => {
  const p = clientS.safeParse(req.body || {});
  if (!p.success) return res.status(400).json({ error: p.error.issues[0]?.message || 'Invalid app details' });
  const bad = p.data.redirectUris.find(u => !validRedirect(u));
  if (bad) return res.status(400).json({ error: `Redirect URI must be https (or http://localhost, or an app scheme like com.example.app:/cb): ${bad}` });
  if (p.data.logo && !/^https:/.test(p.data.logo)) return res.status(400).json({ error: 'Logo must be an https URL' });
  if (db.prepare('SELECT COUNT(*) n FROM oauth_clients WHERE owner_id=?').get(req.user.id).n >= 20) return res.status(400).json({ error: 'App limit reached (20)' });
  const cid = 'cairn_' + crypto.randomBytes(12).toString('base64url');
  const secret = p.data.type === 'confidential' ? 'cs_' + crypto.randomBytes(32).toString('base64url') : null;
  db.prepare('INSERT INTO oauth_clients (id, secret_hash, name, homepage, logo, redirect_uris, owner_id, type, created_at) VALUES (?,?,?,?,?,?,?,?,?)')
    .run(cid, secret ? bcrypt.hashSync(secret, 10) : null, p.data.name, p.data.homepage || '', p.data.logo || '', JSON.stringify(p.data.redirectUris), req.user.id, p.data.type, now());
  audit(req.user.id, 'oauth_client_create', { client: cid });
  res.json({ clientId: cid, clientSecret: secret, issuer: config.publicUrl });
});
oauthApi.delete('/clients/:cid', requireAuth, (req, res) => {
  const c = db.prepare('SELECT owner_id FROM oauth_clients WHERE id=?').get(req.params.cid);
  if (!c || c.owner_id !== req.user.id) return res.status(404).json({ error: 'Not found' });
  for (const t of ['oauth_codes', 'oauth_refresh', 'oauth_consents']) db.prepare(`DELETE FROM ${t} WHERE client_id=?`).run(req.params.cid);
  db.prepare('DELETE FROM oauth_clients WHERE id=?').run(req.params.cid);
  res.json({ ok: true });
});

// Public metadata used by the browsers' one-tap sheet and the portal.
oauthApi.get('/issuer', (_req, res) => res.json({ issuer: config.publicUrl }));
export const oidcIssuer = () => config.publicUrl;
