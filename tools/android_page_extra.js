// Android-only page helpers, appended to assets/agent.js by tools/gen_android_agent_js.js.
function typeFn(id, text, submit) {
  const el = document.querySelector('[data-cairn-id="' + id + '"]');
  if (!el) return false;
  el.scrollIntoView({ block: 'center', behavior: 'instant' }); el.focus();
  if (el.isContentEditable) { document.execCommand('selectAll'); document.execCommand('insertText', false, text); }
  else setVal(el, text);
  if (submit) {
    const o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true };
    el.dispatchEvent(new KeyboardEvent('keydown', o)); el.dispatchEvent(new KeyboardEvent('keypress', o)); el.dispatchEvent(new KeyboardEvent('keyup', o));
    if (el.form) { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }
  }
  return true;
}
function setVal(el, text) {
  if (el.tagName === 'SELECT') {
    const t = String(text).toLowerCase();
    const o = [...el.options].find(o => o.text.trim().toLowerCase() === t || o.value.toLowerCase() === t) || [...el.options].find(o => o.text.toLowerCase().includes(t));
    if (!o) return false;
    el.value = o.value;
  } else {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
    el.dispatchEvent(new InputEvent('input', { bubbles: true, data: String(text), inputType: 'insertText' }));
  }
  el.dispatchEvent(new Event('change', { bubbles: true }));
  el.dispatchEvent(new Event('blur', { bubbles: true }));
  return true;
}
function keyFn(key) {
  const el = document.activeElement || document.body;
  const codes = { Enter: 13, Escape: 27, Tab: 9, ArrowDown: 40, ArrowUp: 38, Backspace: 8 };
  const o = { key, code: key, keyCode: codes[key] || 0, which: codes[key] || 0, bubbles: true };
  el.dispatchEvent(new KeyboardEvent('keydown', o)); el.dispatchEvent(new KeyboardEvent('keyup', o));
  if (key === 'Enter' && el.form) { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); }
  return true;
}
/** Programmatic click, used by background sub-agents that have no touch surface. */
function clickElFn(id) {
  const el = document.querySelector('[data-cairn-id="' + id + '"]');
  if (!el) return false;
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(t => el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window })));
  el.click();
  return true;
}
function countLinksFn(src) { const re = new RegExp(src); return [...document.querySelectorAll('a[href]')].filter(a => re.test(a.href)).length; }
function blockedFn() { return /503|robot|captcha|access denied|unusual traffic/i.test(document.title + ' ' + (document.body ? document.body.innerText.slice(0, 400) : '')); }
function metricsFn() { return { w: innerWidth, h: innerHeight, dpr: devicePixelRatio, sh: document.documentElement.scrollHeight, y: scrollY }; }
function hideFixedFn(hide) {
  if (hide) { document.querySelectorAll('body *').forEach(e => { const p = getComputedStyle(e).position; if ((p === 'fixed' || p === 'sticky') && e.id !== '__cairn_overlay') { e.dataset.cairnHid = e.style.visibility || '-'; e.style.visibility = 'hidden'; } }); }
  else document.querySelectorAll('[data-cairn-hid]').forEach(e => { e.style.visibility = e.dataset.cairnHid === '-' ? '' : e.dataset.cairnHid; delete e.dataset.cairnHid; });
  return true;
}

