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
  function showChip(anchor, items) {
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
    </style><div class="b" style="left:${Math.round(r.left)}px;top:${Math.round(r.bottom + 6)}px"><div class="h">Cairn Passwords</div></div>`;
    const box = sh.querySelector('.b');
    for (const it of items) {
      const b = document.createElement('button');
      b.innerHTML = `<span class="k">${it.icon}</span><span>${it.label}<small>${it.sub || ''}</small></span>`;
      b.addEventListener('mousedown', (e) => { e.preventDefault(); it.run(); removeChip(); });
      box.appendChild(b);
    }
    document.documentElement.appendChild(chip);
  }

  async function onFocus(e) {
    const el = e.target;
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
  document.addEventListener('submit', (e) => captureAndOffer(e.target), true);
  document.addEventListener('click', (e) => {
    const b = e.target.closest?.('button, input[type=submit], [role=button]');
    if (b && /log ?in|sign ?in|continue|next|submit|sign ?up|register|create/i.test(b.innerText || b.value || '')) setTimeout(() => captureAndOffer(b.form || b.closest('form')), 0);
  }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target?.type === 'password') captureAndOffer(e.target.form); }, true);
}
