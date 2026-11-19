// LLM access for the desktop app.
// Mode "byok": call Groq / NVIDIA NIM directly with the user's keys (stored encrypted locally).
// Mode "cloud": call the Saarthi server proxy (keys stay server-side, quotas per account).
const store = require('./store');
const cloud = require('./cloud');

const PROVIDERS = {
  groq: { base: 'https://api.groq.com/openai/v1', label: 'Groq' },
  nvidia: { base: 'https://integrate.api.nvidia.com/v1', label: 'NVIDIA NIM' },
};

function cfg() {
  const s = store.get().settings.data;
  const v = store.get().vault.data;
  return {
    groqKey: v.groqKey || s.groqKey || process.env.GROQ_API_KEY || '',
    nvidiaKey: v.nvidiaKey || s.nvidiaKey || process.env.NVIDIA_API_KEY || '',
    groqModel: s.groqModel, groqFastModel: s.groqFastModel,
    nvidiaModel: s.nvidiaModel, nvidiaFastModel: s.nvidiaFastModel,
    preferred: s.preferredProvider, mode: s.aiMode,
  };
}

function order(c, tier) {
  let o = tier === 'vision' ? ['nvidia', 'groq'] : ['groq', 'nvidia'];
  if (c.preferred && tier !== 'vision') o = [c.preferred, ...o.filter(p => p !== c.preferred)];
  return o.filter(p => (p === 'groq' ? c.groqKey : c.nvidiaKey));
}
const modelFor = (p, tier, c) => p === 'groq' ? (tier === 'fast' ? c.groqFastModel : c.groqModel) : (tier === 'fast' ? c.nvidiaFastModel : c.nvidiaModel);

function status() {
  const c = cfg();
  if (c.mode === 'cloud') return { ready: cloud.isSignedIn(), mode: 'cloud', providers: [] };
  const providers = order(c, 'smart');
  return { ready: providers.length > 0, mode: 'byok', providers };
}

/**
 * chat({ messages, tools, tier:'smart'|'fast'|'vision', json, temperature, maxTokens, signal })
 * → { message, provider }
 */
async function chat(opts) {
  const c = cfg();
  if (c.mode === 'cloud') {
    const j = await cloud.api('/ai/chat', { method: 'POST', body: { ...opts, signal: undefined, stream: false } , signal: opts.signal });
    return { message: j.choices[0].message, provider: j._provider, usage: j.usage };
  }
  const providers = order(c, opts.tier || 'smart');
  if (!providers.length) throw new Error('NO_AI_KEY');
  let last;
  for (const p of providers) {
    const body = {
      model: modelFor(p, opts.tier === 'vision' ? 'smart' : opts.tier, c),
      messages: opts.messages,
      temperature: opts.temperature ?? 0.2,
      max_tokens: opts.maxTokens ?? 4096,
    };
    if (opts.tools?.length) { body.tools = opts.tools; body.tool_choice = opts.toolChoice || 'auto'; }
    if (opts.json) body.response_format = { type: 'json_object' };
    if (p === 'nvidia') body.chat_template_kwargs = { enable_thinking: false };
    if (p === 'groq' && /gpt-oss/.test(body.model)) body.reasoning_effort = opts.reasoning || 'low';
    try {
      const r = await fetch(`${PROVIDERS[p].base}/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${p === 'groq' ? c.groqKey : c.nvidiaKey}` },
        body: JSON.stringify(body),
        signal: opts.signal ? AbortSignal.any([opts.signal, AbortSignal.timeout(120_000)]) : AbortSignal.timeout(120_000),
      });
      if (!r.ok) {
        const t = await r.text();
        last = new Error(`${PROVIDERS[p].label} ${r.status}: ${t.slice(0, 240)}`);
        // Groq returns 400 tool_use_failed when the model emits a malformed call — let the other provider try.
        if (r.status === 401) last.message = `${PROVIDERS[p].label} API key is invalid (401). Update it in Settings → AI.`;
        continue;
      }
      const j = await r.json();
      return { message: j.choices[0].message, provider: p, usage: j.usage };
    } catch (e) {
      if (opts.signal?.aborted) throw e;
      last = e;
    }
  }
  throw last;
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
  const c = cfg();
  if (c.mode === 'cloud') {
    const fd = new FormData();
    fd.append('audio', new Blob([buffer], { type: mime }), 'voice.webm');
    if (language) fd.append('language', language);
    return (await cloud.api('/ai/stt', { method: 'POST', form: fd })).text;
  }
  if (!c.groqKey) throw new Error('Voice input needs a Groq API key (Settings → AI).');
  const fd = new FormData();
  fd.append('file', new Blob([buffer], { type: mime }), 'voice.webm');
  fd.append('model', 'whisper-large-v3-turbo');
  if (language) fd.append('language', language);
  const r = await fetch(`${PROVIDERS.groq.base}/audio/transcriptions`, { method: 'POST', headers: { Authorization: `Bearer ${c.groqKey}` }, body: fd });
  if (!r.ok) throw new Error(`Transcription failed (${r.status})`);
  return (await r.json()).text;
}

// Cloud TTS fallback (Groq Orpheus, English) for systems without a matching OS voice.
async function speech(text, voice = 'hannah') {
  const c = cfg();
  if (!c.groqKey) throw new Error('No voice installed for this language. Add a Groq key (Settings → AI) or install system voices.');
  const r = await fetch(`${PROVIDERS.groq.base}/audio/speech`, { method: 'POST', headers: { Authorization: `Bearer ${c.groqKey}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'canopylabs/orpheus-v1-english', voice, input: String(text).slice(0, 1200), response_format: 'wav' }), signal: AbortSignal.timeout(30000) });
  if (!r.ok) throw new Error(`Speech failed (${r.status})`);
  return Buffer.from(await r.arrayBuffer());
}

async function testKey(provider, key) {
  const r = await fetch(`${PROVIDERS[provider].base}/models`, { headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(15000) });
  return r.ok;
}

module.exports = { chat, complete, completeJSON, transcribe, speech, status, testKey };