/* ---------- Delivery profile autofill ---------- */
function labelOf(el) {
  const bits = [el.name, el.id, el.placeholder, el.getAttribute('aria-label'), el.getAttribute('autocomplete'), el.getAttribute('data-testid'), el.getAttribute('formcontrolname')];
  if (el.id) { const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) bits.push(l.innerText); }
  const wrap = el.closest('label'); if (wrap) bits.push(wrap.innerText);
  const prev = el.parentElement && el.parentElement.querySelector('label, span, div'); if (prev && prev !== el && (prev.innerText || '').length < 40) bits.push(prev.innerText);
  return bits.filter(Boolean).join(' ').toLowerCase();
}
function fillProfileFn(p) {
  const rules = [
    ['email', /e-?mail/, ['email']],
    ['phone', /phone|mobile|\btel\b|contact.?number/, ['tel', 'tel-national']],
    ['pin', /pin.?code|postal|zip|postcode/, ['postal-code']],
    ['line1', /address.?(line)?.?1|flat|house|building|street|address(?!.*(2|line2|landmark|area))/, ['address-line1', 'street-address']],
    ['line2', /address.?(line)?.?2|area|locality|landmark|colony|sector/, ['address-line2']],
    ['city', /city|town|district/, ['address-level2']],
    ['state', /state|province|region/, ['address-level1']],
    ['country', /country/, ['country', 'country-name']],
    ['first', /first.?name|given.?name|fname/, ['given-name']],
    ['last', /last.?name|surname|family.?name|lname/, ['family-name']],
    ['full', /full.?name|^name$|\bname\b|your name|receiver|recipient/, ['name']],
  ];
  const full = [p.first, p.last].filter(Boolean).join(' ');
  const vis = (el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el); return r.width > 4 && r.height > 4 && s.visibility !== 'hidden' && s.display !== 'none'; };
  const fields = [...document.querySelectorAll('input, select, textarea')].filter(el => vis(el) && !el.disabled && !el.readOnly && !/^(hidden|password|checkbox|radio|submit|button|file|search)$/.test(el.type) && !/otp|captcha|coupon|promo|card|cvv|upi|gift/i.test(labelOf(el)));
  const filled = [], skipped = [];
  for (const el of fields) {
    if (el.value && el.value.trim().length > 1 && el.tagName !== 'SELECT') { skipped.push(labelOf(el).slice(0, 30)); continue; }
    const ac = (el.getAttribute('autocomplete') || '').toLowerCase().split(/\s+/).pop();
    const lab = labelOf(el);
    let key = null;
    for (const [k, re, acs] of rules) { if (acs.includes(ac)) { key = k; break; } }
    if (!key) for (const [k, re] of rules) { if (re.test(lab)) { key = k; break; } }
    if (!key && el.type === 'email') key = 'email';
    if (!key && el.type === 'tel') key = 'phone';
    if (!key) continue;
    let v = key === 'full' ? full : p[key];
    if (key === 'phone' && v) v = String(v).replace(/^\+?91[\s-]?/, '').replace(/\D/g, '').slice(-10);
    if (!v) continue;
    if (setVal(el, v)) filled.push(key);
  }
  return { filled: [...new Set(filled)], skipped: skipped.length, fields: fields.length };
}

/* ---------- Coupons & bank offers ---------- */
function offersFn() {
  const t = (document.body ? document.body.innerText : '').replace(/\s+/g, ' ');
  const lines = [];
  const re = /([^.•|\n]{0,120}?(bank offer|instant discount|cashback|coupon|promo code|use code|no cost emi|% off|flat ₹\s?[\d,]+ off)[^.•|\n]{0,140})/gi;
  let m; while ((m = re.exec(t)) && lines.length < 30) { const s = m[1].trim(); if (s.length > 14 && !lines.includes(s)) lines.push(s); }
  const codes = [...new Set((t.match(/\b(?:code|coupon)[:\s]+([A-Z0-9]{4,16})\b/g) || []).map(x => x.split(/[:\s]+/).pop()))].slice(0, 10);
  const input = [...document.querySelectorAll('input')].find(i => /coupon|promo|voucher|discount.?code|gift/i.test(labelOf(i)));
  if (input) input.setAttribute('data-cairn-coupon', '1');
  return { offers: lines, codes, couponField: !!input };
}
function applyCouponFn(code) {
  const input = document.querySelector('[data-cairn-coupon]') || [...document.querySelectorAll('input')].find(i => /coupon|promo|voucher/i.test(labelOf(i)));
  if (!input) return { ok: false, reason: 'no coupon field' };
  input.scrollIntoView({ block: 'center', behavior: 'instant' }); input.focus(); setVal(input, code);
  let box = input.parentElement, btn = null;
  for (let d = 0; d < 4 && box && !btn; d++, box = box.parentElement) btn = [...box.querySelectorAll('button, [role=button], input[type=submit], a')].find(b => /apply|redeem|check/i.test(b.innerText || b.value || ''));
  if (btn) btn.click();
  return { ok: true, clicked: !!btn };
}

/* ---------- Guard: is this a sensitive page? ---------- */
function guardFn() {
  const pw = !!document.querySelector('input[type=password]');
  const pin = !!document.querySelector('input[autocomplete="one-time-code"], input[name*="otp" i], input[name*="pin" i], input[autocomplete^="cc-"], input[name*="cvv" i], input[name*="card" i]');
  const t = (document.body ? document.body.innerText.slice(0, 8000) : '').toLowerCase();
  const pay = /payment method|pay ₹|pay now|upi id|card number|cvv|net banking|place (your )?order|checkout/.test(t);
  return { sensitive: pw || pin || pay, login: pw, payment: pay };
}

