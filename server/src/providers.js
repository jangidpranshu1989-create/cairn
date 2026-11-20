// Shared LLM provider layer: Groq (primary) + NVIDIA NIM (fallback), OpenAI-compatible.
// Used by the server proxy and by the desktop app in "bring your own key" mode.

export const PROVIDERS = {
  groq: { base: 'https://api.groq.com/openai/v1', label: 'Groq' },
  nvidia: { base: 'https://integrate.api.nvidia.com/v1', label: 'NVIDIA NIM' },
  // Keyless community endpoint (OpenAI-compatible, supports tools + streaming). Built-in last resort so Cairn's
  // assistant works out of the box with zero setup. Disable with AI_FREE_FALLBACK=0.
  gemini: { base: 'https://generativelanguage.googleapis.com/v1beta/openai', label: 'Gemini' },
  openrouter: { base: 'https://openrouter.ai/api/v1', label: 'OpenRouter' },
  // Keyless OpenAI-compatible endpoint (tools + streaming). The old text.pollinations.ai/openai endpoint was retired
  // (it now answers 404 "Model not found" / 402), which surfaced in the app as a bare "Not found".
  // Keyless public gateways (no account, per-IP limits), used after every keyed provider. LLM7 was dropped: since
  // November 2026 it needs a token and answered anonymous calls with 429.
  kilo: { base: 'https://ai-gateway.kilo.ai/api/v1', label: 'Kilo Gateway', keyless: true, noJson: true,
    models: { smart: 'nvidia/nemotron-3.5-lightning:free', fast: 'nvidia/nemotron-3.5-lightning:free', vision: 'nvidia/nemotron-3.5-super-vl:free' } },
  ovh: { base: 'https://oai.endpoints.kepler.ai.cloud.ovh.net/v1', label: 'OVHcloud AI', keyless: true,
    models: { smart: 'gpt-oss-120b', fast: 'gpt-oss-20b', vision: 'qwen3-vl-30b-a3b' } },
  pollinations: { base: 'https://text.pollinations.ai', path: '/openai', label: 'Pollinations', keyless: true, noJson: true,
    models: { smart: 'openai', fast: 'openai' } },
};
const KEYLESS = ['kilo', 'ovh', 'pollinations'];
const keyFor = (p, cfg) => (p === 'groq' ? cfg.groqKey : p === 'nvidia' ? cfg.nvidiaKey : p === 'gemini' ? cfg.geminiKey : p === 'openrouter' ? cfg.openrouterKey : '');

export function modelFor(provider, tier, cfg) {
  if (PROVIDERS[provider]?.models) return PROVIDERS[provider].models[tier] || PROVIDERS[provider].models.smart;
  if (provider === 'openrouter') return cfg.openrouterModel || 'openrouter/free';
  if (provider === 'gemini') return cfg.geminiModel;
  if (tier === 'vision') return provider === 'groq' ? cfg.groqVisionModel : cfg.nvidiaVisionModel;
  if (provider === 'groq') return tier === 'fast' ? cfg.groqFastModel : cfg.groqModel;
  return tier === 'fast' ? cfg.nvidiaFastModel : cfg.nvidiaModel;
}

/** Order providers by preference; skip ones without keys. Vision prefers NVIDIA (multimodal Nemotron). */
export function providerOrder(cfg, tier, preferred) {
  let order = tier === 'vision' ? ['groq', 'gemini', 'nvidia', 'openrouter'] : ['groq', 'nvidia', 'gemini', 'openrouter'];
  if (preferred && order.includes(preferred)) order = [preferred, ...order.filter(p => p !== preferred)];
  // The retired text.pollinations.ai host is gone from the chain: it only answered 402/404, and its status used to leak
  // to the app as "Cairn Cloud 404".
  if (cfg.freeFallback !== false) order.push(...KEYLESS.filter(p => PROVIDERS[p].models[tier]));
  return order.filter(p => PROVIDERS[p].keyless || keyFor(p, cfg));
}

// Any of these means "this provider can't answer right now" → move on to the next one instead of failing the request.
const retriable = (status) => [400, 401, 402, 403, 404, 408, 409, 413, 422, 429].includes(status) || status >= 500;

