// Shared LLM provider layer: Groq (primary) + NVIDIA NIM (fallback), OpenAI-compatible.
// Used by the server proxy and by the desktop app in "bring your own key" mode.

export const PROVIDERS = {
  groq: { base: 'https://api.groq.com/openai/v1', label: 'Groq' },
  nvidia: { base: 'https://integrate.api.nvidia.com/v1', label: 'NVIDIA NIM' },
  // Keyless community endpoint (OpenAI-compatible, supports tools + streaming). Built-in last resort so Cairn's
  // assistant works out of the box with zero setup. Disable with AI_FREE_FALLBACK=0.
  gemini: { base: 'https://generativelanguage.googleapis.com/v1beta/openai', label: 'Gemini' },
  free: { base: 'https://text.pollinations.ai', path: '/openai', label: 'Cairn Free AI', keyless: true },
};
const keyFor = (p, cfg) => (p === 'groq' ? cfg.groqKey : p === 'nvidia' ? cfg.nvidiaKey : p === 'gemini' ? cfg.geminiKey : '');

export function modelFor(provider, tier, cfg) {
  if (provider === 'free') return cfg.freeModel || 'openai';
  if (provider === 'gemini') return cfg.geminiModel;
  if (tier === 'vision') return provider === 'groq' ? cfg.groqVisionModel : cfg.nvidiaVisionModel;
  if (provider === 'groq') return tier === 'fast' ? cfg.groqFastModel : cfg.groqModel;
  return tier === 'fast' ? cfg.nvidiaFastModel : cfg.nvidiaModel;
}

/** Order providers by preference; skip ones without keys. Vision prefers NVIDIA (multimodal Nemotron). */
export function providerOrder(cfg, tier, preferred) {
  let order = tier === 'vision' ? ['groq', 'gemini', 'nvidia'] : ['groq', 'nvidia', 'gemini'];
  if (preferred && order.includes(preferred)) order = [preferred, ...order.filter(p => p !== preferred)];
  if (tier !== 'vision' && cfg.freeFallback !== false) order.push('free');
  return order.filter(p => PROVIDERS[p].keyless || keyFor(p, cfg));
}

const retriable = (status) => status === 429 || status >= 500 || status === 408;

/**
 * chat({ messages, tools, tier, stream, temperature, maxTokens, preferred }, cfg)
 * Returns fetch Response when stream=true, else parsed JSON (OpenAI format) with `_provider` field.
 */
export async function chat(opts, cfg) {
  const order = providerOrder(cfg, opts.tier || 'smart', opts.preferred);
  if (!order.length) throw Object.assign(new Error(opts.tier === 'vision' ? 'Image understanding is not available on this server yet.' : 'AI is not configured on this server.'), { status: 503 });
  let lastErr;
  for (const p of order) {
    const body = {
      model: opts.model && opts.provider === p ? opts.model : modelFor(p, opts.tier, cfg),
      messages: opts.messages,
      temperature: opts.temperature ?? 0.3,
      max_tokens: opts.maxTokens ?? 4096,
      stream: !!opts.stream,
    };
    if (opts.tools?.length) { body.tools = opts.tools; body.tool_choice = opts.toolChoice || 'auto'; }
    if (opts.json) body.response_format = { type: 'json_object' };
    if (p === 'nvidia') body.chat_template_kwargs = { enable_thinking: false };
    if (p === 'groq' && /gpt-oss/.test(body.model)) body.reasoning_effort = opts.reasoning || 'low';
    if (p === 'gemini') delete body.chat_template_kwargs;
    try {
      if (p === 'free') { delete body.chat_template_kwargs; delete body.reasoning_effort; delete body.response_format; }
      const headers = { 'Content-Type': 'application/json' };
      if (keyFor(p, cfg)) headers.Authorization = `Bearer ${keyFor(p, cfg)}`;
      const r = await fetch(`${PROVIDERS[p].base}${PROVIDERS[p].path || '/chat/completions'}`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: opts.signal || AbortSignal.timeout(90_000),
      });
      if (!r.ok) {
        const text = await r.text();
        lastErr = Object.assign(new Error(`${PROVIDERS[p].label} ${r.status}: ${text.slice(0, 300)}`), { status: r.status });
        if (retriable(r.status) || r.status === 404 || r.status === 400) continue; // try next provider
        throw lastErr;
      }
      if (opts.stream) { r._provider = p; return r; }
      const j = await r.json();
      j._provider = p;
      return j;
    } catch (e) {
      if (e.name === 'AbortError' && opts.signal?.aborted) throw e;
      lastErr = e;
    }
  }
  throw lastErr;
}

export async function transcribe(blob, filename, cfg, language) {
  if (!cfg.groqKey) throw Object.assign(new Error('Voice input needs a Groq API key'), { status: 503 });
  const fd = new FormData();
  fd.append('file', blob, filename || 'audio.webm');
  fd.append('model', cfg.sttModel || 'whisper-large-v3-turbo');
  fd.append('response_format', 'json');
  if (language) fd.append('language', language);
  const r = await fetch(`${PROVIDERS.groq.base}/audio/transcriptions`, { method: 'POST', headers: { Authorization: `Bearer ${cfg.groqKey}` }, body: fd });
  if (!r.ok) throw Object.assign(new Error(`Transcription failed: ${r.status} ${(await r.text()).slice(0, 200)}`), { status: r.status });
  return (await r.json()).text;
}
