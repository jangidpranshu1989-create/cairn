// LLM access for the desktop app. Zero setup and no keys on the device:
//  1. Cairn server AI: your account quota when signed in, otherwise an anonymous per-install token. The server
//     holds the provider keys (Groq → NVIDIA NIM → keyless fallback).
//  2. If the server can't be reached, a keyless OpenAI-compatible endpoint called directly from this computer.
const store = require('./store');
const cloud = require('./cloud');

// Keyless OpenAI-compatible fallback (tools supported). text.pollinations.ai was retired (402/404) and is not used.
const FREE_URL = 'https://api.llm7.io/v1/chat/completions';
const base = () => store.get().settings.data.serverUrl.replace(/\/$/, '');
const timeout = (signal, ms) => (signal ? AbortSignal.any([signal, AbortSignal.timeout(ms)]) : AbortSignal.timeout(ms));

async function anonToken(force) {
  const v = store.get().vault;
  if (v.data.anonAiToken && !force) return v.data.anonAiToken;
  const r = await fetch(base() + '/api/ai/device', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ platform: 'desktop' }), signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw Object.assign(new Error('Cairn AI is unreachable'), { status: r.status });
  v.data.anonAiToken = (await r.json()).token; v.flush();
  return v.data.anonAiToken;
}

/** POST to the server AI with account or anonymous credentials. Returns the raw Response. */
async function serverFetch(path, { json, form, accept, signal } = {}) {
  const send = async (auth) => fetch(base() + '/api' + path, {
    method: 'POST', signal: timeout(signal, 120_000),
    headers: { Authorization: 'Bearer ' + auth, ...(json ? { 'Content-Type': 'application/json' } : {}), ...(accept ? { Accept: accept } : {}) },
    body: form || JSON.stringify(json),
  });
  if (cloud.isSignedIn()) {
    let r = await send(store.get().vault.data.cloud.accessToken);
    if (r.status === 401) { try { await cloud.api('/auth/me'); r = await send(store.get().vault.data.cloud.accessToken); } catch {} }
    if (r.status !== 401) return r;
  }
  let r = await send(await anonToken());
  if (r.status === 401) r = await send(await anonToken(true));
  return r;
}
async function okJson(r) {
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error || `AI error ${r.status}`), { status: r.status });
  return j;
}

function status() { return { ready: true, mode: cloud.isSignedIn() ? 'account' : 'builtin', providers: ['cairn'] }; }

async function freeChat(opts) {
  const body = { model: 'default', messages: opts.messages, temperature: opts.temperature ?? 0.2, max_tokens: opts.maxTokens ?? 4096 };
  if (opts.tools?.length) { body.tools = opts.tools; body.tool_choice = opts.toolChoice || 'auto'; }
  const r = await fetch(FREE_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: timeout(opts.signal, 120_000) });
  const j = await okJson(r);
  return { message: j.choices[0].message, provider: 'free', usage: j.usage };
}

/**
 * chat({ messages, tools, tier:'smart'|'fast'|'vision', json, temperature, maxTokens, signal })
 * → { message, provider }
 */
