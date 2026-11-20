// Unit tests for the AI provider chain (no network: fetch is mocked).
import test from 'node:test';
import assert from 'node:assert/strict';
import { chat, providerOrder, estimateTokens, AllProvidersFailed } from '../src/providers.js';

const cfg = { groqKey: 'g', nvidiaKey: 'n', geminiKey: '', openrouterKey: '', freeFallback: true, groqModel: 'gpt-oss-120b', groqFastModel: 'gpt-oss-20b', nvidiaModel: 'nem', nvidiaFastModel: 'nemf' };
const ok = (content) => new Response(JSON.stringify({ choices: [{ message: { role: 'assistant', content } }] }), { status: 200 });
const fail = (s) => new Response('{"error":"x"}', { status: s });
function mock(seq) { const calls = []; globalThis.fetch = async (url) => { calls.push(String(url)); const f = seq.shift(); return typeof f === 'function' ? f(url) : f; }; return calls; }
const msgs = [{ role: 'user', content: 'hi' }];

test('retired Pollinations is no longer in the chain', () => {
  assert.deepEqual(providerOrder(cfg, 'smart'), ['groq', 'nvidia', 'free']);
  assert.ok(!providerOrder(cfg, 'smart').includes('legacy'));
});

test('falls through providers and returns the first good reply', async () => {
  const calls = mock([fail(500), ok('hello')]);
  const j = await chat({ messages: msgs }, cfg);
  assert.equal(j._provider, 'nvidia'); assert.equal(j.choices[0].message.content, 'hello');
  assert.match(calls[0], /groq/); assert.match(calls[1], /nvidia/);
});

test('every provider failing gives 503 AllProvidersFailed, never an upstream 404/402', async () => {
  mock(Array(20).fill(0).map(() => fail(404)));
  await assert.rejects(chat({ messages: msgs, _single: true }, cfg), (e) => e instanceof AllProvidersFailed && e.status === 503 && e.attempts.length === 3);
});

test('second pass rescues a momentary rate limit', async () => {
  mock([fail(503), fail(429), fail(402), ok('back')]);
  const j = await chat({ messages: msgs }, { ...cfg, groqKey: '' });
  assert.equal(j.choices[0].message.content, 'back');
});

test('large agent steps skip Groq free tier and go to NVIDIA', async () => {
  const big = [{ role: 'system', content: 'x'.repeat(40000) }];
  assert.ok(estimateTokens(big) > 7000);
  const calls = mock([ok('nim')]);
  const j = await chat({ messages: big }, cfg);
  assert.equal(j._provider, 'nvidia'); assert.equal(calls.length, 1);
});

test('empty replies are treated as failures', async () => {
  mock([ok(''), ok('real')]);
  const j = await chat({ messages: msgs }, cfg);
  assert.equal(j._provider, 'nvidia');
});
