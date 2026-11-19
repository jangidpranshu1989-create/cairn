// Scripts injected into web pages (run in an isolated world so pages cannot tamper with them).
const WORLD = 1999;

async function run(wc, fn, ...args) {
  const code = `(${fn.toString()})(...${JSON.stringify(args)})`;
  return wc.executeJavaScriptInIsolatedWorld(WORLD, [{ code }], true);
}

// ---------- Page snapshot for the agent ----------
function snapshotFn(maxText) {
  const vis = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && Number(s.opacity) > 0.05;
  };
  const clean = (t, n = 80) => (t || '').replace(/\s+/g, ' ').trim().slice(0, n);
  const sel = 'a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=combobox],[role=searchbox],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
  document.querySelectorAll('[data-cairn-id]').forEach(e => e.removeAttribute('data-cairn-id'));
  const vh = innerHeight;
  const all = [...document.querySelectorAll(sel)].filter(vis);
  // Prioritise elements in / near the viewport
  all.sort((a, b) => {
    const da = Math.abs(a.getBoundingClientRect().top - vh / 3), db = Math.abs(b.getBoundingClientRect().top - vh / 3);
    return da - db;
  });
  const picked = all.slice(0, 220);
  picked.sort((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
  const lines = [];
  picked.forEach((el, i) => {
    const id = i + 1;
    el.setAttribute('data-cairn-id', String(id));
    const tag = el.tagName.toLowerCase();
    const role = el.getAttribute('role');
    const label = clean(el.getAttribute('aria-label') || el.innerText || el.value || el.getAttribute('title') || el.getAttribute('alt') || el.querySelector?.('img')?.alt || '');
    const r = el.getBoundingClientRect();
    const where = r.bottom < 0 ? ' (above)' : r.top > vh ? ' (below)' : '';
    if (tag === 'input' || tag === 'textarea' || tag === 'select') {
      const type = el.type || tag;
      const ph = clean(el.placeholder || el.getAttribute('aria-label') || el.name || '', 50);
      const sensitive = type === 'password' || /^cc-/.test(el.autocomplete || '') || /card.?num|cvv|cvc|security.?code|\bpin\b|expiry|exp.?date/i.test(`${el.name} ${el.id} ${el.placeholder || ''} ${el.getAttribute('aria-label') || ''}`);
      const val = sensitive ? (el.value ? '••••' : '') : clean(el.value, 40);
      let extra = '';
      if (tag === 'select') extra = ' options=' + JSON.stringify([...el.options].slice(0, 12).map(o => clean(o.text, 30)));
      if (type === 'checkbox' || type === 'radio') extra = el.checked ? ' checked' : ' unchecked';
      lines.push(`[${id}] ${tag} type=${type}${ph ? ` "${ph}"` : ''}${val ? ` value="${val}"` : ''}${extra}${el.disabled ? ' disabled' : ''}${where}`);
    } else if (tag === 'a') {
      let href = el.getAttribute('href') || '';
      try { href = new URL(href, location.href).pathname.slice(0, 60); } catch {}
      lines.push(`[${id}] link "${label}" -> ${href}${where}`);
    } else {
      lines.push(`[${id}] ${role || tag} "${label}"${el.disabled || el.getAttribute('aria-disabled') === 'true' ? ' disabled' : ''}${where}`);
    }
  });
  const text = clean(document.body?.innerText || '', maxText);
  return {
    url: location.href, title: document.title,
    scroll: { y: Math.round(scrollY), max: Math.max(0, document.documentElement.scrollHeight - innerHeight) },
    elements: lines.join('\n'), text,
  };
}

// ---------- Element helpers ----------
function locateFn(id) {
  const el = document.querySelector(`[data-cairn-id="${id}"]`);
  if (!el) return null;
  el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  const r = el.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), text: (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 120), tag: el.tagName.toLowerCase(), type: el.type || '' };
}
function findByTextFn(text) {
  const t = text.toLowerCase().trim();
  const cands = [...document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button],[role=link],[role=menuitem],[role=tab],label,span,div')]
    .filter(e => { const r = e.getBoundingClientRect(); return r.width > 2 && r.height > 2; })
    .map(e => ({ e, s: (e.innerText || e.value || e.getAttribute('aria-label') || '').toLowerCase().replace(/\s+/g, ' ').trim() }))
    .filter(o => o.s && o.s.length < 200 && o.s.includes(t));
  cands.sort((a, b) => (a.s === t ? -1 : 0) - (b.s === t ? -1 : 0) || a.s.length - b.s.length);
  const el = cands[0]?.e;
  if (!el) return null;
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  const r = el.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), text: cands[0].s.slice(0, 120) };
}
function focusFn(id, clear) {
  const el = document.querySelector(`[data-cairn-id="${id}"]`);
  if (!el) return false;
  el.scrollIntoView({ block: 'center', behavior: 'instant' });
  el.focus();
  if (clear) {
    if ('value' in el) { el.select?.(); }
    else if (el.isContentEditable) { const r = document.createRange(); r.selectNodeContents(el); const s = getSelection(); s.removeAllRanges(); s.addRange(r); }
  }
  return true;
}
function selectFn(id, value) {
  const el = document.querySelector(`[data-cairn-id="${id}"]`);
  if (!el || el.tagName !== 'SELECT') return 'not a select';
  const v = String(value).toLowerCase();
  const opt = [...el.options].find(o => o.text.toLowerCase().includes(v) || o.value.toLowerCase() === v);
  if (!opt) return 'option not found';
  el.value = opt.value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return 'selected ' + opt.text;
}
function scrollFn(dir) {
  const h = innerHeight * 0.85;
  if (dir === 'top') scrollTo(0, 0); else if (dir === 'bottom') scrollTo(0, document.documentElement.scrollHeight);
  else scrollBy(0, dir === 'up' ? -h : h);
  return Math.round(scrollY);
}

