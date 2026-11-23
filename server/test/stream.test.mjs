// A streamed AI answer that breaks half-way (upstream drop or the 60 s timeout) must end that one response, not crash
// the server. Before 3.4.1 `.pipe()` left the stream error unhandled and Node exited.
import test from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'cairn-stream-'));
process.env.SAFE_BROWSING_FEEDS = '0';
const { default: express } = await import('express');
const { aiRouter } = await import('../src/ai.js');

test('a broken upstream stream ends the response and the process keeps running', { timeout: 15_000 }, async () => {
  const app = express(); app.use(express.json()); app.use('/api/ai', aiRouter);
  const srv = app.listen(0); const base = `http://127.0.0.1:${srv.address().port}`;
  const realFetch = globalThis.fetch;
  try {
    const { token } = await (await realFetch(base + '/api/ai/device', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).json();
    globalThis.fetch = async (url, o) => {
      if (String(url).startsWith(base)) return realFetch(url, o);
      const body = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data: {"x":1}\n\n')); setTimeout(() => c.error(new DOMException('The operation timed out', 'TimeoutError')), 50); } });
      return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
    };
    let crashed = null; const onErr = (e) => { crashed = e; }; process.on('uncaughtException', onErr);
    const r = await realFetch(base + '/api/ai/chat', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({ messages: [{ role: 'user', content: 'hi' }], stream: true }) });
    assert.equal(r.status, 200);
    await Promise.race([r.text().catch(() => {}), new Promise((_, rej) => setTimeout(() => rej(new Error('response never ended')), 5000))]); // cut short is expected; hanging is not
    await new Promise((res) => setTimeout(res, 200));
    process.off('uncaughtException', onErr);
    assert.equal(crashed, null, 'stream error must not escape as an uncaught exception');
    const h = await realFetch(base + '/api/ai/status');
    assert.equal(h.status, 200, 'server still answers after the broken stream');
  } finally { globalThis.fetch = realFetch; srv.close(); }
});