/** Rough token count of a request (≈4 chars per token), used to skip providers whose free tier can't take it. */
export function estimateTokens(messages, tools) {
  return Math.ceil((JSON.stringify(messages || []).length + JSON.stringify(tools || []).length) / 4);
}
// Groq's free tier caps a single request at ~8k tokens per minute for gpt-oss-120b (6k for 20b). Bigger agent steps
// would only bounce with 413/429 and waste 2-3 s, so they go straight to NVIDIA NIM instead.
const GROQ_MAX_TOKENS = { smart: 7000, fast: 5500, vision: 6000 };

/** The error thrown when every provider failed. Always 503, never an upstream 404/402, with each provider's status. */
export class AllProvidersFailed extends Error {
  constructor(attempts) {
    super('Cairn AI is busy on every provider right now. Please try again in a few seconds.');
    this.status = 503; this.attempts = attempts; this.retryAfter = 5;
  }
}

/**
 * chat({ messages, tools, tier, stream, temperature, maxTokens, preferred }, cfg)
 * Returns fetch Response when stream=true, else parsed JSON (OpenAI format) with `_provider` field.
 * Tries every configured provider in order, then makes one more pass after a short pause (rate limits usually clear
 * within seconds). If all fail it throws AllProvidersFailed (503) so clients never mistake it for a missing route.
 */
export async function chat(opts, cfg) {
  const tier = opts.tier || 'smart';
  const order = providerOrder(cfg, tier, opts.preferred);
  if (!order.length) throw Object.assign(new Error(tier === 'vision' ? 'Image understanding is not available on this server yet.' : 'AI is not configured on this server.'), { status: 503 });
  const size = estimateTokens(opts.messages, opts.tools);
  const attempts = [];
  const passes = opts._single ? 1 : 2;
  for (let pass = 0; pass < passes; pass++) {
    if (pass > 0) await new Promise(r => setTimeout(r, 1200));
    for (const p of order) {
      if (p === 'groq' && !opts._small && size > (GROQ_MAX_TOKENS[tier] || 7000) && order.length > 1) { if (pass === 0) attempts.push('groq: too large for free tier'); continue; }
      try {
        const out = await callProvider(p, opts, cfg);
        return out;
      } catch (e) {
        if (e.name === 'AbortError' && opts.signal?.aborted) throw e;
        attempts.push(`${p}: ${e.status || e.name || 'error'}`);
        if (e.status && !retriable(e.status)) throw e;
      }
    }
  }
  console.warn('[ai] all providers failed', attempts.join(' | '));
  throw new AllProvidersFailed(attempts);
}

async function callProvider(p, opts, cfg) {
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
  if (PROVIDERS[p].noJson) delete body.response_format;
  const headers = { 'Content-Type': 'application/json' };
  if (keyFor(p, cfg)) headers.Authorization = `Bearer ${keyFor(p, cfg)}`;
  const r = await fetch(`${PROVIDERS[p].base}${PROVIDERS[p].path || '/chat/completions'}`, {
    method: 'POST', headers, body: JSON.stringify(body),
    signal: opts.signal || AbortSignal.timeout(opts.stream ? 60_000 : 75_000),
  });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    const err = Object.assign(new Error(`${PROVIDERS[p].label} ${r.status}: ${text.slice(0, 300)}`), { status: r.status });
    // Groq free tier: on 429 back off 1.5 s and retry once on the small instant model (higher limits).
    if (r.status === 429 && p === 'groq' && !opts._small && !opts.stream && opts.tier !== 'vision') {
      await new Promise(res => setTimeout(res, 1500));
      return callProvider('groq', { ...opts, _small: true, provider: 'groq', model: cfg.groqSmallModel || 'llama-3.1-8b-instant' }, cfg);
    }
    throw err;
  }
  if (opts.stream) { r._provider = p; return r; }
  const j = await r.json();
  const m = j?.choices?.[0]?.message;
  const empty = !m || ((!m.content || m.content === 'null') && !(m.tool_calls?.length));
  if (empty) throw Object.assign(new Error(`${PROVIDERS[p].label}: empty reply`), { status: 502 });
  if (Array.isArray(m.tool_calls) && !m.tool_calls.length) delete m.tool_calls;
  delete m.reasoning; delete m.reasoning_content;
  j._provider = p;
  return j;
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
