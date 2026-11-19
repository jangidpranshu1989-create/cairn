// Cairn Search for desktop: cairn://search/?q=… — Cairn's own results page with a streamed, cited AI answer.
// Also resolves navigational input ("youtube", "amazon kholo") straight to the site, like Android.
const { net, nativeTheme } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const store = require('./store');
const llm = require('./llm');
const SITES = require('./sites.json');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const UI_DIR = path.join(__dirname, '..', 'ui');
const cache = new Map();
const norm = (s) => String(s).toLowerCase().replace(/[^a-z0-9+]/g, '');
const VERBS = /^(open|go to|goto|visit|launch|kholo|khol|chalao|show)\s+|\s+(kholo|khol do|khol de|open karo|open kr|open kar|chalao|par jao|pe jao|website|site|\.com)$/i;
const AMBIG = new Set(SITES.ambiguous);

function resolveSite(input) {
  let t = String(input || '').trim().toLowerCase();
  if (!t || t.length > 40) return null;
  for (let i = 0; i < 2; i++) t = t.replace(VERBS, '').trim();
  if (t.split(/\s+/).length > 3) return null;
  const n = norm(t);
  if (SITES.map[n]) return 'https://' + SITES.map[n];
  if (n.length < 3 || AMBIG.has(n)) return null;
  const counts = new Map();
  for (const h of store.get().history.data.slice(-3000)) { try { const host = new URL(h.url).hostname; counts.set(host, (counts.get(host) || 0) + 1); } catch {} }
  const hit = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(x => x[0])
    .find(h => h !== 'search' && norm(h.replace(/^www\.|^m\./, '').split('.')[0]) === n);
  return hit ? 'https://' + hit : null;
}

const label = (host) => { const p = host.toLowerCase().replace(/^www\./, '').split('.').filter(Boolean); if (p.length < 2) return host; return p.length >= 3 && ['co', 'com', 'org', 'net', 'gov', 'ac', 'edu'].includes(p[p.length - 2]) ? p[p.length - 3] : p[p.length - 2]; };
const hostOf = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };
const ent = (s) => String(s).replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#x([0-9a-f]+);/gi, (_m, h) => String.fromCodePoint(parseInt(h, 16))).replace(/&#(\d+);/g, (_m, d) => String.fromCodePoint(+d)).replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
function clean(u) {
  u = u.replace(/&amp;/g, '&');
  const m = u.match(/uddg=([^&]+)/); if (m) u = decodeURIComponent(m[1]);
  if (u.startsWith('//')) u = 'https:' + u;
  if (!/^https?:/.test(u) || /duckduckgo\.com\/y\.js/.test(u)) return null;
  return u;
}
async function get(url, ms = 9000) {
  const r = await net.fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'en-IN,en;q=0.9' }, signal: AbortSignal.timeout(ms) });
  return r.ok ? r.text() : '';
}