async function chat(opts) {
  const { signal, ...rest } = opts;
  let cloudErr;
  // Cairn Cloud, retried once after a short pause when it is busy (503), cold, redeploying (404/502) or timed out.
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const j = await okJson(await serverFetch('/ai/chat', { json: { ...rest, stream: false }, signal }));
      return { message: j.choices[0].message, provider: j._provider || 'cairn', usage: j.usage };
    } catch (e) {
      if (signal?.aborted) throw e;
      cloudErr = e;
      const transient = !e.status || e.status === 404 || e.status === 408 || e.status >= 500;
      if (attempt === 0 && transient) { await new Promise(r => setTimeout(r, 1500)); continue; }
      break;
    }
  }
  if (opts.tier === 'vision' || (cloudErr.status === 429 && /daily/i.test(cloudErr.message))) throw cloudErr;
  let freeErr;
  try { return await freeChat(opts); }
  catch (e) { if (signal?.aborted) throw e; freeErr = e; }
  // Both failed. Cairn Cloud on Render's free tier answers 404 from its edge (x-render-routing: no-server) for the
  // 30-60 s it takes to wake up, so wait for it patiently (~40 s) instead of failing after 2 s.
  const transientCloud = (e) => !e.status || [404, 408, 425, 502, 503, 504].includes(e.status) || (e.status === 429 && !/daily|limit/i.test(e.message));
  if (transientCloud(cloudErr) && Date.now() - downAt > 30_000) {
    for (const wait of [2000, 3000, 5000, 7000, 10000, 12000]) {
      await new Promise(r => setTimeout(r, wait));
      if (signal?.aborted) throw new Error('aborted');
      try {
        const j = await okJson(await serverFetch('/ai/chat', { json: { ...rest, stream: false }, signal }));
        downAt = 0;
        return { message: j.choices[0].message, provider: j._provider || 'cairn', usage: j.usage };
      } catch (e) { if (signal?.aborted) throw e; cloudErr = e; if (!transientCloud(e)) break; }
    }
  }
  downAt = Date.now();
  void freeErr;
  throw cloudErr.status && cloudErr.status !== 503 && cloudErr.status !== 404 ? cloudErr : new Error('NO_AI');
}
let downAt = 0;

/** Wakes Cairn Cloud in the background (the free tier sleeps after 15 idle minutes). */
let lastWarm = 0;
function warm() {
  if (Date.now() - lastWarm < 60_000) return;
  lastWarm = Date.now();
  fetch(base() + '/api/health', { signal: AbortSignal.timeout(70_000) }).catch(() => {});
}

async function readSse(r, onDelta) {
  const dec = new TextDecoder(); let buf = '', out = '';
  for await (const chunk of r.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line.startsWith('data:')) continue;
      const d = line.slice(5).trim(); if (d === '[DONE]') continue;
      try { const t = JSON.parse(d).choices?.[0]?.delta?.content; if (t) { out += t; onDelta(t); } } catch {}
    }
  }
  return out;
}

/** Streams a plain-text reply; calls onDelta(text) per chunk. Falls back to one chunk. */
async function stream(messages, onDelta, opts = {}) {
  let partial = '';
  try {
    const r = await serverFetch('/ai/chat', { json: { messages, tier: opts.tier || 'smart', maxTokens: opts.maxTokens ?? 1000, temperature: 0.3, stream: true }, accept: 'text/event-stream' });
    if (r.ok) { const out = await readSse(r, (t) => { partial += t; onDelta(t); }); if (out) return out; }
  } catch { if (partial) return partial; }
  const { message } = await chat({ messages, ...opts });
  onDelta(message.content || ''); return message.content || '';
}

/** Plain text helper */
async function complete(system, user, opts = {}) {
  const { message } = await chat({ messages: [{ role: 'system', content: system }, { role: 'user', content: user }], ...opts });
  return (message.content || '').trim();
}

/** JSON helper with tolerant parsing */
async function completeJSON(system, user, opts = {}) {
  const text = await complete(system + '\nRespond with a single valid JSON object only.', user, { ...opts, json: true });
  try { return JSON.parse(text); }
  catch {
    const m = text.match(/\{[\s\S]*\}/);
    if (m) return JSON.parse(m[0]);
    throw new Error('Model returned invalid JSON');
  }
}

async function transcribe(buffer, mime, language) {
  const fd = new FormData();
  fd.append('audio', new Blob([buffer], { type: mime }), 'voice.webm');
  if (language) fd.append('language', language);
  return (await okJson(await serverFetch('/ai/stt', { form: fd }))).text;
}

// Cloud TTS isn't offered; Cairn reads aloud with the system's voices.
async function speech() { throw new Error('No voice installed for this language. Install a system voice for it in your OS settings.'); }

module.exports = { chat, stream, complete, completeJSON, transcribe, speech, status, warm };
