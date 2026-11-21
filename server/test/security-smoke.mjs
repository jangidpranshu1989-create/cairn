// End-to-end checks for the 3.4 security fixes. Run against a dev server started with CAPTCHA_DEV_ANSWER=1.
//  - sync uploads over 4 MB are accepted (the global JSON limit no longer pre-empts the sync routes)
//  - QR pairing needs the 256-bit secret; a wrong secret fails
//  - payment approvals from a phone with a Keystore key need a valid ECDSA signature (the old boolean is not enough)
//  - a device key can be registered once and never swapped
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const B = process.env.BASE || 'http://localhost:8787';
const j = async (p, o = {}) => { const r = await fetch(B + p, { ...o, headers: { 'Content-Type': 'application/json', ...(o.headers || {}) }, body: o.body && typeof o.body !== 'string' ? JSON.stringify(o.body) : o.body }); const t = await r.text(); let d; try { d = JSON.parse(t); } catch { d = t; } return { s: r.status, d }; };

const cap = (await j('/api/auth/captcha')).d;
const su = await j('/api/auth/signup', { method: 'POST', body: { email: `sec${Date.now()}@example.com`, password: 'Secure-Passw0rd-123', name: 'Sec', captchaToken: cap.token, captchaAnswer: cap.devAnswer } });
assert.equal(su.s, 200, JSON.stringify(su.d));
const A = { Authorization: 'Bearer ' + su.d.accessToken };

// 1) Big sync push (5 MB) is accepted
const big = 'x'.repeat(5 * 1024 * 1024);
const put = await j('/api/sync/bookmarks', { method: 'PUT', headers: A, body: { data: big } });
assert.notEqual(put.s, 413, 'sync PUT over 4 MB must not be rejected by the global JSON limit');
const ch = await j('/api/sync/v2/changes', { method: 'POST', headers: A, body: { items: [{ id: 'a'.repeat(32), kind: 'history', blob: big, deleted: false }] } });
assert.notEqual(ch.s, 413, 'sync v2 changes over 4 MB must not be rejected by the global JSON limit');
// Small bodies on other routes still parse normally
assert.equal((await j('/api/devices/me', { method: 'PATCH', headers: A, body: { name: 'x' } })).s, 400); // not a device session (body parsed)

// 2) QR pairing with secret
const kp = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const pub = kp.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
const st = await j('/api/devices/pair/start', { method: 'POST', headers: A });
assert.equal(st.s, 200);
const url = new URL(st.d.payload.replace('cairn://', 'https://x/'));
const secret = url.searchParams.get('s');
assert.ok(secret && secret.length >= 40, 'QR carries a long secret');
assert.equal((await j('/api/devices/pair/complete', { method: 'POST', body: { code: st.d.code, secret: secret.slice(0, -2) + 'AA', name: 'Evil' } })).s, 400, 'wrong secret rejected');
const pc = await j('/api/devices/pair/complete', { method: 'POST', body: { code: st.d.code, secret, name: 'CI phone', approvalKey: pub } });
assert.equal(pc.s, 200, JSON.stringify(pc.d));
const P = { Authorization: 'Bearer ' + pc.d.accessToken };

// 3) Signed payment approval
const mk = async () => (await j('/api/approvals', { method: 'POST', headers: A, body: { kind: 'payment', title: 'Pay ₹1', details: { amount: '₹1', merchant: 'CI' } } })).d.approval.id;
const a1 = await mk();
assert.equal((await j(`/api/approvals/${a1}/decision`, { method: 'POST', headers: P, body: { approve: true, verified: true } })).s, 403, 'boolean alone is not enough for a keyed phone');
const badSig = crypto.sign('sha256', Buffer.from(`cairn-approval-v1:${a1}x:approve`), kp.privateKey).toString('base64');
assert.equal((await j(`/api/approvals/${a1}/decision`, { method: 'POST', headers: P, body: { approve: true, verified: true, signature: badSig } })).s, 403, 'signature for another id rejected');
const sig = crypto.sign('sha256', Buffer.from(`cairn-approval-v1:${a1}:approve`), kp.privateKey).toString('base64');
const ok = await j(`/api/approvals/${a1}/decision`, { method: 'POST', headers: P, body: { approve: true, verified: true, signature: sig } });
assert.equal(ok.s, 200, JSON.stringify(ok.d)); assert.equal(ok.d.status, 'approved');
// Deny never needs a signature
const a2 = await mk();
assert.equal((await j(`/api/approvals/${a2}/decision`, { method: 'POST', headers: P, body: { approve: false } })).s, 200);
// Desktop session can't approve payments
const a3 = await mk();
assert.equal((await j(`/api/approvals/${a3}/decision`, { method: 'POST', headers: A, body: { approve: true, verified: true } })).s, 403);

// 4) Device key can't be swapped
const other = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
assert.equal((await j('/api/devices/me', { method: 'PATCH', headers: P, body: { approvalKey: other } })).s, 409);
assert.equal((await j('/api/devices/me', { method: 'PATCH', headers: P, body: { approvalKey: pub } })).s, 200);
assert.equal((await j('/api/devices/me', { method: 'PATCH', headers: P, body: { approvalKey: 'not-a-key' } })).s, 400);

// 5) Manual 6-digit codes stop working after too many wrong guesses server-wide (QR keeps working)
const st2 = await j('/api/devices/pair/start', { method: 'POST', headers: A });
let locked = false;
for (let i = 0; i < 40 && !locked; i++) {
  const guess = String((Number(st2.d.code) + 1 + i) % 1_000_000).padStart(6, '0');
  const r = await j('/api/devices/pair/complete', { method: 'POST', headers: { 'X-Forwarded-For': `10.9.${i}.1` }, body: { code: guess } });
  if (r.s === 429) locked = true;
}
const manual = await j('/api/devices/pair/complete', { method: 'POST', body: { code: st2.d.code } });
assert.ok(manual.s === 429, 'manual code is blocked after the wrong-guess budget: ' + manual.s);
const s2 = new URL(st2.d.payload.replace('cairn://', 'https://x/')).searchParams.get('s');
assert.equal((await j('/api/devices/pair/complete', { method: 'POST', body: { code: st2.d.code, secret: s2 } })).s, 200, 'QR still pairs');
console.log('Security smoke test passed');