// ---------- Reader / article extraction ----------
function articleFn() {
  const pick = () => {
    for (const sel of ['#mw-content-text .mw-parser-output', 'article [itemprop=articleBody]', '.article-body', '.post-content', '.entry-content', 'article', '[role=main]', 'main', '#content']) {
      const el = [...document.querySelectorAll(sel)].sort((a, b) => b.innerText.length - a.innerText.length)[0];
      if (el && el.innerText.length > 600) return el;
    }
    let best = document.body, bestScore = 0;
    document.querySelectorAll('div,section').forEach(d => {
      const ps = d.querySelectorAll(':scope > p, :scope > div > p');
      let s = 0; ps.forEach(p => s += p.innerText.length);
      if (s > bestScore) { bestScore = s; best = d; }
    });
    return best;
  };
  const root = pick();
  const parts = [];
  root.querySelectorAll('h1,h2,h3,p,li,blockquote').forEach(n => {
    if (n.closest('nav,footer,aside,header,menu,[role=navigation],[role=menu],[aria-hidden=true],.reflist,.references,.navbox,.infobox,.mw-editsection,.interlanguage-link,.vector-menu,.mw-portlet,.toc,#toc,table,.sidebar,figure,.share,.related,.comments,.ad,[class*=newsletter]')) return;
    if (n.tagName === 'LI' && n.closest('ul,ol')?.querySelectorAll('a').length > 6 && n.innerText.length < 60) return;
    let t = n.innerText.replace(/\[\d+\]|\[citation needed\]|\[edit\]/gi, '').replace(/\s+/g, ' ').trim();
    if (t.length < 2) return;
    if (/^H[1-3]$/.test(n.tagName)) parts.push('\n## ' + t + '\n'); else if (t.length > 30 || n.tagName === 'LI') parts.push(t);
  });
  const h1 = document.querySelector('h1')?.innerText?.trim();
  return { title: h1 || document.title, url: location.href, lang: document.documentElement.lang || '', text: parts.join('\n').slice(0, 60000), byline: document.querySelector('[rel=author], .author, .byline')?.innerText?.trim()?.slice(0, 100) || '' };
}