function bingUrl(u) {
  u = u.replace(/&amp;/g, '&');
  const m = u.match(/[?&]u=a1([^&]+)/);
  if (m) { try { u = Buffer.from(m[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'); } catch { return null; } }
  return /^https?:/.test(u) && !/bing\.com\//.test(u) ? u : null;
}
async function ddg(q) {
  const out = [];
  const html = await get('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(q), 5000);
  const re = /<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>[\s\S]*?(?:<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)<\/a>|<\/div>)/g;
  let m; while ((m = re.exec(html)) && out.length < 10) { const u = clean(m[1]); if (u && !out.some(x => x.url === u)) out.push({ title: ent(m[2]), url: u, snippet: ent(m[3] || '').slice(0, 300) }); }
  return out;
}
async function bing(q) {
  const out = [];
  const cc = (store.get().settings.data.shoppingCountry || 'IN').toUpperCase();
  const html = await get(`https://www.bing.com/search?q=${encodeURIComponent(q).replace(/%20/g, '+')}&form=QBLH&cc=${cc}`, 7000);
  for (const it of html.match(/<li class="b_algo"[\s\S]*?<\/li>/g) || []) {
    const h = it.match(/<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/); if (!h) continue;
    const u = bingUrl(h[1]); if (!u || out.some(x => x.url === u)) continue;
    const p = it.match(/<p[^>]*>([\s\S]*?)<\/p>/);
    out.push({ title: ent(h[2]), url: u, snippet: ent(p ? p[1] : '').replace(/^Web\s*/, '').slice(0, 300) });
    if (out.length >= 10) break;
  }
  return out;
}

async function results(q) {
  const key = q.trim().toLowerCase();
  if (cache.has(key)) return cache.get(key);
  // Two independent indexes in parallel: prefer DuckDuckGo, fall back to Bing instantly if it's slow or blocked.
  const b = bing(q).catch(() => []);
  let out = await ddg(q).catch(() => []);
  if (!out.length) out = await b;
  out = out.map((r, i) => ({ ...r, n: i + 1, host: hostOf(r.url), site: label(hostOf(r.url)).replace(/^./, c => c.toUpperCase()) }));
  if (out.length) { cache.set(key, out); if (cache.size > 30) cache.delete(cache.keys().next().value); }
  return out;
}

async function related(q) {
  const ok = (l) => l.filter(s => typeof s === 'string' && s.toLowerCase() !== q.toLowerCase()).slice(0, 6);
  try { const a = JSON.parse(await get('https://duckduckgo.com/ac/?type=list&q=' + encodeURIComponent(q), 3500)); const l = ok(Array.isArray(a[1]) ? a[1] : a); if (l.length) return l; } catch {}
  try { const a = JSON.parse(await get('https://api.bing.com/osjson.aspx?query=' + encodeURIComponent(q), 3500)); return ok(a[1] || []); } catch { return []; }
}

async function pageText(url, max = 3500) {
  try {
    const r = await net.fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(4000) });
    if (!r.ok || !/html/.test(r.headers.get('content-type') || '')) return '';
    let html = (await r.text()).slice(0, 600000).replace(/<(script|style|noscript|svg|nav|footer|header|form|aside)[^>]*>[\s\S]*?<\/\1>/gi, ' ');
    const main = html.match(/<(article|main)[^>]*>([\s\S]*?)<\/\1>/i); if (main) html = main[2];
    return ent(html.replace(/<(br|p|li|h[1-6]|div|tr)[^>]*>/gi, '\n')).slice(0, max);
  } catch { return ''; }
}

function lang(q) {
  if (/[\u0900-\u097F]/.test(q)) return 'Reply in Hindi (Devanagari).';
  if (/\b(kya|kaise|kaun|kitna|kab|kahan|hai|hain|mujhe|sabse|sasta|batao|chahiye|karna|kyu|kyon|wala|aur)\b/i.test(q)) return 'Reply in Hinglish (Hindi in Latin script), matching the user.';
  return 'Reply in English unless the search is clearly written in another language — then use that language.';
}

function answerStream(q) {
  const enc = new TextEncoder();
  return new ReadableStream({
    async start(ctrl) {
      try {
        const rs = await results(q);
        if (!rs.length) { ctrl.enqueue(enc.encode('\u0000ERR:No results to answer from.')); return ctrl.close(); }
        const pages = await Promise.race([Promise.all(rs.slice(0, 4).map(r => pageText(r.url))), new Promise(res => setTimeout(() => res([]), 5000))]);
        const today = new Date().toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
        const ctx = rs.slice(0, 6).map((r, i) => `[${r.n}] ${r.title}\nURL: ${r.url}\n${(pages[i] && pages[i].length > 200) ? pages[i] : r.snippet}`).join('\n\n');
        const sys = `You are Cairn, the answer engine inside the Cairn browser. Today is ${today}.
Answer the user's search using ONLY the numbered sources below. Cite every factual sentence with the source number in square brackets right after it, like [1] or [2][4]. Never invent sources, numbers or links.
Start with a direct 1–2 sentence answer. Then add short sections or bullets only if they help. Use **bold** for key facts. Keep it under 220 words. If sources disagree or don't answer it, say so briefly.
${lang(q)}`;
        await llm.stream([{ role: 'system', content: sys }, { role: 'user', content: `Search: ${q}\n\nSources:\n${ctx}` }], (d) => ctrl.enqueue(enc.encode(d)), { maxTokens: 900 });
      } catch (e) {
        ctrl.enqueue(enc.encode('\u0000ERR:' + (e.message === 'NO_AI_KEY' ? "Cairn's AI isn't set up yet. Add a key in Settings → AI." : 'The answer couldn\'t be generated.')));
      }
      ctrl.close();
    },
  });
}

const FONTS = { regular: 'Inter-Regular.woff2', medium: 'Inter-Medium.woff2', semibold: 'Inter-SemiBold.woff2', display: 'Inter-Bold.woff2' };

/** Handles cairn://search/… requests. */
async function handle(u) {
  const p = u.pathname;
  const json = (o) => new Response(JSON.stringify(o), { headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
  if (p === '/' || p === '') {
    const q = (u.searchParams.get('q') || '').trim();
    const t = store.get().settings.data.theme; const dark = t === 'system' ? nativeTheme.shouldUseDarkColors : t !== 'light';
    const esc = q.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const html = fs.readFileSync(path.join(UI_DIR, 'pages', 'search.html'), 'utf8').replace(/\{\{Q\}\}/g, esc).replace('{{THEME}}', dark ? 'dark' : 'light').replace('{{LANG}}', /[\u0900-\u097F]/.test(q) ? 'hi' : 'en')
      .replace(/url\(\/font\/(\w+)\) format\("truetype"\)/g, (_m, k) => `url(/font/${k}) format("woff2")`);
    return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });
  }
  if (p === '/api/results') {
    const q = u.searchParams.get('q') || '';
    const [rs, rel] = await Promise.all([results(q), related(q)]);
    const nav = rs[0] && q.trim().split(/\s+/).length <= 2 && norm(q).length >= 3 && !AMBIG.has(norm(q)) && norm(q) === norm(label(rs[0].host)) ? rs[0].url : null;
    return json({ results: rs, nav, related: rel, answers: store.get().settings.data.searchAnswers !== false && llm.status().ready });
  }
  if (p === '/api/answer') return new Response(answerStream(u.searchParams.get('q') || ''), { headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' } });
  if (p === '/icon') {
    const h = (u.searchParams.get('h') || '').replace(/[^a-z0-9.-]/gi, '');
    for (const src of [`https://icons.duckduckgo.com/ip3/${h}.ico`, `https://${h}/favicon.ico`, `https://www.${h.replace(/^www\./, '')}/apple-touch-icon.png`]) {
      try { const r = await net.fetch(src, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(4000) }); const ct = r.headers.get('content-type') || ''; if (r.ok && !/html|json/.test(ct)) { const b = await r.arrayBuffer(); if (b.byteLength > 60) return new Response(b, { headers: { 'content-type': ct || 'image/x-icon', 'cache-control': 'max-age=604800' } }); } } catch {}
    }
    return new Response('', { status: 404 });
  }
  if (p.startsWith('/font/')) {
    const f = FONTS[p.slice(6)]; if (!f) return new Response('', { status: 404 });
    return new Response(fs.readFileSync(path.join(UI_DIR, 'fonts', f)), { headers: { 'content-type': 'font/woff2', 'cache-control': 'max-age=31536000' } });
  }
  return new Response('Not found', { status: 404 });
}

module.exports = { resolveSite, results, pageText, handle };
