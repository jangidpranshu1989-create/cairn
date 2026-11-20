(async () => {
  let S = await api('settings:get');
  const save = async (patch) => { Object.assign(S, patch); await api('settings:set', patch); toast('Saved'); };

  // sections
  const show = () => {
    const id = (location.hash || '#general').slice(1);
    const sec = $('#s-' + id) ? id : 'general';
    $$('.sec').forEach(s => s.classList.toggle('on', s.id === 's-' + sec));
    $$('.side a[data-s]').forEach(a => a.classList.toggle('on', a.dataset.s === sec));
    if (sec === 'phone') loadPhone();
  };
  addEventListener('hashchange', show);

  // bind controls
  $$('.seg[data-k]').forEach(seg => {
    const k = seg.dataset.k;
    const paint = () => $$('button', seg).forEach(b => b.classList.toggle('on', String(S[k]) === b.dataset.v));
    paint();
    $$('button', seg).forEach(b => b.onclick = async () => { await save({ [k]: b.dataset.v }); paint(); });
  });
  $$('input[type=checkbox][data-k]').forEach(c => { c.checked = !!S[c.dataset.k]; c.onchange = () => save({ [c.dataset.k]: c.checked }); });
  $$('select[data-k]:not(#voices), input.input[data-k], input[type=range][data-k]').forEach(el => {
    el.value = S[el.dataset.k] ?? '';
    el.onchange = () => save({ [el.dataset.k]: el.dataset.num !== undefined ? Number(el.value) : el.value.trim() });
  });
  const rate = $('input[type=range]'); const rv = () => $('#rateV').textContent = Number(rate.value).toFixed(1) + '×'; rate.addEventListener('input', rv); rv();

  // voices
  const fillVoices = () => {
    const vs = speechSynthesis.getVoices(); if (!vs.length) return;
    $('#voices').innerHTML = '<option value="">Automatic (matches the language)</option>' + vs.map(v => `<option value="${esc(v.name)}">${esc(v.name)} — ${esc(v.lang)}</option>`).join('');
    $('#voices').value = S.voiceName || '';
  };
  speechSynthesis.onvoiceschanged = fillVoices; fillVoices();
  $('#voices').onchange = (e) => save({ voiceName: e.target.value });

  // Cairn AI is built in: show which plan is serving this device
  api('cloud:session').then(se => { const el = $('#aiPlan'); if (el) el.textContent = se?.signedIn ? 'Included with your Cairn Account on every device.' : 'Works with no setup. Sign in to your Cairn Account for a higher daily limit.'; }).catch(() => {});

  // clear data
  $('#clrGo').onclick = async () => {
    const r = Number($('#clrRange').value);
    await api('history:clear', { since: r ? Date.now() - r : 0, cookies: $('#clrCookies').checked, cache: $('#clrCache').checked });
    toast('Browsing data cleared');
  };
  const PERM_NAMES = { geolocation: 'Location', camera: 'Camera', microphone: 'Microphone', notifications: 'Notifications', 'clipboard-read': 'Clipboard', midi: 'MIDI', openExternal: 'Open apps' };
  function renderPerms() {
    const sp = S.sitePermissions || {}; const el = $('#permList'); const sites = Object.keys(sp).filter(o => Object.keys(sp[o]).length);
    if (!sites.length) { el.innerHTML = '<div class="opt"><div class="l"><small>Sites you allow or block (location, camera, microphone, notifications) appear here.</small></div></div>'; return; }
    el.innerHTML = sites.map(o => `<div class="opt"><div class="l"><b>${esc(o.replace(/^https?:\/\//, ''))}</b><small>${Object.entries(sp[o]).map(([k, v]) => `${PERM_NAMES[k] || k}: ${v ? 'Allowed' : 'Blocked'}`).join(' · ')}</small></div><button class="btn" data-reset="${esc(o)}">Reset</button></div>`).join('');
  }
  renderPerms();
  $('#permList').addEventListener('click', async (e) => {
    const o = e.target.closest('[data-reset]')?.dataset.reset; if (!o) return;
    const sp = { ...(S.sitePermissions || {}) }; delete sp[o]; await api('settings:set', { sitePermissions: sp }); S.sitePermissions = sp; renderPerms(); toast('Permissions reset');
  });
  api('cloud:base').then(b => { $('#privacyLink').href = 'https://cairn.app/privacy.html'; });

  // downloads
  const dl = () => $('#dlDir').textContent = S.downloadDir || 'Default Downloads folder (Cairn files go to Downloads/Cairn)';
  dl();
  $('#dlPick').onclick = async () => { const d = await api('settings:pickDir'); if (d) { await save({ downloadDir: d }); dl(); } };

  // phone
  async function loadPhone() {
    const sess = await api('cloud:session');
    const body = $('#phoneBody');
    if (!sess.signedIn) { body.innerHTML = `<div class="l"><b>Sign in first</b><small>Phone pairing uses your Cairn account so approvals reach only your phone.</small></div><a class="btn primary" href="cairn://account">Sign in</a>`; $('#devList').innerHTML = ''; return; }
    body.innerHTML = `<div class="l"><b>Pair with QR code</b><small>Open the Cairn app on Android → "Pair with computer", then scan the code.</small></div><button class="btn primary" id="pairBtn">Show pairing code</button>`;
    $('#pairBtn').onclick = startPair;
    loadDevices();
  }
  let pairTimer;
  async function startPair() {
    try {
      const p = await api('cloud:pair');
      const g = $('#phoneBody'); g.className = 'pair';
      g.innerHTML = `<img src="${p.qr}" alt="Pairing QR code"><div><div class="muted small">Or enter this code in the app</div><div class="code">${esc(p.code.replace(/(\d{3})/, '$1 '))}</div><div class="muted small" id="exp"></div><button class="btn sm" id="pairDone" style="margin-top:12px">Done</button></div>`;
      let left = p.expiresIn; clearInterval(pairTimer);
      pairTimer = setInterval(() => { left--; $('#exp') && ($('#exp').textContent = left > 0 ? `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}` : 'Code expired'); if (left % 5 === 0) loadDevices(); if (left <= 0) clearInterval(pairTimer); }, 1000);
      $('#pairDone').onclick = () => { clearInterval(pairTimer); g.className = 'opt'; loadPhone(); };
    } catch (e) { toast(cleanErr(e)); }
  }
  async function loadDevices() {
    try {
      const r = await api('cloud:devices'); const devices = Array.isArray(r) ? r : (r.devices || []);
      const phones = devices.filter(d => d.kind !== 'desktop');
      $('#devList').innerHTML = phones.length ? phones.map(d => `<div class="opt"><div class="l"><b>${ICON('phone')} ${esc(d.name)}</b><small>Paired ${new Date(d.created_at || d.createdAt).toLocaleDateString()}${d.online ? ' · <span style="color:var(--ok)">online</span>' : ''}</small></div><button class="btn sm danger" data-rm="${esc(d.id)}">Remove</button></div>`).join('')
        : '<div class="opt"><div class="l muted">No phones paired yet.</div></div>';
      $$('[data-rm]').forEach(b => b.onclick = async () => { await api('cloud:removeDevice', b.dataset.rm); loadDevices(); });
    } catch {}
  }

  // sync
  async function loadSync() {
    const st = await api('sync:status').catch(() => null); if (!st) return;
    $('#syncOn').checked = st.on;
    const when = st.last ? new Date(st.last).toLocaleString() : 'never';
    $('#syncInfo').textContent = !st.signedIn ? 'Sign in to your Cairn account to sync bookmarks, history, tabs, passwords and passkeys across devices.'
      : !st.ready ? 'Sign in again with your password to unlock end-to-end encrypted sync on this computer.'
      : st.error ? 'Last sync failed: ' + st.error : 'End-to-end encrypted. Last synced: ' + when;
    const rt = await api('sync:remoteTabs').catch(() => []);
    $('#remoteTabs').innerHTML = rt.map(d => `<div class="opt"><div class="l"><b>${ICON('phone')} ${esc(d.device)}</b><small>${d.tabs.filter(t => /^https?:/i.test(t.url)).slice(0, 8).map(t => `<a href="${esc(t.url)}">${esc(t.title || t.url)}</a>`).join(' · ')}${d.tabs.length > 8 ? ` · +${d.tabs.length - 8} more` : ''}</small></div></div>`).join('');
  }
  $('#syncOn').onchange = async (e) => { await api('sync:setOn', e.target.checked); loadSync(); };
  $('#syncNow').onclick = async () => { $('#syncNow').disabled = true; const r = await api('sync:now').catch(e => ({ error: cleanErr(e) })); $('#syncNow').disabled = false; toast(r.ok ? 'Synced' : (r.error || 'Sync failed')); loadSync(); };
  loadSync();

  const info = await api('app:info');
  $('#ver').textContent = 'Cairn ' + info.version; $('#verD').textContent = `Chromium ${info.chrome} · Electron ${info.electron} · ${info.platform}`;
  show();
})();