// ---------- Visual agent overlay (cursor + glow) ----------
function overlayFn(cmd, x, y, label) {
  let root = document.getElementById('__cairn_overlay');
  if (!root) {
    root = document.createElement('div');
    root.id = '__cairn_overlay';
    root.attachShadow({ mode: 'open' }).innerHTML = `<style>
      :host{all:initial}
      .glow{position:fixed;inset:0;pointer-events:none;z-index:2147483646;border-radius:0;box-shadow:inset 0 0 0 2px rgba(130,169,217,.7),inset 0 0 36px 2px rgba(130,169,217,.22);animation:breathe 2.6s ease-in-out infinite}
      @keyframes breathe{50%{box-shadow:inset 0 0 0 2px rgba(130,169,217,.95),inset 0 0 64px 8px rgba(130,169,217,.3)}}
      .cur{position:fixed;left:0;top:0;z-index:2147483647;pointer-events:none;transition:transform .55s cubic-bezier(.22,1,.36,1);will-change:transform}
      .dot{width:18px;height:18px;border-radius:50%;background:#82A9D9;border:2px solid #fff;box-shadow:0 4px 14px rgba(0,0,0,.35);transform:translate(-50%,-50%)}
      .tag{position:absolute;left:14px;top:10px;white-space:nowrap;font:600 12px/1 system-ui,sans-serif;background:#1D1D1D;color:#EDEDEB;padding:6px 9px;border-radius:8px;box-shadow:0 6px 18px rgba(0,0,0,.3)}
      .ring{position:fixed;width:16px;height:16px;border-radius:50%;border:2px solid #82A9D9;transform:translate(-50%,-50%);pointer-events:none;z-index:2147483647;animation:ping .6s ease-out forwards}
      @keyframes ping{to{width:64px;height:64px;opacity:0}}
    </style><div class="glow"></div><div class="cur"><div class="dot"></div><div class="tag"></div></div>`;
    document.documentElement.appendChild(root);
  }
  const sh = root.shadowRoot;
  if (cmd === 'remove') { root.remove(); return; }
  if (cmd === 'move' || cmd === 'click') {
    sh.querySelector('.cur').style.transform = `translate(${x}px, ${y}px)`;
    sh.querySelector('.tag').textContent = label || '';
    if (cmd === 'click') {
      const r = document.createElement('div'); r.className = 'ring'; r.style.left = x + 'px'; r.style.top = y + 'px';
      sh.appendChild(r); setTimeout(() => r.remove(), 700);
    }
  }
}

// ---------- Login / checkout detection ----------
function pageStateFn() {
  const t = (document.body?.innerText || '').slice(0, 20000).toLowerCase();
  return {
    hasPassword: !!document.querySelector('input[type=password]'),
    captcha: !!document.querySelector('iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="turnstile"], #captchacharacters, [class*="captcha" i]') || /enter the characters you see|verify you are human|i'm not a robot/.test(t),
    otp: /\botp\b|one[- ]time password|verification code/.test(t) && !!document.querySelector('input[autocomplete="one-time-code"], input[maxlength="6"], input[name*="otp" i]'),
    payment: /payment method|pay ₹|pay now|upi id|card number|cvv|net banking|place (your )?order/.test(t),
  };
}

module.exports = {
  WORLD, run,
  snapshot: (wc, maxText = 3500) => run(wc, snapshotFn, maxText),
  locate: (wc, id) => run(wc, locateFn, id),
  findByText: (wc, text) => run(wc, findByTextFn, text),
  focus: (wc, id, clear) => run(wc, focusFn, id, clear),
  select: (wc, id, v) => run(wc, selectFn, id, v),
  scroll: (wc, dir) => run(wc, scrollFn, dir),
  article: (wc) => run(wc, articleFn),
  overlay: (wc, cmd, x, y, label) => run(wc, overlayFn, cmd, x, y, label).catch(() => {}),
  pageState: (wc) => run(wc, pageStateFn),
};
