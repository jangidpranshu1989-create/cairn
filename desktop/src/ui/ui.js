/* Cairn browser chrome */
(() => {
  const S = window.cairn;
  const $ = (s) => document.querySelector(s);
  const h = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
  const icon = (id, size = 14) => `<svg class="ic" width="${size}" height="${size}"><use href="#i-${id}"/></svg>`;
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const host = (u) => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };

  const platform = new URLSearchParams(location.search).get('platform') || S.platform;
  document.body.classList.add(platform === 'darwin' ? 'mac' : platform === 'win32' ? 'win' : 'linux');

  let tabs = [], active = null, settings = {}, agentBusy = false, includePage = true;

  // Image fallbacks (inline handlers are blocked by CSP)
  document.addEventListener('error', (e) => {
    const im = e.target; if (im.tagName !== 'IMG') return;
    if (im.dataset.fb) { const sp = h('span', 'fav ph', esc(im.dataset.fb)); im.replaceWith(sp); }
    else if (im.dataset.rmparent) im.parentElement?.remove();
    else im.style.visibility = 'hidden';
  }, true);

  // ---------- Theme ----------
  const setTheme = (t) => document.documentElement.dataset.theme = t;
  S.invoke('ui:theme').then(setTheme); S.on('theme', setTheme);
  S.invoke('settings:get').then(s => { settings = s; applyPanelWidth(); });
  S.on('settings:changed', s => { settings = { ...settings, ...s }; refreshModelTag(); });
  S.on('fullscreen', f => document.body.classList.toggle('fullscreen', f));

  // ---------- Page view rect sync ----------
  const page = $('#page');
  const syncRect = () => { const r = page.getBoundingClientRect(); S.invoke('view:rect', { x: r.left, y: r.top, width: r.width, height: r.height }); };
  new ResizeObserver(syncRect).observe(page);
  window.addEventListener('resize', syncRect);

  let freezeDepth = 0;
  async function freeze() {
    if (freezeDepth++ > 0) return;
    const img = await S.invoke('view:freeze');
    if (img) { $('#freeze').src = img; $('#freeze').classList.add('show'); }
  }
  function unfreeze() {
    if (freezeDepth === 0 || --freezeDepth > 0) return;
    $('#freeze').classList.remove('show');
    S.invoke('view:unfreeze');
  }

  // ---------- Tabs ----------
  const tabsEl = $('#tabs');
  function favicon(t) {
    if (t.loading) return '<span class="spin"></span>';
    if (t.url.startsWith('cairn://')) return `<span class="fav" style="color:var(--accent)"><svg width="16" height="16"><use href="#i-logo"/></svg></span>`;
    if (t.favicon) return `<img class="fav" src="${esc(t.favicon)}" alt="" data-fb="${esc((host(t.url)[0] || '•').toUpperCase())}">`;
    return `<span class="fav ph">${esc((host(t.url)[0] || '•').toUpperCase())}</span>`;
  }
  function renderTabs() {
    const existing = new Map([...tabsEl.children].map(c => [Number(c.dataset.id), c]));
    tabsEl.innerHTML = '';
    for (const t of tabs) {
      let el = existing.get(t.id);
      if (!el) { el = h('div', 'tab'); el.dataset.id = t.id; el.setAttribute('role', 'tab'); el.draggable = true; }
      el.className = 'tab' + (t.active ? ' active' : '');
      el.title = `${t.title}\n${t.url}`;
      const title = t.url === 'cairn://newtab' || t.url === 'about:blank' ? 'New Tab' : t.title || t.url;
      el.innerHTML = `${favicon(t)}<span class="t">${esc(title)}</span>${t.agent && agentBusy ? '<span class="agentdot" title="Cairn is working here"></span>' : ''}${t.audible ? `<span class="aud">${icon('speak', 13)}</span>` : ''}<button class="x" title="Close tab">${icon('x', 12)}</button>`;
      tabsEl.appendChild(el);
    }
  }
  tabsEl.addEventListener('mousedown', (e) => {
    const el = e.target.closest('.tab'); if (!el) return;
    const id = Number(el.dataset.id);
    if (e.button === 1) { e.preventDefault(); S.invoke('tabs:close', id); return; }
    if (e.target.closest('.x')) return;
    S.invoke('tabs:activate', id);
  });
  tabsEl.addEventListener('click', (e) => { const x = e.target.closest('.x'); if (x) S.invoke('tabs:close', Number(x.closest('.tab').dataset.id)); });
  tabsEl.addEventListener('dblclick', (e) => { if (e.target === tabsEl) S.invoke('tabs:new'); });
  let dragId = null;
  tabsEl.addEventListener('dragstart', (e) => { dragId = Number(e.target.closest('.tab')?.dataset.id); });
  tabsEl.addEventListener('dragover', (e) => e.preventDefault());
  tabsEl.addEventListener('drop', (e) => {
    const t = e.target.closest('.tab'); if (!t || dragId == null) return;
    S.invoke('tabs:move', dragId, [...tabsEl.children].indexOf(t)); dragId = null;
  });
  $('#newtab').onclick = () => S.invoke('tabs:new');

  S.on('tabs:update', (list) => {
    tabs = list; active = list.find(t => t.active);
    renderTabs(); renderToolbar(); if (typeof showPerm === 'function') showPerm();
  });
  S.invoke('tabs:list').then(l => { tabs = l; active = l.find(t => t.active); renderTabs(); renderToolbar(); });

  // ---------- Toolbar / omnibox ----------
  const url = $('#url'), omni = $('#omni');
  let editing = false;
  function renderToolbar() {
    if (!active) return;
    $('#back').disabled = !active.canBack; $('#fwd').disabled = !active.canFwd;
    $('#reloadIc').innerHTML = `<use href="#i-${active.loading ? 'x' : 'reload'}"/>`;
    $('#reload').title = active.loading ? 'Stop' : 'Reload';
    const internal = active.url.startsWith('cairn://') || active.url === 'about:blank';
    if (!editing) url.value = internal ? '' : active.url;
    const secure = active.url.startsWith('https://');
    $('#lockIc').innerHTML = `<use href="#i-${internal ? 'search' : secure ? 'lock' : 'info'}"/>`;
    $('#lockIc').classList.toggle('secure', secure);
    $('#lockIc').parentElement.title = secure ? 'Connection is secure' : '';
    S.invoke('bookmarks:has', active.url).then(b => $('#star').classList.toggle('on', b));
    $('#star').style.display = internal ? 'none' : '';
    $('#shieldCount').textContent = active.blocked || 0;
    $('#shieldCount').classList.toggle('hidden', !active.blocked);
    $('#ctxLabel').textContent = internal ? 'No page' : host(active.url) || 'This page';
  }
  $('#back').onclick = () => S.invoke('nav:back');
  $('#fwd').onclick = () => S.invoke('nav:forward');
  $('#reload').onclick = (e) => active?.loading ? S.invoke('nav:stop') : S.invoke('nav:reload', e.shiftKey);
  $('#star').onclick = toggleBookmark;
  async function toggleBookmark() { const on = await S.invoke('bookmarks:toggle'); $('#star').classList.toggle('on', on); toast(on ? 'Bookmarked' : 'Bookmark removed'); }
  S.on('bookmark:toggle', toggleBookmark);
  S.on('shield', ({ blocked }) => { $('#shieldCount').textContent = blocked; $('#shieldCount').classList.remove('hidden'); });
  $('#shieldBtn').onclick = () => toast(`Cairn Shield blocked ${active?.blocked || 0} ads & trackers on this page`);

  // Omnibox suggestions
  const pop = $('#omniPop');
  let sugItems = [], sel = 0, popOpen = false;
  const looksLikeTask = (q) => /\b(find|buy|cheapest|compare|book|order|summar|read|sasta|kharid|dhundh|dhoondh|dikhao|batao|sunao|suna|padh|karo|kar do|banao|bana do|mere liye|chahiye|chaiye)\b/i.test(q) || /[\u0900-\u097F]/.test(q);
  async function openPop() { if (!popOpen) { popOpen = true; await freeze(); } positionPop(); pop.classList.add('show'); }
  function closePop() { if (!popOpen) return; popOpen = false; pop.classList.remove('show'); unfreeze(); }
  function positionPop() { const r = omni.getBoundingClientRect(); pop.style.left = r.left + 'px'; pop.style.top = (r.bottom + 6) + 'px'; pop.style.width = r.width + 'px'; }
  async function updateSuggestions() {
    const q = url.value.trim();
    if (!q) { closePop(); return; }
    const hist = await S.invoke('suggest', q);
    const task = looksLikeTask(q);
    const ask = { type: 'ask', title: `Ask Cairn: ${q}`, q };
    const search = { type: 'search', title: q, q };
    sugItems = task ? [ask, search, ...hist] : [search, ask, ...hist];
    sel = 0; renderPop(); openPop();
  }
  function renderPop() {
    pop.innerHTML = '';
    sugItems.forEach((s, i) => {
      const o = h('div', 'opt' + (i === sel ? ' sel' : '') + (s.type === 'ask' ? ' askopt' : ''));
      const ic = s.type === 'ask' ? 'spark' : s.type === 'search' ? 'search' : s.type === 'bookmark' ? 'star' : 'history';
      o.innerHTML = `<span class="oi">${icon(ic)}</span><span class="ot">${esc(s.title)}</span>${s.url ? `<span class="ou">${esc(s.url.replace(/^https?:\/\/(www\.)?/, ''))}</span>` : ''}${i === 0 ? '<kbd>Enter</kbd>' : s.type === 'ask' ? '<kbd>Tab</kbd>' : ''}`;
      o.onmousedown = (e) => { e.preventDefault(); pick(s); };
      pop.appendChild(o);
    });
  }
  function pick(s) {
    closePop(); editing = false; url.blur();
    if (s.type === 'ask') { runAgent(s.q); return; }
    S.invoke('nav:go', s.url || s.q);
  }
  url.addEventListener('focus', () => { editing = true; omni.classList.add('focus'); setTimeout(() => url.select(), 0); });
  url.addEventListener('blur', () => { editing = false; omni.classList.remove('focus'); setTimeout(closePop, 120); renderToolbar(); });
  url.addEventListener('input', updateSuggestions);
  url.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' && popOpen) { e.preventDefault(); sel = Math.min(sel + 1, sugItems.length - 1); renderPop(); }
    else if (e.key === 'ArrowUp' && popOpen) { e.preventDefault(); sel = Math.max(sel - 1, 0); renderPop(); }
    else if (e.key === 'Tab' && url.value.trim()) { e.preventDefault(); pick({ type: 'ask', q: url.value.trim() }); }
    else if (e.key === 'Enter') { e.preventDefault(); const q = url.value.trim(); if (!q) return; pick(popOpen && sugItems[sel] ? sugItems[sel] : { type: 'search', q }); }
    else if (e.key === 'Escape') { closePop(); url.blur(); S.invoke('view:focus'); }
  });
  $('#askBtn').onmousedown = (e) => { e.preventDefault(); const q = url.value.trim(); if (q && editing) pick({ type: 'ask', q }); else togglePanel(true); };
  S.on('focus:omnibox', () => url.focus());

  // ---------- Generic popovers ----------
  let openPopEl = null;
  async function togglePopover(el, anchor, render) {
    if (openPopEl === el) { closePopover(); return; }
    closePopover();
    if (render) await render();
    await freeze();
    openPopEl = el;
    const r = anchor.getBoundingClientRect();
    el.classList.add('show');
    const w = el.offsetWidth;
    el.style.left = Math.max(8, Math.min(innerWidth - w - 8, r.right - w)) + 'px';
    el.style.top = (r.bottom + 6) + 'px';
  }
  function closePopover() { if (!openPopEl) return; openPopEl.classList.remove('show'); openPopEl = null; unfreeze(); }
  document.addEventListener('mousedown', (e) => { if (openPopEl && !openPopEl.contains(e.target) && !e.target.closest('#toolsBtn,#dlBtn')) closePopover(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePopover(); });

  $('#toolsBtn').onclick = () => togglePopover($('#toolsPop'), $('#toolsBtn'));
  $('#toolsPop').addEventListener('click', (e) => { const o = e.target.closest('[data-tool]'); if (!o) return; closePopover(); runTool(o.dataset.tool); });
  const busyTools = new Set();
  function runTool(kind) {
    if (['summary', 'read'].includes(kind)) togglePanel(true);
    S.invoke('page:tool', kind);
  }
  S.on('tool:busy', ({ kind, busy }) => {
    busy ? busyTools.add(kind) : busyTools.delete(kind);
    $('#toolsBtn').classList.toggle('on', busyTools.size > 0);
    const labels = { summary: 'Summarizing page…', mindmap: 'Building mind map…', longshot: 'Capturing full page…', pdf: 'Creating PDF…', readerpdf: 'Creating article PDF…', read: 'Preparing to read…' };
    if (busy && labels[kind]) toast(labels[kind], { ms: 2500 });
  });

  // Downloads
  let dlList = [];
  async function renderDownloads() {
    dlList = await S.invoke('downloads:list');
    const el = $('#dlPop'); el.innerHTML = '<div class="mh">Downloads</div>';
    if (!dlList.length) el.innerHTML += '<div class="opt" style="color:var(--muted)">No downloads yet</div>';
    for (const d of dlList.slice(0, 8)) {
      const row = h('div', 'dl');
      const pct = d.total ? Math.round(d.received / d.total * 100) : 0;
      row.innerHTML = `<span class="oi" style="width:28px;height:28px;border-radius:8px;display:grid;place-items:center;background:var(--surface-2)">${icon('doc')}</span>
        <div class="dn"><div>${esc(d.filename)}</div>${d.state === 'progressing' ? `<progress max="100" value="${pct}"></progress>` : `<small>${d.state === 'completed' ? fmtBytes(d.total || d.received) : esc(d.state)}</small>`}</div>
        ${d.state === 'completed' ? `<button class="tb" data-a="phone" title="Send to phone">${icon('phone')}</button><button class="tb" data-a="reveal" title="Show in folder">${icon('folder')}</button>` : d.state === 'progressing' ? `<button class="tb" data-a="cancel" title="Cancel">${icon('x')}</button>` : ''}`;
      row.onclick = (e) => {
        const a = e.target.closest('[data-a]')?.dataset.a;
        if (a === 'reveal') S.invoke('file:reveal', d.path);
        else if (a === 'cancel') S.invoke('downloads:cancel', d.id);
        else if (a === 'phone') sendToPhone(d.path);
        else if (d.state === 'completed') S.invoke('file:open', d.path);
      };
      el.appendChild(row);
    }
    el.appendChild(h('div', 'sep'));
    const all = h('div', 'opt', `<span class="oi">${icon('folder')}</span><span class="ot">Show all downloads</span>`);
    all.onclick = () => { closePopover(); S.invoke('tabs:new', 'cairn://downloads'); };
    el.appendChild(all);
  }
  const fmtBytes = (b) => !b ? '' : b < 1024 ? b + ' B' : b < 1048576 ? (b / 1024).toFixed(0) + ' KB' : (b / 1048576).toFixed(1) + ' MB';
  $('#dlBtn').onclick = () => togglePopover($('#dlPop'), $('#dlBtn'), renderDownloads);
  S.on('downloads:update', (list) => {
    const live = list.filter(d => d.state === 'progressing');
    const ring = $('#dlRing');
    ring.classList.toggle('hidden', !live.length);
    if (live.length) {
      const tot = live.reduce((a, d) => a + (d.total || 0), 0), rec = live.reduce((a, d) => a + d.received, 0);
      ring.querySelector('circle').setAttribute('stroke-dashoffset', String(69 - 69 * (tot ? rec / tot : 0.1)));
    }
    if (openPopEl === $('#dlPop')) renderDownloads();
  });

  $('#menuBtn').onclick = () => S.invoke('app:menu');
  $('#logoBtn').onclick = () => S.invoke('app:menu');
  $('#avatar').onclick = () => S.invoke('tabs:new', 'cairn://account');
  function renderSession(s) {
    const a = $('#avatar');
    if (s?.signedIn) { a.textContent = (s.user?.name || s.user?.email || '?').trim()[0].toUpperCase(); a.classList.add('in'); a.title = `Signed in as ${s.user?.email}`; }
    else { a.innerHTML = icon('key', 13); a.classList.remove('in'); a.title = 'Sign in to Cairn'; }
  }
  S.invoke('cloud:session').then(renderSession); S.on('cloud:session', renderSession);

  // Find in page
  const findBar = $('#findBar'), findInput = $('#findInput');
  S.on('find:open', () => { findBar.classList.add('show'); findInput.focus(); findInput.select(); });
  findInput.addEventListener('input', () => S.invoke('find:start', findInput.value, { findNext: false }));
  findInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') S.invoke('find:start', findInput.value, { forward: !e.shiftKey, findNext: true });
    if (e.key === 'Escape') closeFind();
  });
  $('#findNext').onclick = () => S.invoke('find:start', findInput.value, { forward: true, findNext: true });
  $('#findPrev').onclick = () => S.invoke('find:start', findInput.value, { forward: false, findNext: true });
  $('#findClose').onclick = closeFind;
  function closeFind() { findBar.classList.remove('show'); S.invoke('find:stop'); S.invoke('view:focus'); }
  S.on('find:result', (r) => { if (r.finalUpdate) $('#findCount').textContent = r.matches ? `${r.activeMatchOrdinal} of ${r.matches}` : 'No results'; });
  S.on('zoom', async (d) => { const z = await S.invoke('zoom', d); toast(`Zoom ${Math.round(z * 100)}%`, { ms: 1200 }); });

  // Site permission bar (in-app, remembered per site)
  let permQ = [];
  function curPerm() { return permQ.find(p => !p.tabId || p.tabId === active?.id); }
  function showPerm() { const p = curPerm(); $('#permBar').classList.toggle('show', !!p); if (p) $('#permText').textContent = p.text; }
  S.on('perm:prompt', (p) => { permQ.push(p); showPerm(); });
  S.on('perm:close', (id) => { permQ = permQ.filter(p => p.id !== id); showPerm(); });
  $('#permBar').addEventListener('click', (e) => {
    const a = e.target.closest('[data-perm]')?.dataset.perm; const p = curPerm(); if (!a || !p) return;
    S.invoke('perm:decide', p.id, a !== 'block', a !== 'once'); permQ = permQ.filter(x => x !== p); showPerm();
  });

  // Password save bar
  let pwPrompt = null;
  S.on('pw:prompt', (p) => {
    pwPrompt = p;
    $('#pwText').innerHTML = p.text ? p.text : `${p.update ? 'Update' : 'Save'} password for <b>${esc(p.username || 'this account')}</b> on ${esc(host(p.origin))}?`;
    $('#pwBar [data-pw=never]').style.display = p.text ? 'none' : '';
    $('#pwBar').classList.add('show');
  });
  $('#pwBar').addEventListener('click', (e) => {
    const a = e.target.closest('[data-pw]')?.dataset.pw; if (!a || !pwPrompt) return;
    S.invoke('pw:decide', pwPrompt.id, a); $('#pwBar').classList.remove('show');
    if (a === 'save') toast(pwPrompt.kind === 'cards' ? 'Card saved to Cairn' : pwPrompt.kind === 'addresses' ? 'Address saved to Cairn' : 'Password saved to Cairn');
    pwPrompt = null;
  });

  // Toasts
  function toast(text, { file, image, error, ms = 4500 } = {}) {
    const t = h('div', 'toast' + (error ? ' err' : ''));
    t.innerHTML = `<span>${esc(text)}</span>`;
    if (file) {
      const o = h('button', '', 'Open'); o.onclick = () => S.invoke('file:open', file);
      const f = h('button', '', 'Show'); f.onclick = () => S.invoke('file:reveal', file);
      const p = h('button', '', 'Send to phone'); p.onclick = () => sendToPhone(file);
      t.append(o, f, p); ms = 8000;
    }
    const c = h('button', '', '✕'); c.onclick = () => t.remove(); t.append(c);
    $('#toasts').appendChild(t);
    setTimeout(() => t.remove(), ms);
  }
  S.on('toast', (t) => toast(t.text, t));
  async function sendToPhone(file) {
    try { const r = await S.invoke('file:toPhone', file, 'Cairn'); toast(r.deliveredLive ? 'Sent to your phone' : 'Queued — your phone will receive it when online'); }
    catch (e) { toast(cleanErr(e), { error: true }); }
  }
  const cleanErr = (e) => String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');

  // ---------- Assistant panel ----------
  function applyPanelWidth() { $('#panel').style.width = (settings.panelWidth || 420) + 'px'; }
  function togglePanel(force) {
    const open = force ?? !document.body.classList.contains('panel-open');
    document.body.classList.toggle('panel-open', open);
    $('#assistantBtn').classList.toggle('open', open);
    if (open) setTimeout(() => $('#input').focus(), 50);
    let n = 0; const iv = setInterval(() => { syncRect(); if (++n > 18) clearInterval(iv); }, 16);
  }
  $('#assistantBtn').onclick = () => togglePanel();
  $('#closePanel').onclick = () => togglePanel(false);
  S.on('agent:toggle', () => togglePanel());
  // Resizer
  $('#resizer').addEventListener('mousedown', (e) => {
    e.preventDefault(); document.body.classList.add('resizing');
    const startX = e.clientX, startW = $('#panel').offsetWidth;
    const mm = (ev) => { const w = Math.max(320, Math.min(720, startW - (ev.clientX - startX))); $('#panel').style.width = w + 'px'; syncRect(); };
    const mu = () => { document.body.classList.remove('resizing'); removeEventListener('mousemove', mm); removeEventListener('mouseup', mu); S.invoke('settings:set', { panelWidth: $('#panel').offsetWidth }); };
    addEventListener('mousemove', mm); addEventListener('mouseup', mu);
  });

  const thread = $('#thread'), input = $('#input'), send = $('#send');
  const SUGGESTIONS = [
    { i: 'cart', ag: true, t: 'Find the best price for an iPhone 16 128GB', s: 'Compares trusted stores side by side' },
    { i: 'speak', ag: true, t: 'Read me the Wikipedia article on the Taj Mahal', s: 'Fetched in the background, read aloud' },
    { i: 'spark', t: 'Summarize this page', s: 'Key points in seconds' },
    { i: 'map', t: 'Make a mind map of this page', s: 'Visual map you can export' },
    { i: 'phone', ag: true, t: 'Save this article as a PDF to my phone', s: 'Delivered to your paired Android device' },
  ];
  function renderEmpty() {
    thread.innerHTML = '';
    const e = h('div', 'empty');
    e.innerHTML = `<h2>What can I do for you?</h2><p>Ask about this page, or hand me a task. I can browse, compare prices, fill a cart, read aloud and send files to your phone — in any language.</p>`;
    const g = h('div', 'sugs');
    for (const s of SUGGESTIONS) {
      const b = h('button', 'sug' + (s.ag ? ' ag' : ''), `<span class="si">${icon(s.i)}</span><span>${esc(s.t)}<small>${esc(s.s)}</small></span>`);
      b.onclick = () => runAgent(s.t);
      g.appendChild(b);
    }
    e.appendChild(g); thread.appendChild(e);
  }
  renderEmpty();
  $('#newChat').onclick = () => { if (agentBusy) S.invoke('agent:stop'); S.invoke('agent:reset'); stopSpeaking(); renderEmpty(); };
  $('#ctxChip').onclick = () => { includePage = !includePage; $('#ctxChip').classList.toggle('on', includePage); };
  $('#ctxChip').classList.add('on');

  input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = Math.min(160, input.scrollHeight) + 'px'; send.disabled = !input.value.trim() && !agentBusy; });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); } });
  send.onclick = () => agentBusy ? S.invoke('agent:stop') : submit();
  function submit() { const t = input.value.trim(); if (!t || agentBusy) return; input.value = ''; input.style.height = 'auto'; runAgent(t); }

  let cur = null; // current bot turn { root, steps, stepsList, body }
  function runAgent(text) {
    togglePanel(true);
    if (agentBusy) { toast('Cairn is still working — press stop first'); return; }
    if (thread.querySelector('.empty')) thread.innerHTML = '';
    const u = h('div', 'msg user'); u.appendChild(h('div', 'bubble')).textContent = text; thread.appendChild(u);
    cur = null; ensureTurn();
    S.invoke('agent:run', text);
    scrollDown();
  }
  S.on('agent:prefill', ({ text, run }) => { if (run) runAgent(text); else { togglePanel(true); input.value = text; input.focus(); } });

  function ensureTurn() {
    if (cur) return cur;
    const root = h('div', 'msg bot'); thread.appendChild(root);
    cur = { root, steps: null, list: null, count: 0, body: null };
    return cur;
  }
  const STEP_ICONS = { search: 'search', shop: 'cart', store: 'cart', nav: 'globe', read: 'eye', click: 'click', type: 'type', scroll: 'down', speak: 'speak', sum: 'spark', map: 'map', pdf: 'doc', shot: 'shot', down: 'down', phone: 'phone', lock: 'lock', ok: 'check', stop: 'x', ask: 'info', warn: 'warn' };
  function addStep(ev) {
    const t = ensureTurn();
    if (!t.steps) {
      t.steps = h('details', 'steps'); t.steps.open = true;
      t.steps.innerHTML = `<summary><span class="live"><span class="orb"></span><span class="lbl shimmer">Working…</span></span><svg class="ic chev" width="14" height="14"><use href="#i-fwd" transform="rotate(90 12 12)"/></svg></summary><ol></ol>`;
      t.root.prepend(t.steps); t.list = t.steps.querySelector('ol');
    }
    t.count++;
    const li = h('li', ev.icon === 'warn' ? 'warn' : '', `<span class="sic">${icon(STEP_ICONS[ev.icon] || 'spark', 11)}</span><span>${esc(ev.text)}</span>`);
    t.list.appendChild(li); t.list.scrollTop = t.list.scrollHeight;
    t.steps.querySelector('.lbl').textContent = ev.text.length > 46 ? ev.text.slice(0, 46) + '…' : ev.text;
    scrollDown();
  }
  function finishSteps() {
    if (!cur?.steps) return;
    cur.steps.classList.add('done'); cur.steps.open = false;
    cur.steps.querySelector('.orb').innerHTML = '';
    const l = cur.steps.querySelector('.lbl'); l.classList.remove('shimmer'); l.textContent = `Completed ${cur.count} step${cur.count === 1 ? '' : 's'}`;
  }
  const scrollDown = () => requestAnimationFrame(() => thread.scrollTop = thread.scrollHeight);

  const STORE_COLORS = { amazon: '#FF9900', flipkart: '#2874F0', croma: '#12A89D', reliance: '#E42529', vijaysales: '#D7282F', jiomart: '#0078AD', tatacliq: '#A8184E', bestbuy: '#0046BE', walmart: '#0071CE', target: '#CC0000', ebay: '#E53238', argos: '#D42114', currys: '#4C12A1', johnlewis: '#000' };
  const money = (n, cur = '₹') => cur + Number(n).toLocaleString('en-IN');
  function productCards(ev) {
    const t = ensureTurn();
    const wrap = h('div', 'products');
    const curSym = settings.shoppingCountry === 'US' ? '$' : settings.shoppingCountry === 'GB' ? '£' : '₹';
    ev.items.forEach((p, i) => {
      const c = h('div', 'pcard' + (p.best ? ' best' : ''));
      c.style.animationDelay = (i * 50) + 'ms';
      const tc = p.trust >= 80 ? 'hi' : p.trust >= 60 ? 'mid' : 'lo';
      c.innerHTML = `${p.best ? '<span class="ribbon">BEST PICK</span>' : ''}
        <div class="store"><span class="sl" style="background:${STORE_COLORS[p.storeId] || '#555'}">${esc(p.store[0])}</span>${esc(p.store)}</div>
        ${p.image ? `<div class="img"><img src="${esc(p.image)}" alt="" loading="lazy" data-rmparent="1"></div>` : ''}
        <div class="pt" title="${esc(p.title)}">${esc(p.title)}</div>
        <div class="price">${money(p.price, curSym)}${p.mrp ? `<s>${money(p.mrp, curSym)}</s>` : ''}${p.discount ? `<span class="off">${p.discount}% off</span>` : ''}</div>
        <div class="meta"><span class="trust ${tc}">${icon('shield', 10)} Trust ${p.trust}</span>${p.rating ? `<span>★ ${p.rating}</span>` : ''}${p.reviews ? `<span>${Number(p.reviews).toLocaleString('en-IN')} reviews</span>` : ''}</div>
        ${p.flags?.length ? `<div class="flags">⚠ ${esc(p.flags[0])}</div>` : ''}
        <div class="acts"><button class="btn" data-a="open">${icon('ext', 12)} Open</button><button class="btn primary" data-a="cart">${icon('cart', 12)} Add to cart</button></div>`;
      c.onclick = (e) => {
        const a = e.target.closest('[data-a]')?.dataset.a;
        if (a === 'open') S.invoke('tabs:new', p.url);
        if (a === 'cart') runAgent(`Add this to my cart: ${p.title} from ${p.store} — ${p.url}`);
      };
      wrap.appendChild(c);
    });
    if (ev.headline) { const hd = h('div', 'md', md(`**${ev.headline}**`)); t.root.appendChild(hd); }
    t.root.appendChild(wrap);
    scrollDown();
  }

  function summaryCard(ev) {
    const t = ev.standalone ? (cur = null, ensureTurn()) : ensureTurn();
    const c = h('div', 'card');
    c.innerHTML = `<div class="ch">${icon('spark')} Summary · ${esc(host(ev.url))}</div><div class="md">${md(ev.markdown)}</div>
      <div class="acts"><button class="btn" data-a="speak">${icon('speak', 12)} Read aloud</button><button class="btn" data-a="copy">Copy</button><button class="btn" data-a="pdf">${icon('doc', 12)} Save PDF</button><button class="btn" data-a="map">${icon('map', 12)} Mind map</button></div>`;
    c.onclick = (e) => {
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'speak') speak(ev.markdown.replace(/[*#>`_]/g, ''), '', ev.title + ' — summary');
      if (a === 'copy') { S.invoke('clipboard:write', ev.markdown); toast('Copied'); }
      if (a === 'pdf') runAgent('Save that summary as a PDF');
      if (a === 'map') runTool('mindmap');
    };
    t.root.appendChild(c);
    if (ev.standalone) cur = null;
    scrollDown();
  }

  function fileCard(ev) {
    const t = ensureTurn();
    const c = h('div', 'card file');
    const name = ev.file.split(/[\\/]/).pop();
    c.innerHTML = `<div class="ch">${icon(ev.kind === 'pdf' ? 'doc' : ev.kind === 'image' ? 'shot' : 'down')} ${ev.kind === 'pdf' ? 'PDF ready' : ev.kind === 'image' ? 'Screenshot saved' : 'File saved'}</div><div class="fn">${esc(name)}</div>
      <div class="acts"><button class="btn" data-a="open">Open</button><button class="btn" data-a="reveal">${icon('folder', 12)} Show in folder</button><button class="btn" data-a="phone">${icon('phone', 12)} Send to phone</button></div>`;
    if (ev.kind === 'image') S.invoke('open:file-url', ev.file).then(u => { if (u) { const im = h('img', 'thumb'); im.src = u; c.insertBefore(im, c.querySelector('.acts')); } });
    c.onclick = (e) => {
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (a === 'open') S.invoke('file:open', ev.file);
      if (a === 'reveal') S.invoke('file:reveal', ev.file);
      if (a === 'phone') sendToPhone(ev.file);
    };
    t.root.appendChild(c); scrollDown();
  }

  function approvalCard(ev) {
    const t = ensureTurn();
    const c = h('div', 'card approval');
    const d = ev.details || {};
    c.innerHTML = `<div class="ch">${icon('lock')} Approval needed</div>
      <div style="font-weight:700">${esc(ev.title)}</div>
      ${d.amount ? `<div class="amt">${esc(d.amount)}</div>` : ''}
      <div style="color:var(--muted);font-size:12.5px">${esc([d.item, d.merchant].filter(Boolean).join(' · '))}</div>
      ${ev.phone ? `<div class="phone"><span class="pring">${icon('phone')}</span><div><b>Check your phone</b><br><span style="color:var(--muted)">Approve with fingerprint, face or PIN in the Cairn app.</span></div></div>`
        : `<div style="font-size:12px;color:var(--muted);margin-top:8px">No phone paired — confirm here. Pair your phone in Settings → Phone for biometric approvals.</div>
           <div class="acts"><button class="btn danger" data-a="deny">Deny</button><button class="btn primary" data-a="ok">${icon('check', 12)} Approve</button></div>`}`;
    c.onclick = (e) => {
      const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return;
      S.invoke('agent:approve', ev.id, a === 'ok');
      c.querySelector('.acts')?.remove();
      c.appendChild(h('div', '', `<div style="margin-top:8px;font-weight:700;color:${a === 'ok' ? 'var(--ok)' : 'var(--err)'}">${a === 'ok' ? 'Approved' : 'Denied'}</div>`));
    };
    t.root.appendChild(c); t.approval = c; scrollDown();
  }

  function askCard(ev) {
    const t = ensureTurn();
    const c = h('div', 'card ask');
    c.innerHTML = `<div class="ch">${icon('info')} Cairn needs your input</div><div class="md">${md(ev.question)}</div><input placeholder="Type your answer… (or 'done' after finishing in the page)"><div class="acts"><button class="btn primary">Reply</button></div>`;
    const inp = c.querySelector('input');
    const go = () => { const v = inp.value.trim(); if (!v) return; S.invoke('agent:answer', ev.id, v); c.querySelector('.acts').remove(); inp.disabled = true; };
    c.querySelector('button').onclick = go;
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    t.root.appendChild(c); scrollDown(); setTimeout(() => inp.focus(), 50);
  }
  function confirmCard(ev) {
    const t = ensureTurn();
    const c = h('div', 'card');
    c.innerHTML = `<div class="ch">${icon('click')} Allow this action?</div><div style="font-size:12.5px"><code>${esc(ev.text)}</code></div><div class="acts"><button class="btn" data-a="no">Skip</button><button class="btn primary" data-a="yes">Allow</button></div>`;
    c.onclick = (e) => { const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; S.invoke('agent:confirm', ev.id, a === 'yes'); c.querySelector('.acts').remove(); };
    t.root.appendChild(c); scrollDown();
  }

  function setBusy(b) {
    agentBusy = b;
    document.body.classList.toggle('agent-running', b);
    send.classList.toggle('stop', b); send.disabled = !b && !input.value.trim();
    send.innerHTML = icon(b ? 'stop' : 'send', 16); send.title = b ? 'Stop' : 'Send';
    renderTabs();
  }

  S.on('agent:event', (ev) => {
    switch (ev.type) {
      case 'start': setBusy(true); break;
      case 'thinking': { const t = ensureTurn(); if (!t.steps) addStep({ icon: 'spark', text: 'Thinking…' }); break; }
      case 'step': addStep(ev); break;
      case 'sources': {
        const t = ensureTurn(); const w = h('div', 'sources');
        for (const s of ev.items) { const a = h('button', 'src', `<img src="https://icons.duckduckgo.com/ip3/${encodeURIComponent(host(s.url))}.ico" width="14" height="14" alt=""><span>${esc(host(s.url))}</span>`); a.title = s.title; a.onclick = () => S.invoke('tabs:new', s.url); w.appendChild(a); }
        t.root.appendChild(w); break;
      }
      case 'products': productCards(ev); break;
      case 'summary': summaryCard(ev); break;
      case 'file': fileCard(ev); break;
      case 'approval': approvalCard(ev); break;
      case 'approval_result': if (cur?.approval) cur.approval.appendChild(h('div', '', `<div style="margin-top:8px;font-weight:700;color:${ev.status === 'approved' ? 'var(--ok)' : 'var(--err)'}">${ev.status === 'approved' ? 'Approved on your phone' : 'Not approved (' + esc(ev.status) + ')'}</div>`)); break;
      case 'ask': askCard(ev); break;
      case 'confirm': confirmCard(ev); break;
      case 'speak': speak(ev.text, ev.lang, ev.title); break;
      case 'answer': {
        if (ev.error && cur?.steps && cur.count <= 1) { cur.steps.remove(); cur.steps = null; }
        finishSteps();
        const t = ensureTurn();
        const b = h('div', 'md', md(ev.text, ev.sources));
        if (ev.error) b.classList.add('errtxt');
        t.root.appendChild(b);
        if (!ev.error && ev.text) {
          const bar = h('div', 'ans-bar');
          const cp = h('button', 'ab', `${icon('copy', 14)}<span>Copy</span>`);
          cp.onclick = () => { const plain = ev.text + (ev.sources?.length ? '\n\nSources:\n' + ev.sources.map(c => `[${c.n}] ${c.title} — ${c.url}`).join('\n') : ''); S.invoke('clipboard:write', plain); cp.querySelector('span').textContent = 'Copied'; setTimeout(() => { cp.querySelector('span').textContent = 'Copy'; }, 1400); };
          const rd = h('button', 'ab', `${icon('speak', 14)}<span>Listen</span>`); rd.onclick = () => speak(ev.text.replace(/\[\d{1,2}\]/g, '').replace(/[*#`]/g, ''), '', '');
          bar.append(cp, rd);
          if (ev.sources?.length) {
            const favs = [...new Set(ev.sources.map(c => host(c.url)))].slice(0, 4).map(hh => `<img src="cairn://search/icon?h=${encodeURIComponent(hh)}" alt="">`).join('');
            const chip = h('button', 'ab srcchip', `<span class="favs">${favs}</span><span>${ev.sources.length} source${ev.sources.length > 1 ? 's' : ''}</span>`);
            const list = h('div', 'srclist');
            for (const c of ev.sources) { const r = h('button', 'srcrow', `<span class="n">${c.n}</span><img src="cairn://search/icon?h=${encodeURIComponent(host(c.url))}" alt=""><span class="tt">${esc(c.title)}</span><span class="hh">${esc(host(c.url))}</span>`); r.onclick = () => S.invoke('tabs:new', c.url); list.appendChild(r); }
            chip.onclick = () => list.classList.toggle('open');
            for (const im of [...chip.querySelectorAll('img'), ...list.querySelectorAll('img')]) im.onerror = () => { im.style.visibility = 'hidden'; };
            bar.appendChild(chip); t.root.appendChild(bar); t.root.appendChild(list);
          } else t.root.appendChild(bar);
        }
        if (ev.setup) { const bt = h('button', 'btn primary setup-btn', `${icon('key', 13)} Add API key`); bt.onclick = () => S.invoke('tabs:new', 'cairn://settings#ai'); t.root.appendChild(bt); }
        if (ev.provider) t.root.appendChild(h('div', 'prov', `via ${ev.provider === 'groq' ? 'Groq' : 'NVIDIA NIM'}`));
        scrollDown(); break;
      }
      case 'end': finishSteps(); setBusy(false); cur = null; break;
    }
  });
  thread.addEventListener('click', (e) => { const a = e.target.closest('a[data-ext]'); if (a) { e.preventDefault(); S.invoke('tabs:new', a.href); } });

  async function refreshModelTag() {
    const st = await S.invoke('agent:status');
    const tag = $('#modelTag');
    if (!st.ready) { tag.textContent = 'Setup needed'; tag.style.color = 'var(--warn)'; tag.onclick = () => S.invoke('tabs:new', 'cairn://settings#ai'); tag.style.cursor = 'pointer'; }
    else { tag.textContent = st.mode === 'cloud' ? 'Cairn Cloud' : st.providers.map(p => p === 'groq' ? 'Groq' : 'NVIDIA').join(' + '); tag.style.color = ''; tag.onclick = null; }
  }
  refreshModelTag();

  // ---------- Text to speech ----------
  const player = $('#player');
  let queue = [], speaking = false, paused = false, ttsLang = '';
  function chunks(text) {
    const parts = String(text).replace(/\s+/g, ' ').match(/[^.!?।\n]+[.!?।]?/g) || [];
    const out = []; let buf = '';
    for (const p of parts) { if ((buf + p).length > 220) { if (buf) out.push(buf); buf = p; } else buf += p; }
    if (buf.trim()) out.push(buf);
    return out;
  }
  function pickVoice(lang) {
    const vs = speechSynthesis.getVoices();
    if (settings.voiceName) { const v = vs.find(x => x.name === settings.voiceName); if (v) return v; }
    const want = lang || 'en-IN';
    return vs.find(v => v.lang === want) || vs.find(v => v.lang.startsWith(want.slice(0, 2))) || vs.find(v => v.lang === 'en-IN') || vs.find(v => v.default) || vs[0];
  }
  function speak(text, lang, title) {
    stopSpeaking();
    ttsLang = /[\u0900-\u097F]/.test(text.slice(0, 400)) ? 'hi-IN' : (lang && lang.length >= 2 ? (lang.length === 2 ? (lang === 'en' ? 'en-IN' : lang) : lang) : 'en-IN');
    queue = chunks(text); speaking = true; paused = false;
    $('#playerTitle').textContent = title ? `Reading: ${title}` : 'Reading aloud';
    player.classList.add('show'); player.classList.remove('paused');
    $('#playPause').innerHTML = icon('pause', 16);
    togglePanel(true);
    next();
  }
  let cloudAudio = null;
  function next() {
    if (!speaking || !queue.length) { if (speaking) stopSpeaking(); return; }
    const v = pickVoice(ttsLang);
    const native = v && (settings.voiceName === v.name || (v.lang || '').slice(0, 2) === ttsLang.slice(0, 2));
    if (!native) {
      // No OS voice for this language — use cloud speech for English, otherwise explain.
      if (ttsLang.startsWith('en')) {
        const chunk = queue.shift();
        S.invoke('voice:speak', chunk).then((buf) => {
          if (!speaking) return;
          const url = URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
          cloudAudio = new Audio(url); cloudAudio.playbackRate = Number($('#rate').value) || 1;
          cloudAudio.onended = () => { URL.revokeObjectURL(url); if (speaking && !paused) next(); };
          cloudAudio.play().catch(() => next());
        }).catch((e) => { toast(cleanErr(e), { error: true }); stopSpeaking(); });
        return;
      }
      if (!v) { toast('No voice is installed for this language. Add one in your system speech settings.', { error: true }); stopSpeaking(); return; }
    }
    const u = new SpeechSynthesisUtterance(queue.shift());
    if (v) u.voice = v;
    u.lang = ttsLang; u.rate = Number($('#rate').value) || 1;
    u.onend = () => { if (speaking && !paused) next(); };
    u.onerror = () => { if (speaking) next(); };
    speechSynthesis.speak(u);
  }
  function stopSpeaking() { speaking = false; queue = []; speechSynthesis.cancel(); if (cloudAudio) { cloudAudio.pause(); cloudAudio = null; } player.classList.remove('show'); }
  $('#playStop').onclick = stopSpeaking;
  $('#playPause').onclick = () => {
    if (!speaking) return;
    paused = !paused;
    if (cloudAudio) paused ? cloudAudio.pause() : cloudAudio.play(); else paused ? speechSynthesis.pause() : speechSynthesis.resume();
    player.classList.toggle('paused', paused);
    $('#playPause').innerHTML = icon(paused ? 'play' : 'pause', 16);
  };
  speechSynthesis.onvoiceschanged = () => {};

  // ---------- Voice input (Groq Whisper) ----------
  let rec = null, chunksA = [];
  $('#mic').onclick = async () => {
    if (rec) { rec.stop(); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      rec = new MediaRecorder(stream, { mimeType: 'audio/webm;codecs=opus' });
      chunksA = [];
      rec.ondataavailable = (e) => chunksA.push(e.data);
      rec.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        $('#mic').classList.remove('rec'); rec = null;
        const blob = new Blob(chunksA, { type: 'audio/webm' });
        if (blob.size < 2000) return;
        input.placeholder = 'Transcribing…';
        try {
          const text = await S.invoke('voice:transcribe', await blob.arrayBuffer(), 'audio/webm');
          input.value = text; input.dispatchEvent(new Event('input'));
          if (text.trim()) submit();
        } catch (e) { toast(cleanErr(e), { error: true }); }
        input.placeholder = 'Ask anything or give Cairn a task…';
      };
      rec.start(); $('#mic').classList.add('rec');
      setTimeout(() => rec?.state === 'recording' && rec.stop(), 60000);
    } catch (e) { toast('Microphone unavailable: ' + e.message, { error: true }); }
  };

  // global shortcuts inside the chrome UI
  document.addEventListener('keydown', (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'l') { e.preventDefault(); url.focus(); }
    if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); togglePanel(); }
  });
})();