/* ---------- Lasso: what is inside a rectangle (CSS px) ---------- */
function regionFn(x, y, w, h) {
  const seen = new Set(), texts = [], images = [], links = [];
  const step = Math.max(8, Math.min(w, h) / 10);
  for (let yy = y; yy <= y + h; yy += step) for (let xx = x; xx <= x + w; xx += step) {
    for (const el of document.elementsFromPoint(xx, yy).slice(0, 4)) {
      if (seen.has(el)) continue; seen.add(el);
      if (el.tagName === 'IMG' && (el.currentSrc || el.src)) images.push(el.currentSrc || el.src);
      const a = el.closest('a[href]'); if (a && !links.includes(a.href)) links.push(a.href);
      if (el.childElementCount === 0 || el.tagName === 'P' || /^H\d$/.test(el.tagName)) { const s = (el.innerText || el.alt || '').trim(); if (s && s.length < 600 && !texts.includes(s)) texts.push(s); }
    }
  }
  return { text: texts.join('\n').slice(0, 4000), images: [...new Set(images)].slice(0, 4), links: links.slice(0, 6) };
}

/* ---------- Product info (price tracking) ---------- */
function productFn() {
  const out = { title: document.title, url: location.href, price: null, currency: null, inStock: null, image: (document.querySelector('meta[property="og:image"]') || {}).content || '' };
  const walk = (o) => {
    if (!o || typeof o !== 'object') return;
    if (Array.isArray(o)) return o.forEach(walk);
    const t = [].concat(o['@type'] || []).join(' ');
    if (/Product/.test(t)) { out.title = o.name || out.title; if (o.image) out.image = [].concat(o.image)[0]?.url || [].concat(o.image)[0] || out.image; }
    if (/Offer/.test(t) || o.price || o.lowPrice) {
      const p = Number(String(o.price || o.lowPrice || '').replace(/[^\d.]/g, ''));
      if (p && !out.price) { out.price = p; out.currency = o.priceCurrency || null; }
      if (o.availability) out.inStock = !/OutOfStock|SoldOut|Discontinued/i.test(o.availability);
    }
    Object.values(o).forEach(v => { if (v && typeof v === 'object') walk(v); });
  };
  document.querySelectorAll('script[type="application/ld+json"]').forEach(s => { try { walk(JSON.parse(s.textContent)); } catch (e) {} });
  if (!out.price) {
    const meta = document.querySelector('meta[property="product:price:amount"], meta[itemprop=price], [itemprop=price]');
    const v = meta && (meta.content || meta.getAttribute('content') || meta.textContent);
    if (v) out.price = Number(String(v).replace(/[^\d.]/g, '')) || null;
  }
  if (!out.price) {
    const el = document.querySelector('#corePrice_feature_div .a-offscreen, .a-price .a-offscreen, [class*="pdp-price" i], [class*="selling-price" i], [class*="final-price" i]');
    if (el) out.price = Number(el.textContent.replace(/[^\d.]/g, '')) || null;
  }
  if (out.inStock === null) { const t = (document.body ? document.body.innerText.slice(0, 20000) : ''); out.inStock = !/currently unavailable|out of stock|sold out|notify me/i.test(t); }
  out.title = String(out.title || '').trim().slice(0, 200);
  return out;
}

/* ---------- Page translation (text nodes in, translated text out) ---------- */
const __tx = [];
function textNodesFn(max) {
  __tx.length = 0;
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, { acceptNode: (n) => {
    const p = n.parentElement; if (!p || /^(SCRIPT|STYLE|NOSCRIPT|CODE|PRE|TEXTAREA)$/.test(p.tagName)) return NodeFilter.FILTER_REJECT;
    const s = n.nodeValue.trim(); return s.length > 1 && /\p{L}/u.test(s) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT; } });
  let n, total = 0; const out = [];
  while ((n = w.nextNode()) && total < (max || 24000)) { const s = n.nodeValue.trim().slice(0, 600); __tx.push(n); out.push(s); total += s.length; }
  return out;
}
function setTextsFn(pairs) {
  let k = 0;
  for (const [i, s] of pairs) { const n = __tx[i]; if (n && s) { const lead = n.nodeValue.match(/^\s*/)[0], tail = n.nodeValue.match(/\s*$/)[0]; n.nodeValue = lead + s + tail; k++; } }
  document.documentElement.setAttribute('data-cairn-translated', '1');
  return k;
}
function iconsFn() {
  return [...document.querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"], link[rel="apple-touch-icon-precomposed"], link[rel="mask-icon"]')]
    .map(l => ({ href: l.href, rel: l.rel, sizes: l.getAttribute('sizes') || '' })).filter(x => x.href);
}
