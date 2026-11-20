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
    const r = Number($('#clrRange').value); const pw = $('#clrPw').checked, af = $('#clrAf').checked;
    if ((pw || af) && !confirm('Delete saved ' + [pw && 'passwords', af && 'cards and addresses'].filter(Boolean).join(' and ') + ' for this time range from this computer and every synced device? This can\'t be undone.')) return;
    await api('history:clear', { since: r ? Date.now() - r : 0, cookies: $('#clrCookies').checked, cache: $('#clrCache').checked, passwords: pw, autofill: af });
    toast('Browsing data cleared');
  };

  // secure DNS
  const dohPaint = () => { $('#dohMode').value = S.dohMode || 'off'; $('#dohUrl').value = S.dohUrl || ''; $('#dohCustomRow').hidden = $('#dohMode').value !== 'custom'; };
  dohPaint();
  $('#dohMode').onchange = async (e) => { const m = e.target.value; $('#dohCustomRow').hidden = m !== 'custom'; if (m !== 'custom' || /^https:\/\//.test(S.dohUrl || '')) await save({ dohMode: m }); };
  $('#dohSave').onclick = async () => { const u = $('#dohUrl').value.trim(); if (!/^https:\/\/[^/]+/.test(u)) return toast('Enter an https:// address'); await save({ dohMode: 'custom', dohUrl: u }); };

  // site permissions: Ask / Allow / Block per site
  const PERMS = [['camera', 'Camera'], ['microphone', 'Microphone'], ['geolocation', 'Location'], ['notifications', 'Notifications'], ['popups', 'Pop-ups']];
  const PERM_NAMES = Object.fromEntries([...PERMS, ['clipboard-read', 'Clipboard'], ['midi', 'MIDI'], ['openExternal', 'Open apps']]);
  function renderPerms() {
    const sp = S.sitePermissions || {}; const el = $('#permList'); const sites = Object.keys(sp);
    if (!sites.length) { el.innerHTML = '<div class="opt"><div class="l"><small>Sites you allow or block appear here.</small></div></div>'; return; }
    el.innerHTML = sites.map(o => `<div class="opt" style="flex-wrap:wrap;gap:8px"><div class="l" style="min-width:180px"><b>${esc(o.replace(/^https?:\/\//, ''))}</b></div>
      ${PERMS.map(([k, n]) => `<label class="small muted">${n} <select class="input" data-site="${esc(o)}" data-perm="${k}"><option value="ask"${sp[o][k] === undefined ? ' selected' : ''}>${k === 'popups' ? 'Default' : 'Ask'}</option><option value="allow"${sp[o][k] === true ? ' selected' : ''}>Allow</option><option value="block"${sp[o][k] === false ? ' selected' : ''}>Block</option></select></label>`).join('')}
      <button class="btn" data-reset="${esc(o)}">Reset</button></div>`).join('');
  }
  async function setPerm(o, k, v) {
    const sp = JSON.parse(JSON.stringify(S.sitePermissions || {})); sp[o] = sp[o] || {};
    if (v === 'ask') delete sp[o][k]; else sp[o][k] = v === 'allow';
    await api('settings:set', { sitePermissions: sp }); S.sitePermissions = sp; toast('Saved');
  }
  renderPerms();
  $('#permList').addEventListener('change', (e) => { const s = e.target.closest('select[data-perm]'); if (s) setPerm(s.dataset.site, s.dataset.perm, s.value); });
  $('#permList').addEventListener('click', async (e) => {
    const o = e.target.closest('[data-reset]')?.dataset.reset; if (!o) return;
    const sp = { ...(S.sitePermissions || {}) }; delete sp[o]; await api('settings:set', { sitePermissions: sp }); S.sitePermissions = sp; renderPerms(); toast('Permissions reset');
  });
  $('#permAddGo').onclick = async () => {
    let v = $('#permAdd').value.trim(); if (!v.includes('.')) return toast('Enter a site like example.com');
    try { const o = new URL(/^https?:/.test(v) ? v : 'https://' + v).origin; const sp = { ...(S.sitePermissions || {}) }; sp[o] = sp[o] || {}; await api('settings:set', { sitePermissions: sp }); S.sitePermissions = sp; $('#permAdd').value = ''; renderPerms(); } catch { toast('That address is not valid'); }
  };
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
    // custom passphrase
    const mode = !st.signedIn ? 'hidden' : st.needPassphrase ? 'unlock' : st.passphrase ? 'on' : st.ready ? 'off' : 'hidden';
    $('#ppRow').hidden = mode === 'hidden'; $('#ppForm').hidden = true; $('#ppRow').dataset.mode = mode;
    $('#ppBtn').textContent = { unlock: 'Enter passphrase', on: 'Change or remove', off: 'Set passphrase' }[mode] || '';
    $('#ppInfo').textContent = mode === 'unlock' ? 'Your synced data is protected by a custom passphrase. Enter it to start syncing on this computer.'
      : mode === 'on' ? 'On. Synced data is encrypted with your passphrase. Cairn\'s server never sees it.'
      : 'Optional. Encrypt synced data with a passphrase only you know, instead of your account password. Cairn\'s server can never read it, but if you forget it you\'ll have to reset sync.';
    if (st.needPassphrase) $('#syncInfo').textContent = 'Locked with your custom sync passphrase.';
    const rt = await api('sync:remoteTabs').catch(() => []);
    $('#remoteTabs').innerHTML = rt.map(d => `<div class="opt"><div class="l"><b>${ICON('phone')} ${esc(d.device)}</b><small>${d.tabs.filter(t => /^https?:/i.test(t.url)).slice(0, 8).map(t => `<a href="${esc(t.url)}">${esc(t.title || t.url)}</a>`).join(' · ')}${d.tabs.length > 8 ? ` · +${d.tabs.length - 8} more` : ''}</small></div></div>`).join('');
  }
  $('#syncOn').onchange = async (e) => { await api('sync:setOn', e.target.checked); loadSync(); };
  $('#syncNow').onclick = async () => { $('#syncNow').disabled = true; const r = await api('sync:now').catch(e => ({ error: cleanErr(e) })); $('#syncNow').disabled = false; toast(r.ok ? 'Synced' : (r.error || 'Sync failed')); loadSync(); };
  $('#ppBtn').onclick = async () => {
    const mode = $('#ppRow').dataset.mode;
    if (mode === 'on') {
      const c = prompt('Type a new passphrase to change it, or type REMOVE to go back to using your account password.');
      if (!c) return;
      try {
        if (c.trim() === 'REMOVE') { const pw = prompt('Enter your Cairn account password'); if (!pw) return; await api('sync:removePassphrase', pw); toast('Passphrase removed'); }
        else { await api('sync:setPassphrase', c); toast('Passphrase changed'); }
      } catch (e) { toast(cleanErr(e)); }
      return loadSync();
    }
    $('#ppForm').hidden = false; $('#ppIn2').hidden = mode === 'unlock'; $('#ppIn').value = $('#ppIn2').value = ''; $('#ppIn').focus();
    $('#ppGo').textContent = mode === 'unlock' ? 'Unlock' : 'Save';
  };
  $('#ppCancel').onclick = () => { $('#ppForm').hidden = true; };
  $('#ppGo').onclick = async () => {
    const mode = $('#ppRow').dataset.mode, a = $('#ppIn').value;
    try {
      if (mode === 'unlock') { await api('sync:unlock', a); toast('Sync unlocked'); }
      else { if (a !== $('#ppIn2').value) return toast('Passphrases don\'t match'); await api('sync:setPassphrase', a); toast('Passphrase set. Other devices keep syncing; new devices will ask for it.'); }
    } catch (e) {
      if (mode === 'unlock' && /not correct/.test(cleanErr(e)) && confirm('Wrong passphrase. Forgot it? You can reset sync: the encrypted copy on the server is deleted and this computer uploads its data again with a new key.')) {
        const pw = prompt('Enter your Cairn account password to reset sync'); if (pw) { try { await api('sync:reset', pw); toast('Sync reset'); } catch (e2) { toast(cleanErr(e2)); } }
      } else toast(cleanErr(e));
    }
    loadSync();
  };
  loadSync();

  const info = await api('app:info');
  $('#ver').textContent = 'Cairn ' + info.version; $('#verD').textContent = `Chromium ${info.chrome} · Electron ${info.electron} · ${info.platform}`;
  show();
})();
