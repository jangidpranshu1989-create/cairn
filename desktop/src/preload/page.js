// Preload for every tab.
//  - cairn:// internal pages get a privileged bridge (main re-checks the sender origin).
//  - https pages get password autofill / save prompts / strong password suggestions.
const { contextBridge, ipcRenderer } = require('electron');

if (location.protocol === 'cairn:') {
  contextBridge.exposeInMainWorld('cairn', {
    invoke: (ch, ...args) => ipcRenderer.invoke(ch, ...args),
    on: (ch, fn) => { const l = (_e, d) => fn(d); ipcRenderer.on(ch, l); return () => ipcRenderer.removeListener(ch, l); },
    platform: process.platform,
  });
} else if (/^https?:$/.test(location.protocol)) {
  const SHADOW_ID = '__cairn_pw';
  let saved = null; // [{id, username}]
  let chip = null;

  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const pwFields = () => [...document.querySelectorAll('input[type=password]')].filter(visible);
  function userFieldFor(pw) {
    const form = pw.form || pw.closest('form') || document;
    const cands = [...form.querySelectorAll('input:not([type=password]):not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit])')].filter(visible);
    const before = cands.filter(c => c.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING);
    return (before.length ? before : cands).find(c => /user|email|login|mail|phone|mobile|id/i.test(c.name + c.id + c.autocomplete + c.type + c.placeholder)) || before[before.length - 1] || null;
  }
  function setValue(el, v) {
    const proto = Object.getPrototypeOf(el);
    const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
    setter ? setter.call(el, v) : (el.value = v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function removeChip() { chip?.remove(); chip = null; }
  function showChip(anchor, items, title = 'Cairn Passwords') {
    removeChip();
    const r = anchor.getBoundingClientRect();
    chip = document.createElement('div');
    chip.id = SHADOW_ID;
    const sh = chip.attachShadow({ mode: 'closed' });
    sh.innerHTML = `<style>
      .b{position:fixed;z-index:2147483647;min-width:220px;max-width:320px;background:#1D1D1D;color:#EDEDEB;border:1px solid rgba(255,255,255,.12);border-radius:12px;box-shadow:0 18px 40px rgba(0,0,0,.35);font:13px/1.35 system-ui,sans-serif;padding:6px;animation:in .14s ease-out}
      @keyframes in{from{opacity:0;transform:translateY(-4px)}}
      .h{font-size:11px;color:#A1A19D;padding:6px 8px 4px;letter-spacing:.04em;text-transform:uppercase}
      button{all:unset;display:flex;gap:10px;align-items:center;width:calc(100% - 16px);padding:8px;border-radius:8px;cursor:pointer}
      button:hover,button:focus{background:rgba(255,255,255,.07)}
      .k{width:22px;height:22px;border-radius:6px;background:#EDEDEB;color:#141414;display:grid;place-items:center;font-weight:700;font-size:12px;flex:none}
      small{display:block;color:#A1A19D;font-size:11px}
    </style><div class="b" style="left:${Math.round(r.left)}px;top:${Math.round(r.bottom + 6)}px"><div class="h">${title}</div></div>`;
    const box = sh.querySelector('.b');
    for (const it of items) {
      const b = document.createElement('button');
      b.innerHTML = '<span class="k"></span><span><span class="t"></span><small></small></span>';
      b.querySelector('.k').textContent = it.icon; b.querySelector('.t').textContent = it.label; b.querySelector('small').textContent = it.sub || '';
      b.addEventListener('mousedown', (e) => { e.preventDefault(); it.run(); removeChip(); });
      box.appendChild(b);
    }
    document.documentElement.appendChild(chip);
  }

  // ---- Payment cards & addresses ----
  const AC = { 'cc-number': 'cc-number', 'cc-name': 'cc-name', 'cc-exp': 'cc-exp', 'cc-exp-month': 'cc-exp-month', 'cc-exp-year': 'cc-exp-year', name: 'name', 'given-name': 'given-name', 'family-name': 'family-name',
    email: 'email', tel: 'tel', 'tel-national': 'tel', 'street-address': 'line1', 'address-line1': 'line1', 'address-line2': 'line2', 'address-level2': 'city', 'address-level1': 'state', 'postal-code': 'zip', country: 'country', 'country-name': 'country' };
  const sig = (el) => [el.getAttribute('autocomplete'), el.name, el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label'), el.getAttribute('data-testid')].join(' ').toLowerCase();
  function field(el) {
    if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'SELECT')) return null;
    const t = (el.type || 'text').toLowerCase(); if (/^(hidden|submit|button|checkbox|radio|password|file|image|reset|search)$/.test(t)) return null;
    const a = (el.getAttribute('autocomplete') || '').toLowerCase().replace(/^section-\S+\s*/, '').replace(/^(shipping|billing)\s+/, '');
    if (AC[a]) return AC[a];
    const s = sig(el);
    if (/cvc|cvv|csc|security.?code|otp|captcha|coupon|promo/.test(s)) return null;
    if (/card.?num|cc.?num|ccnum|cardnumber|acct.?num|credit.?card|debit.?card/.test(s)) return 'cc-number';
    if (/(card|cc).?(holder|name)|name.?on.?card/.test(s)) return 'cc-name';
    if (/exp\w*.?(month|mm\b)|cc.?month|card.?month/.test(s)) return 'cc-exp-month';
    if (/exp\w*.?(year|yy)|cc.?year|card.?year/.test(s)) return 'cc-exp-year';
    if (/expir|exp.?date|mm.?\/.?yy|valid.?thru/.test(s)) return 'cc-exp';
    if (/e.?mail/.test(s) || t === 'email') return 'email';
    if (/phone|mobile|\btel\b/.test(s) || t === 'tel') return 'tel';
    if (/zip|postal|pin.?code|pincode/.test(s)) return 'zip';
    if (/address.?(line)?.?2|apartment|\bapt\b|suite|landmark|locality/.test(s)) return 'line2';
    if (/address|street|house|flat|line.?1/.test(s)) return 'line1';
    if (/city|town|district/.test(s)) return 'city';
    if (/\bstate\b|province|region/.test(s)) return 'state';
    if (/country/.test(s)) return 'country';
    if (/first.?name|given/.test(s)) return 'given-name';
    if (/last.?name|surname|family/.test(s)) return 'family-name';
    if (/full.?name|your.?name|\bname\b/.test(s)) return 'name';
    return null;
  }
  const afList = (root) => [...(root || document).querySelectorAll('input,select')].map(el => [el, field(el)]).filter(([el, f]) => f && visible(el));
  function kindFor(el) {
    const f = field(el); if (!f) return null; const fs = afList(el.form || document);
    if (f.startsWith('cc-')) return fs.some(x => x[1] === 'cc-number') ? 'cards' : null;
    return fs.some(x => /^(line1|zip|city)$/.test(x[1])) ? 'addresses' : null;
  }
  function putVal(el, v, alts = []) {
    if (v === undefined || v === null || v === '') return;
    if (el.tagName === 'SELECT') {
      const vals = [v, ...alts].map(x => String(x).toLowerCase()).filter(Boolean);
      const o = [...el.options].find(o => vals.includes(o.value.toLowerCase()) || vals.includes(o.text.trim().toLowerCase())) || [...el.options].find(o => o.text.toLowerCase().startsWith(vals[0]));
      if (o) { el.value = o.value; el.dispatchEvent(new Event('change', { bubbles: true })); } return;
    }
    setValue(el, String(v));
  }
  function afFill(kind, d, anchor) {
    const fs = afList(anchor.form || document);
    for (const [el, f] of fs) {
      if (kind === 'cards') {
        const mm = ('0' + (d.expMonth || '')).slice(-2), yyyy = String(d.expYear || ''), yy = yyyy.slice(-2);
        if (f === 'cc-number') putVal(el, d.number); else if (f === 'cc-name') putVal(el, d.name);
        else if (f === 'cc-exp') putVal(el, mm + '/' + yy);
        else if (f === 'cc-exp-month') putVal(el, mm, [String(Number(mm))]);
        else if (f === 'cc-exp-year') putVal(el, el.tagName === 'SELECT' ? yyyy : (el.maxLength > 0 && el.maxLength <= 2 ? yy : yyyy), [yy]);
      } else if (!f.startsWith('cc-')) {
        const parts = String(d.name || '').trim().split(/\s+/);
        const map = { name: d.name, 'given-name': parts[0], 'family-name': parts.slice(1).join(' '), email: d.email, tel: d.phone, line1: d.line1, line2: d.line2, city: d.city, state: d.state, zip: d.zip, country: d.country };
        if (map[f]) putVal(el, map[f]);
      }
    }
  }
  async function afFocus(el) {
    const kind = kindFor(el); if (!kind || location.protocol !== 'https:') return false;
    const saved = await ipcRenderer.invoke('af:query', kind).catch(() => []);
    if (!saved.length) return false;
    showChip(el, saved.map(s => ({ icon: kind === 'cards' ? '▭' : '⌂', label: s.label, sub: s.sub, run: async () => {
      const d = await ipcRenderer.invoke('af:fill', kind, s.id).catch(() => null); if (d) afFill(kind, d, el);
    } })), kind === 'cards' ? 'Cairn Payment methods' : 'Cairn Addresses');
    return true;
  }
  const luhn = (n) => { if (!/^\d{12,19}$/.test(n)) return false; let sum = 0, alt = false; for (let i = n.length - 1; i >= 0; i--) { let d = +n[i]; if (alt) { d *= 2; if (d > 9) d -= 9; } sum += d; alt = !alt; } return sum % 10 === 0; };
  let lastAf = '';
  function afGrab() {
    if (location.protocol !== 'https:') return;
    const v = {}; for (const [el, f] of afList(document)) if (el.value && !v[f]) v[f] = String(el.value).trim();
    const num = (v['cc-number'] || '').replace(/\D/g, '');
    if (luhn(num)) {
      let m = v['cc-exp-month'] || '', y = v['cc-exp-year'] || '';
      if (v['cc-exp']) { const p = v['cc-exp'].split(/[^0-9]+/).filter(Boolean); m = p[0] || m; y = p[1] || y; }
      if (y.length === 2) y = '20' + y;
      const card = { number: num, name: v['cc-name'] || '', expMonth: m ? ('0' + Number(m)).slice(-2) : '', expYear: y };
      const k = 'c' + num + card.expMonth + card.expYear; if (k !== lastAf) { lastAf = k; ipcRenderer.invoke('af:offer', 'cards', card).catch(() => {}); }
      return;
    }
    if (v.line1 && (v.zip || v.city)) {
      const a = { name: v.name || [v['given-name'], v['family-name']].filter(Boolean).join(' '), phone: v.tel || '', email: v.email || '', line1: v.line1, line2: v.line2 || '', city: v.city || '', state: v.state || '', zip: v.zip || '', country: v.country || '' };
      const k = 'a' + a.line1 + a.zip; if (k !== lastAf) { lastAf = k; ipcRenderer.invoke('af:offer', 'addresses', a).catch(() => {}); }
    }
  }

  async function onFocus(e) {
    const el = e.target;
    if (el instanceof HTMLSelectElement || (el instanceof HTMLInputElement && el.type !== 'password' && !pwFields().some(p => userFieldFor(p) === el))) { if (await afFocus(el)) return; }
    if (!(el instanceof HTMLInputElement)) return;
    const isPw = el.type === 'password';
    const pw = isPw ? el : pwFields().find(p => userFieldFor(p) === el);
    if (!pw) return;
    const isNew = /new-password/.test(pw.autocomplete) || pwFields().length >= 2 || /sign ?up|register|create/i.test((pw.form?.innerText || '').slice(0, 400));
    if (saved === null) saved = await ipcRenderer.invoke('pw:query').catch(() => []);
    const items = saved.map(s => ({ icon: '•', label: s.username || '(no username)', sub: 'Fill saved login', run: async () => {
      const c = await ipcRenderer.invoke('pw:fill', s.id); if (!c) return;
      const u = userFieldFor(pw); if (u && c.username) setValue(u, c.username); setValue(pw, c.password);
    } }));
    if (isPw && isNew) items.push({ icon: '✦', label: 'Use a strong password', sub: 'Generated and saved by Cairn', run: async () => {
      const g = await ipcRenderer.invoke('pw:generate'); pwFields().filter(p => !p.value || p === el).forEach(p => setValue(p, g));
    } });
    if (items.length) showChip(el, items);
  }

  function captureAndOffer(form) {
    const pw = (form ? [...form.querySelectorAll('input[type=password]')] : pwFields()).find(p => p.value);
    if (!pw) return;
    const u = userFieldFor(pw);
    ipcRenderer.invoke('pw:offer', u?.value || '', pw.value).catch(() => {});
    saved = null;
  }

  document.addEventListener('focusin', onFocus, true);
  document.addEventListener('mousedown', (e) => { if (chip && !e.composedPath().includes(chip)) removeChip(); }, true);
  addEventListener('scroll', removeChip, { passive: true });
  document.addEventListener('submit', (e) => { captureAndOffer(e.target); afGrab(); }, true);
  document.addEventListener('click', (e) => {
    const b = e.target.closest?.('button, input[type=submit], [role=button]');
    if (b) setTimeout(afGrab, 0);
    if (b && /log ?in|sign ?in|continue|next|submit|sign ?up|register|create/i.test(b.innerText || b.value || '')) setTimeout(() => captureAndOffer(b.form || b.closest('form')), 0);
  }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target?.type === 'password') captureAndOffer(e.target.form); }, true);
}
