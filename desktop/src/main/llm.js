// LLM access for the desktop app. Zero setup and no keys on the device:
//  1. Cairn server AI: your account quota when signed in, otherwise an anonymous per-install token. The server
//     holds the provider keys (Groq → NVIDIA NIM → keyless fallback).
//  2. If the server can't be reached, a keyless OpenAI-compatible endpoint called directly from this computer.
const store = require('./store');
const cloud = require('./cloud');

// Keyless OpenAI-compatible fallbacks (tools supported), tried in order when Cairn Cloud can't be reached. Each has
// its own per-IP limit, so one being busy moves on to the next. LLM7 needs a token since Nov 2026 and is not used.
const FREE = [
  { name: 'Kilo', url: 'https://ai-gateway.kilo.ai/api/v1/chat/completions', models: { smart: ['nvidia/nemotron-3.5-lightning:free', 'nvidia/nemotron-3.5-super-vl:free'], fast: ['nvidia/nemotron-3.5-lightning:free'], vision: ['nvidia/nemotron-3.5-super-vl:free'] } },
  { name: 'OVHcloud', url: 'https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions', json: true, models: { smart: ['gpt-oss-120b', 'Qwen3.8-27B'], fast: ['gpt-oss-20b', 'Qwen3.8-27B'], vision: ['qwen3-vl-30b-a3b'] } },
  { name: 'Pollinations', url: 'https://text.pollinations.ai/openai', models: { smart: ['openai'], fast: ['openai'] } },
];
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
  const tier = opts.tier || 'smart';
  let last;
  for (const p of FREE) {
    for (const model of p.models[tier] || []) {
      const body = { model, messages: opts.messages, temperature: opts.temperature ?? 0.2, max_tokens: opts.maxTokens ?? 4096 };
      if (opts.tools?.length) { body.tools = opts.tools; body.tool_choice = opts.toolChoice || 'auto'; }
      if (opts.json && p.json) body.response_format = { type: 'json_object' };
      try {
        const j = await okJson(await fetch(p.url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: timeout(opts.signal, 90_000) }));
        const m = j.choices?.[0]?.message;
        if (!m || (!m.content && !m.tool_calls?.length)) throw Object.assign(new Error('empty reply'), { status: 502 });
        if (Array.isArray(m.tool_calls) && !m.tool_calls.length) delete m.tool_calls;
        delete m.reasoning; delete m.reasoning_content;
        return { message: m, provider: `${p.name} · ${model}`, usage: j.usage };
      } catch (e) { if (opts.signal?.aborted) throw e; last = e; if (e.cause?.code === 'ENOTFOUND') break; }
    }
  }
  throw last || new Error('NO_AI');
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
  if (cloudErr.status === 429 && /daily/i.test(cloudErr.message)) throw cloudErr;
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

/** Streams a chat turn that may call tools. onText(full) gets the visible text so far. Returns { message, provider }. */
async function chatStream(opts, onText) {
  const { signal, ...rest } = opts;
  let content = '', streamed = false;
  try {
    const r = await serverFetch('/ai/chat', { json: { ...rest, stream: true }, accept: 'text/event-stream', signal });
    if (r.ok && /event-stream/.test(r.headers.get('content-type') || '')) {
      const calls = []; const dec = new TextDecoder(); let buf = '', provider = r.headers.get('x-cairn-provider') || 'cairn';
      for await (const chunk of r.body) {
        buf += dec.decode(chunk, { stream: true });
        let i;
        while ((i = buf.indexOf('\n')) >= 0) {
          const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
          if (!line.startsWith('data:')) continue;
          const d = line.slice(5).trim(); if (!d || d === '[DONE]') continue;
          let j; try { j = JSON.parse(d); } catch { continue; }
          if (j._provider) provider = j._provider;
          const delta = j.choices?.[0]?.delta || {};
          if (delta.content) { content += delta.content; streamed = true; onText(content); }
          for (const tc of delta.tool_calls || []) {
            const k = tc.index ?? calls.length;
            const c = calls[k] || (calls[k] = { id: '', type: 'function', function: { name: '', arguments: '' } });
            if (tc.id) c.id = tc.id;
            if (tc.function?.name) c.function.name += tc.function.name;
            if (tc.function?.arguments) c.function.arguments += tc.function.arguments;
          }
        }
      }
      const tool_calls = calls.filter(c => c && c.function.name).map((c, n) => ({ ...c, id: c.id || 'call_' + n + '_' + Date.now().toString(36) }));
      if (content || tool_calls.length) return { message: { role: 'assistant', content, ...(tool_calls.length ? { tool_calls } : {}) }, provider };
    }
  } catch (e) { if (signal?.aborted) throw e; if (streamed) return { message: { role: 'assistant', content }, provider: 'cairn' }; }
  const out = await chat(opts);
  if (out.message?.content) onText(out.message.content);
  return out;
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

module.exports = { chat, chatStream, stream, complete, completeJSON, transcribe, speech, status, warm };
