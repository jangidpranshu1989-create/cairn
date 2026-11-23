// End-to-end checks for the 3.4.1 fixes. Run against a dev server started with CAPTCHA_DEV_ANSWER=1.
//  - login answers the same for unknown emails (captcha after 3 tries), so it can't reveal who has an account
//  - WebSockets accept one-time tickets only (no access token in the URL) and drop oversized messages
//  - each account can only park a limited number of files for the phone
import assert from 'node:assert/strict';
import WebSocket from 'ws';
const B = process.env.BASE || 'http://localhost:8787';
const j = async (p, o = {}) => { const r = await fetch(B + p, { ...o, headers: { 'Content-Type': 'application/json', ...(o.headers || {}) }, body: o.body && typeof o.body !== 'string' ? JSON.stringify(o.body) : o.body }); const t = await r.text(); let d; try { d = JSON.parse(t); } catch { d = t; } return { s: r.status, d }; };

// 1) Login: real and unknown emails look the same
const cap = (await j('/api/auth/captcha')).d;
const email = `h${Date.now()}@example.com`;
const su = await j('/api/auth/signup', { method: 'POST', body: { email, password: 'Secure-Passw0rd-123', name: 'H', captchaToken: cap.token, captchaAnswer: cap.devAnswer } });
assert.equal(su.s, 200, JSON.stringify(su.d));
const A = { Authorization: 'Bearer ' + su.d.accessToken };
const tries = async (e) => { const out = []; for (let i = 0; i < 4; i++) { const r = await j('/api/auth/login', { method: 'POST', body: { email: e, password: 'wrong-password-1' } }); out.push(`${r.s}:${!!r.d.captcha}`); } return out.join(','); };
const real = await tries(email); const ghost = await tries(`nobody${Date.now()}@example.com`);
assert.equal(ghost, real, `unknown email must answer like a real one (real=${real} ghost=${ghost})`);

// 2) WebSocket: token in URL refused, ticket accepted, oversized message closes the socket
const wsBase = B.replace(/^http/, 'ws') + '/ws';
const closeCode = (url, send) => new Promise((res) => { const w = new WebSocket(url); w.on('open', () => { if (send) w.send(send); }); w.on('close', (code) => res(code)); w.on('error', () => {}); setTimeout(() => { try { w.terminate(); } catch {} res('timeout'); }, 4000); });
assert.equal(await closeCode(`${wsBase}?token=${su.d.accessToken}`), 4401, 'access token in URL must be refused');
const t1 = (await j('/api/auth/ws-ticket', { method: 'POST', headers: A })).d.ticket;
assert.equal(await closeCode(`${wsBase}?ticket=${t1}`, 'x'.repeat(10_000)), 1009, 'oversized message must close the socket (1009)');
const t2 = (await j('/api/auth/ws-ticket', { method: 'POST', headers: A })).d.ticket;
const hello = await new Promise((res) => { const w = new WebSocket(`${wsBase}?ticket=${t2}`); w.on('message', (d) => { res(JSON.parse(String(d)).type); w.close(); }); w.on('close', (code) => res('closed ' + code)); w.on('error', () => {}); });
assert.equal(hello, 'hello', 'ticket login works');

// 3) Transfers: per-account cap on files waiting for the phone
let last;
for (let i = 0; i < 11; i++) {
  const fd = new FormData(); fd.append('file', new Blob(['hi ' + i], { type: 'text/plain' }), `f${i}.txt`);
  last = await fetch(B + '/api/transfers', { method: 'POST', headers: A, body: fd });
  if (i < 10) assert.equal(last.status, 200, `upload ${i} should work`);
}
assert.equal(last.status, 429, '11th pending file must be refused');
console.log('Hardening smoke test passed');
