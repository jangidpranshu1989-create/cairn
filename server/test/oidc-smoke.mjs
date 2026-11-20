// End-to-end check of the Cairn OIDC provider. Run against a dev server started with CAPTCHA_DEV_ANSWER=1.
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const B = process.env.BASE || 'http://localhost:8787';
const j = async (p, o = {}) => { const r = await fetch(B + p, { ...o, redirect: 'manual', headers: { 'Content-Type': 'application/json', ...(o.headers || {}) }, body: o.body && typeof o.body !== 'string' ? JSON.stringify(o.body) : o.body }); const t = await r.text(); let d; try { d = JSON.parse(t); } catch { d = t; } return { s: r.status, d, h: r.headers }; };
const form = (o) => ({ method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(o).toString() });

const cap = (await j('/api/auth/captcha')).d;
const pw = 'पासवर्ड-Pässwörd-123';   // Unicode password
const su = await j('/api/auth/signup', { method: 'POST', body: { email: `ci${Date.now()}@example.com`, password: pw, name: 'CI', captchaToken: cap.token, captchaAnswer: cap.devAnswer } });
assert.equal(su.s, 200, JSON.stringify(su.d));
const A = { Authorization: 'Bearer ' + su.d.accessToken };
const cl = await j('/api/oauth/clients', { method: 'POST', headers: A, body: { name: 'CI App', redirectUris: ['https://ci.example.com/cb'] } });
assert.equal(cl.s, 200);
const v = crypto.randomBytes(40).toString('base64url'), ch = crypto.createHash('sha256').update(v).digest('base64url');
const q = { response_type: 'code', client_id: cl.d.clientId, redirect_uri: 'https://ci.example.com/cb', scope: 'openid profile email offline_access', state: 's', nonce: 'n', code_challenge: ch, code_challenge_method: 'S256' };
const az = await j('/oauth/authorize?' + new URLSearchParams(q));
assert.equal(az.s, 302); assert.match(az.h.get('location'), /\/accounts\/consent\.html\?/);
assert.match((await j('/oauth/authorize?' + new URLSearchParams({ ...q, redirect_uri: 'https://evil.example/cb' }))).h.get('location'), /error=/);
assert.equal((await j('/api/oauth/request?' + new URLSearchParams({ ...q, code_challenge: '' }))).s, 400);
const ap = await j('/api/oauth/approve', { method: 'POST', headers: A, body: { ...q, approve: true } });
const code = new URL(ap.d.redirect).searchParams.get('code');
assert.equal((await j('/oauth/token', form({ grant_type: 'authorization_code', code, redirect_uri: q.redirect_uri, client_id: q.client_id, code_verifier: v + 'x' }))).s, 400);
const ap2 = await j('/api/oauth/approve', { method: 'POST', headers: A, body: { ...q, approve: true } });
const code2 = new URL(ap2.d.redirect).searchParams.get('code');
const tk = await j('/oauth/token', form({ grant_type: 'authorization_code', code: code2, redirect_uri: q.redirect_uri, client_id: q.client_id, code_verifier: v }));
assert.equal(tk.s, 200, JSON.stringify(tk.d));
const jw = (await j('/oauth/jwks')).d.keys[0];
const [h, p, sig] = tk.d.id_token.split('.');
assert.ok(crypto.verify('RSA-SHA256', Buffer.from(h + '.' + p), crypto.createPublicKey({ key: jw, format: 'jwk' }), Buffer.from(sig, 'base64url')));
const claims = JSON.parse(Buffer.from(p, 'base64url'));
assert.equal(claims.aud, q.client_id); assert.equal(claims.nonce, 'n');
const ui = await j('/oauth/userinfo', { headers: { Authorization: 'Bearer ' + tk.d.access_token } });
assert.equal(ui.d.sub, claims.sub);
const rf = await j('/oauth/token', form({ grant_type: 'refresh_token', refresh_token: tk.d.refresh_token, client_id: q.client_id }));
assert.equal(rf.s, 200);
assert.equal((await j('/oauth/token', form({ grant_type: 'refresh_token', refresh_token: tk.d.refresh_token, client_id: q.client_id }))).s, 400);   // reuse
assert.equal((await j('/oauth/token', form({ grant_type: 'refresh_token', refresh_token: rf.d.refresh_token, client_id: q.client_id }))).s, 400); // family revoked
assert.equal((await j('/api/auth/login', { method: 'POST', body: { email: su.d.user.email, password: pw } })).s, 200);
console.log('OIDC smoke test passed');
