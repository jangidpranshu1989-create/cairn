// Multi-store price comparison: searches several retailers in parallel (in hidden tabs),
// extracts listings, filters out accessories / wrong variants with the LLM, scores trust and ranks.
const { withPage } = require('./background');
const dom = require('./dom');
const llm = require('../llm');

const STORES = {
  IN: [
    { id: 'amazon', name: 'Amazon.in', trust: 0.95, url: q => `https://www.amazon.in/s?k=${q}`, link: /\/(dp|gp\/product)\// },
    { id: 'flipkart', name: 'Flipkart', trust: 0.93, url: q => `https://www.flipkart.com/search?q=${q}`, link: /\/p\/itm/ },
    { id: 'croma', name: 'Croma', trust: 0.92, url: q => `https://www.croma.com/searchB?q=${q}%3Arelevance&text=${q}`, link: /\/p\/\d+/ },
    { id: 'reliance', name: 'Reliance Digital', trust: 0.92, url: q => `https://www.reliancedigital.in/products?q=${q}`, link: /\/product\/|\/p\/\d+|-p-\d+/ },
    { id: 'vijaysales', name: 'Vijay Sales', trust: 0.9, url: q => `https://www.vijaysales.com/search-listing?q=${q}`, link: /\/p\/|\/\d{4,}\// },
    { id: 'tatacliq', name: 'Tata CLiQ', trust: 0.88, url: q => `https://www.tatacliq.com/search/?searchCategory=all&text=${q}`, link: /\/p-mp\d+/ },
  ],
  US: [
    { id: 'amazon', name: 'Amazon.com', trust: 0.95, url: q => `https://www.amazon.com/s?k=${q}`, link: /\/(dp|gp\/product)\// },
    { id: 'bestbuy', name: 'Best Buy', trust: 0.93, url: q => `https://www.bestbuy.com/site/searchpage.jsp?st=${q}`, link: /\/site\/.+\.p/ },
    { id: 'walmart', name: 'Walmart', trust: 0.9, url: q => `https://www.walmart.com/search?q=${q}`, link: /\/ip\// },
    { id: 'target', name: 'Target', trust: 0.9, url: q => `https://www.target.com/s?searchTerm=${q}`, link: /\/p\// },
    { id: 'ebay', name: 'eBay', trust: 0.75, url: q => `https://www.ebay.com/sch/i.html?_nkw=${q}`, link: /\/itm\// },
  ],
  GB: [
    { id: 'amazon', name: 'Amazon.co.uk', trust: 0.95, url: q => `https://www.amazon.co.uk/s?k=${q}`, link: /\/(dp|gp\/product)\// },
    { id: 'argos', name: 'Argos', trust: 0.92, url: q => `https://www.argos.co.uk/search/${q}/`, link: /\/product\// },
    { id: 'currys', name: 'Currys', trust: 0.92, url: q => `https://www.currys.co.uk/search?q=${q}`, link: /\/products\// },
    { id: 'johnlewis', name: 'John Lewis', trust: 0.92, url: q => `https://www.johnlewis.com/search?search-term=${q}`, link: /\/p\d+/ },
  ],
};

// Runs inside the retailer page (isolated world). Generic card detection + Amazon-specific path.
function extractListingsFn(linkPattern, storeId) {
  const out = [];
  const money = /(?:₹|Rs\.?\s?|\$|£|€)\s?([\d,]+(?:\.\d{1,2})?)/;
  const num = (s) => Number(String(s).replace(/,/g, ''));
  const isStruck = (el) => { for (let n = el; n && n !== document.body; n = n.parentElement) { const s = getComputedStyle(n); if (s.textDecorationLine.includes('line-through') || /^(DEL|S|STRIKE)$/.test(n.tagName)) return true; } return false; };
  const emiCtx = (el) => /\/mo|per month|month|emi|\bno cost\b|save|off|cashback|bank offer|exchange/i.test((el.parentElement?.innerText || '').slice(0, 80));
  const rating = (t) => { const m = t.match(/\b([1-5](?:\.\d)?)\s*(?:out of 5|★|stars?|\s*\(\s*[\d,]+\s*\))/i); const v = m ? Number(m[1]) : null; return v && v >= 1 && v <= 5 ? v : null; };
  const JUNK = /unavailable|visit nearest|add to (cart|bag|compare)|buy now|notify me|out of stock|bank offer|no cost emi|free delivery|sponsored|compare|exchange offer|coming soon|bestseller|only \d+ left/i;
  // Every plausible URL for an <img>: srcset / lazy-load attributes / <picture> sources / <noscript> copies, largest first.
  const imgUrls = (i) => {
    if (!i) return [];
    const c = [];
    const bad = /^data:|blank|spacer|placeholder|transparent|1x1|pixel\.|loader|lazy[-_.]?(load|img)?\.(gif|png|svg)|\.svg(\?|$)/i;
    const push = (u, w) => { if (!u || bad.test(u)) return; try { c.push({ u: new URL(u.trim(), location.href).href, w }); } catch (e) {} };
    const set = (s) => (s || '').split(/,\s+(?=\S)/).forEach(p => { const [u, d] = p.trim().split(/\s+/); const n = parseFloat(d) || 1; push(u, /x$/i.test(d || '') ? n * 300 : n); });
    set(i.getAttribute('srcset')); set(i.getAttribute('data-srcset')); set(i.getAttribute('data-lazy-srcset'));
    i.closest('picture')?.querySelectorAll('source').forEach(s => { if (!/avif/i.test(s.type || '')) { set(s.getAttribute('srcset')); set(s.getAttribute('data-srcset')); } });
    ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-image', 'data-img', 'data-zoom-image', 'data-hires', 'data-old-hires', 'data-a-hires'].forEach(a => push(i.getAttribute(a), 600));
    try { const dyn = JSON.parse(i.getAttribute('data-a-dynamic-image') || '{}'); Object.entries(dyn).forEach(([u, d]) => push(u, (d && d[0]) || 500)); } catch (e) {}
    if (i.complete && i.naturalWidth > 1) push(i.currentSrc || i.src, i.naturalWidth); else push(i.getAttribute('src'), 200);
    const ok = c.filter(x => x.w >= 240 && x.w <= 1400).sort((a, b) => a.w - b.w);       // smallest that is still sharp
    const rest = c.filter(x => !ok.includes(x)).sort((a, b) => b.w - a.w);
    return [...ok, ...rest].map(x => x.u).filter((u, k, a) => a.indexOf(u) === k).slice(0, 4);
  };
  const cardImages = (card) => {
    const imgs = [...card.querySelectorAll('img')].filter(i => !/sprite|icon|star|logo|badge|rating|flag/i.test((i.className || '') + ' ' + (i.getAttribute('src') || '') + ' ' + (i.alt || '')) && (i.getBoundingClientRect().width || Number(i.getAttribute('width')) || 100) >= 50);
    imgs.sort((a, b) => (b.getBoundingClientRect().width * b.getBoundingClientRect().height) - (a.getBoundingClientRect().width * a.getBoundingClientRect().height));
    let urls = imgs.flatMap(imgUrls);
    if (!urls.length) card.querySelectorAll('noscript').forEach(n => { const m = n.textContent.match(/src=["']([^"']+)/); if (m) urls.push(new URL(m[1], location.href).href); });
    if (!urls.length) card.querySelectorAll('[style*="background-image"]').forEach(e => { const m = e.style.backgroundImage.match(/url\(["']?([^"')]+)/); if (m && !/^data:/.test(m[1])) urls.push(new URL(m[1], location.href).href); });
    return { img: imgs[0], urls: urls.filter((u, k, a) => a.indexOf(u) === k).slice(0, 4) };
  };
  const reviews = (t) => { const m = t.match(/([\d,]{2,})\s*(?:ratings|reviews|rating)/i) || t.match(/\((\d[\d,]*)\)/); return m ? num(m[1]) : null; };

  if (storeId === 'amazon') {
    document.querySelectorAll('div[data-component-type="s-search-result"]').forEach(card => {
      const a = card.querySelector('h2 a, a.a-link-normal.s-no-outline, a[href*="/dp/"]');
      const title = (card.querySelector('h2')?.getAttribute('aria-label') || card.querySelector('h2')?.innerText || '').trim();
      const price = card.querySelector('.a-price:not(.a-text-price) .a-offscreen')?.textContent;
      const mrp = card.querySelector('.a-price.a-text-price .a-offscreen')?.textContent;
      if (!a || !title || !price) return;
      out.push({ title, url: new URL(a.getAttribute('href'), location.href).href, price: num(price.match(money)?.[1] || 0), mrp: mrp ? num(mrp.match(money)?.[1] || 0) : null,
        rating: rating(card.querySelector('.a-icon-alt')?.textContent || ''), reviews: num((card.querySelector('[aria-label$="ratings"], span.s-underline-text')?.textContent || '').replace(/[^\d]/g, '')) || null,
        sponsored: !!card.querySelector('.puis-sponsored-label-text, [aria-label="View Sponsored information or leave ad feedback"]'),
        ...(() => { const u = imgUrls(card.querySelector('img.s-image')); return { image: u[0] || '', images: u }; })() });
    });
    return out.slice(0, 16);
  }

  const re = new RegExp(linkPattern);
  const seen = new Set();
  for (const a of document.querySelectorAll('a[href]')) {
    const href = a.href.split('?')[0].split('#')[0];
    if (!re.test(href) || seen.has(href)) continue;
    // climb to a card that contains a price but is not the whole grid
    let card = a, depth = 0;
    while (card && depth < 8 && !(money.test(card.innerText || '') && card.getBoundingClientRect().height > 60)) { card = card.parentElement; depth++; }
    if (!card || card.getBoundingClientRect().height > 1200 || (card.innerText || '').length > 1500) continue;
    seen.add(href);
    const leaves = [...card.querySelectorAll('*')].filter(e => e.childElementCount === 0 && money.test(e.textContent) && e.textContent.trim().length < 24);
    let best = null, bestSize = 0, mrp = null;
    for (const l of leaves) {
      const v = num(l.textContent.match(money)[1]);
      if (!v) continue;
      if (isStruck(l)) { mrp = Math.max(mrp || 0, v); continue; }
      if (emiCtx(l)) continue;
      const size = parseFloat(getComputedStyle(l).fontSize) || 0;
      if (size > bestSize || (size === bestSize && v < best)) { bestSize = size; best = v; }
    }
    if (!best) { const m = (card.innerText || '').match(money); if (m) best = num(m[1]); }
    const { img, urls: imgs } = cardImages(card);
    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const ok = (s) => s.length > 12 && s.length < 220 && !money.test(s) && !JUNK.test(s);
    const strong = [a.getAttribute('title'), a.getAttribute('aria-label'), card.querySelector('h1,h2,h3,h4,[class*=title i],[class*=name i]')?.textContent, img?.alt].map(clean).filter(ok);
    const weak = [...card.querySelectorAll('a, div, span, p')].map(e => e.childElementCount === 0 ? clean(e.textContent) : '').filter(ok);
    const title = strong.sort((x, y) => y.length - x.length)[0] || weak.sort((x, y) => y.length - x.length)[0];
    if (!title || !best) continue;
    const t = card.innerText || '';
    out.push({ title, url: href, price: best, mrp: mrp && mrp > best ? mrp : null, rating: rating(t), reviews: reviews(t), sponsored: /sponsored|\bad\b/i.test(t.slice(0, 60)), image: imgs[0] || '', images: imgs });
    if (out.length >= 16) break;
  }
  return out;
}

// Wait (up to ~9s) until the SPA has rendered product links, then extract.
async function extractWhenReady(wc, store) {
  const src = store.link.source;
  for (let i = 0; i < 12; i++) {
    const n = await wc.executeJavaScript(`(() => { const re = new RegExp(${JSON.stringify(src)}); return [...document.querySelectorAll('a[href]')].filter(a => re.test(a.href)).length; })()`).catch(() => 0);
    if (n >= 4) break;
    await new Promise(r => setTimeout(r, 750));
  }
  const blocked = await wc.executeJavaScript(`/503|robot|captcha|access denied|unusual traffic/i.test(document.title + ' ' + document.body.innerText.slice(0, 400))`).catch(() => false);
  const items = await dom.run(wc, extractListingsFn, src, store.id);
  if (!items?.length && blocked) throw new Error('the store blocked automated access — try opening it yourself');
  return items;
}

async function searchStore(store, query, signal) {
  const q = encodeURIComponent(query);
  let lastErr;
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const items = await withPage(store.url(q), (wc) => extractWhenReady(wc, store), { timeout: 30000, settleMs: 1500, scrolls: 2, signal });
      const list = (items || []).filter(i => i.price > 0).map(i => ({ ...i, store: store.name, storeId: store.id }));
      if (list.length || attempt) return { store, items: list };
    } catch (e) { lastErr = e; if (signal?.aborted || /blocked|timeout/i.test(e.message)) break; }
    await new Promise(r => setTimeout(r, 1200));
  }
  return { store, items: [], error: lastErr?.message };
}

// Fast, deterministic relevance filter (runs before — and as a fallback for — the LLM filter).
const ACCESSORY = /\b(case|cover|covers|back cover|screen ?guard|tempered|protector|glass|skin|charger|adapter|cable|cord|strap|band|holder|stand|mount|pouch|sleeve|stylus|lens protector|magsafe wallet|earphones?|airpods? case|keyboard cover|refill|spare|replacement)\b/i;
const VARIANTS = ['pro', 'max', 'plus', 'mini', 'ultra', 'lite', 'fe', 'air', 'neo', 'prime', 'edge', 'fold', 'flip'];
function heuristicFilter(query, items) {
  const q = query.toLowerCase();
  const qTokens = q.match(/[a-z0-9]+/g) || [];
  const nums = qTokens.filter(t => /\d/.test(t));
  const words = qTokens.filter(t => !/\d/.test(t) && t.length > 2 && !['the', 'and', 'for', 'with', 'buy', 'cheapest', 'best', 'price', 'under', 'sabse', 'sasta', 'chahiye', 'gb', 'tb'].includes(t));
  const wantsAccessory = ACCESSORY.test(q);
  return items.filter(it => {
    const t = it.title.toLowerCase().replace(/(\d+)\s*(gb|tb)/g, '$1$2');
    const tt = t.match(/[a-z0-9]+/g) || [];
    if (!wantsAccessory && ACCESSORY.test(t)) return false;
    for (const n of nums) {
      const nn = n.replace(/(\d+)(gb|tb)/, '$1$2');
      if (/^\d+(gb|tb)$/.test(nn)) { if (!tt.includes(nn) && /\d+(gb|tb)/.test(t)) return false; continue; }
      if (!tt.includes(nn)) return false; // "16" must appear as its own token (rejects "16e", "160")
    }
    if (words.length && words.filter(w => t.includes(w)).length < Math.ceil(words.length * 0.6)) return false;
    for (const v of VARIANTS) if (tt.includes(v) && !qTokens.includes(v)) return false;
    return true;
  });
}

const median = (a) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };

function trustScore(item, store, med) {
  let s = store.trust * 70;
  if (item.rating) s += (item.rating - 3) * 8;            // up to +16
  if (item.reviews) s += Math.min(10, Math.log10(item.reviews + 1) * 3);
  if (item.sponsored) s -= 4;
  const flags = [];
  if (med && item.price < med * 0.6) { s -= 25; flags.push('Price unusually low — possible refurbished, fake or wrong variant'); }
  if (/refurbished|renewed|pre-owned|used|open box/i.test(item.title)) { s -= 10; flags.push('Refurbished / renewed'); }
  if (item.reviews !== null && item.reviews < 5) flags.push('Very few reviews');
  return { score: Math.max(0, Math.min(100, Math.round(s))), flags };
}

/**
 * compare(query, { country, onProgress, signal }) → { query, results: [...ranked], stores: [{name, count, error}] }
 */
async function compare(query, { country = 'IN', onProgress = () => {}, signal, maxStores } = {}) {
  const stores = (STORES[country] || STORES.IN).slice(0, maxStores || 10);
  onProgress({ text: `Searching ${stores.length} stores: ${stores.map(s => s.name).join(', ')}` });
  const settled = await Promise.all(stores.map(s => searchStore(s, query, signal).then(r => {
    onProgress({ text: r.error ? `${s.name}: couldn't load (${r.error})` : `${s.name}: found ${r.items.length} listings`, store: s.name });
    return r;
  })));
  let all = settled.flatMap(r => r.items);
  if (process.env.CAIRN_DEBUG_SHOP) for (const x of all) console.log('RAW', x.store, x.price, x.title.slice(0, 80));
  const pre = heuristicFilter(query, all);
  if (pre.length >= 2 || (pre.length && all.length < 6)) all = pre;
  if (!all.length) return { query, results: [], stores: settled.map(r => ({ name: r.store.name, count: 0, error: r.error })) };

  // LLM relevance filter — keep only the exact product the user asked for (no cases, chargers, other models)
  onProgress({ text: `Matching ${all.length} listings to "${query}"…` });
  try {
    const list = all.slice(0, 90).map((it, i) => `${i}|${it.store}|${it.title.slice(0, 140)}|${it.price}`).join('\n');
    const j = await llm.completeJSON(
      'You match e-commerce listings to a shopping request. Keep only listings that ARE the requested product itself (same model; any colour; respect storage/size if the user specified it). Exclude accessories, cases, covers, chargers, screen guards, bundles of unrelated items, other models/generations. For each kept listing give a short normalized variant label like "128 GB, Black".',
      `Request: ${query}\nListings (index|store|title|price):\n${list}\n\nReturn {"keep":[{"i":index,"variant":"..."}]}`,
      { tier: 'fast', maxTokens: 2000 });
    const keep = new Map((j.keep || []).map(k => [Number(k.i), k.variant]));
    if (keep.size) all = all.slice(0, 90).map((it, i) => ({ it, i })).filter(o => keep.has(o.i)).map(o => ({ ...o.it, variant: keep.get(o.i) || '' }));
  } catch { /* fall back to unfiltered list */ }

  const med = median(all.map(i => i.price));
  const ranked = all.map(it => {
    const store = stores.find(s => s.id === it.storeId);
    const t = trustScore(it, store, med);
    return { ...it, trust: t.score, flags: t.flags, discount: it.mrp ? Math.round((1 - it.price / it.mrp) * 100) : null };
  }).sort((a, b) => a.price - b.price);

  // Best pick = cheapest listing with trust >= 70; dedupe identical store+price
  const seen = new Set();
  const results = ranked.filter(r => { const k = r.storeId + r.price + r.title.slice(0, 30); if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 24);
  const best = results.find(r => r.trust >= 70) || results[0];
  if (best) best.best = true;
  return { query, results, best, stores: settled.map(r => ({ name: r.store.name, count: r.items.length, error: r.error })) };
}

module.exports = { compare, STORES, heuristicFilter };
