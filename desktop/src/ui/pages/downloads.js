(() => {
  // Chrome-style download manager: grouped by day, per-file ⋮ menu, live progress, "file deleted" state.
  const out = $('#out'), q = $('#q');
  const size = (b) => !b ? '' : b < 1024 ? b + ' B' : b < 1048576 ? (b / 1024).toFixed(0) + ' KB' : b < 1073741824 ? (b / 1048576).toFixed(1) + ' MB' : (b / 1073741824).toFixed(2) + ' GB';
  const CATS = [['all', 'All'], ['images', 'Images'], ['videos', 'Videos'], ['audio', 'Audio'], ['docs', 'Docs'], ['apps', 'Apps & APKs'], ['pages', 'Offline pages'], ['other', 'Other']];
  function cat(d) {
    const ext = (d.filename.split('.').pop() || '').toLowerCase(), m = d.mime || '';
    if (d.offline || /^(mhtml|mht)$/.test(ext)) return 'pages';
    if (m.startsWith('image/') || /^(jpe?g|png|gif|webp|heic|svg|bmp|avif)$/.test(ext)) return 'images';
    if (m.startsWith('video/') || /^(mp4|mkv|webm|mov|avi|3gp)$/.test(ext)) return 'videos';
    if (m.startsWith('audio/') || /^(mp3|m4a|aac|wav|ogg|flac|opus)$/.test(ext)) return 'audio';
    if (/^(apk|apks|xapk|exe|msi|dmg|pkg|deb|rpm|appimage)$/.test(ext)) return 'apps';
    if (/pdf|text\/|document|sheet|presentation|msword/.test(m) || /^(pdf|docx?|xlsx?|pptx?|txt|csv|odt|rtf|epub|md)$/.test(ext)) return 'docs';
    return 'other';
  }
  const extOf = (d) => ((d.filename.split('.').pop() || '').slice(0, 4).toUpperCase()) || 'FILE';
  const dayKey = (t) => { const d = new Date(t); return d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate(); };
  const dayLabel = (t) => {
    const now = new Date(), y = new Date(now - 864e5);
    if (dayKey(t) === dayKey(now)) return 'Today';
    if (dayKey(t) === dayKey(y)) return 'Yesterday';
    return new Date(t).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: new Date(t).getFullYear() === now.getFullYear() ? undefined : 'numeric' });
  };
  const eta = (d) => { if (!d.speed || !d.total) return ''; const s = Math.round((d.total - d.received) / d.speed); return s < 60 ? s + 's left' : s < 3600 ? Math.round(s / 60) + ' min left' : Math.floor(s / 3600) + ' h ' + Math.round((s % 3600) / 60) + ' min left'; };
  let active = 'all', menuFor = null, items = [], missing = {};

  function closeMenu() { $('#dlmenu')?.remove(); menuFor = null; }
  function openMenu(btn, d) {
    closeMenu(); menuFor = d.id;
    const gone = missing[d.id];
    const opts = [
      d.state === 'completed' && !gone && ['open', 'Open file'],
      d.state === 'completed' && !gone && ['reveal', 'Show in folder'],
      d.state === 'completed' && !gone && ['phone', 'Send to phone'],
      ['copy', 'Copy download link'],
      d.state !== 'progressing' && ['retry', d.state === 'completed' && !gone ? 'Download again' : 'Retry download'],
      '-',
      d.state !== 'progressing' && ['remove', 'Remove from list'],
      d.state === 'completed' && !gone && ['delete', 'Delete file', 'danger'],
    ].filter(Boolean);
    const m = document.createElement('div'); m.id = 'dlmenu'; m.className = 'dlmenu'; m.setAttribute('role', 'menu');
    m.innerHTML = opts.map(o => o === '-' ? '<hr>' : `<button role="menuitem" data-a="${o[0]}" class="${o[2] || ''}">${esc(o[1])}</button>`).join('');
    document.body.appendChild(m);
    const r = btn.getBoundingClientRect();
    m.style.top = Math.min(r.bottom + 4, innerHeight - m.offsetHeight - 8) + 'px';
    m.style.left = Math.max(8, r.right - m.offsetWidth) + 'px';
    m.querySelector('button')?.focus();
    m.onclick = async (e) => {
      const a = e.target.closest('[data-a]')?.dataset.a; if (!a) return; closeMenu();
      try {
        if (a === 'open') await api('downloads:open', d.id);
        else if (a === 'reveal') await api('downloads:reveal', d.id);
        else if (a === 'phone') { await api('file:toPhone', d.path, 'Downloads'); toast('Sent to your phone'); }
        else if (a === 'copy') { await api('downloads:copyLink', d.id); toast('Link copied'); }
        else if (a === 'retry') { await api('downloads:retry', d.id); toast('Download started'); }
        else if (a === 'remove') { await api('downloads:remove', d.id); toast('Removed from list'); }
        else if (a === 'delete') { if (!confirm(`Delete "${d.filename}" from this computer?`)) return; await api('downloads:delete', d.id); toast('File moved to trash'); }
      } catch (err) { toast(cleanErr(err)); }
      load();
    };
  }
  document.addEventListener('click', (e) => { if (!e.target.closest('#dlmenu, [data-more]')) closeMenu(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenu(); });

  function rowHtml(d) {
    const gone = d.state === 'completed' && missing[d.id];
    const st = d.state === 'progressing' ? (d.paused ? `Paused · ${size(d.received)} of ${size(d.total)}` : [`${size(d.received)} of ${size(d.total)}`, d.speed ? size(d.speed) + '/s' : '', eta(d)].filter(Boolean).join(' · '))
      : gone ? 'Deleted' : d.state === 'completed' ? size(d.total || d.received) : d.state === 'cancelled' ? 'Cancelled' : 'Failed – network error';
    const pct = d.total ? Math.round(100 * d.received / d.total) : 0;
    const name = d.state === 'completed' && !gone ? `<a href="#" data-open>${esc(d.filename)}</a>` : `<span class="${gone || d.state === 'cancelled' || d.state === 'interrupted' ? 'strike' : ''}">${esc(d.filename)}</span>`;
    return `<div class="row dlrow${gone ? ' gone' : ''}" data-id="${esc(d.id)}">
      <div class="fileic c-${cat(d)}">${esc(extOf(d))}</div>
      <div class="main"><div>${name}</div><small><a href="#" data-src title="${esc(d.url)}">${esc(host(d.url))}</a> · ${esc(st)} · ${new Date(d.at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</small>
      ${d.state === 'progressing' ? `<div class="bar"><div style="width:${pct}%"></div></div>` : ''}
      ${d.state === 'progressing' ? `<div class="inline"><button class="btn sm" data-pause>${d.paused ? 'Resume' : 'Pause'}</button><button class="btn sm" data-cancel>Cancel</button></div>` : d.state === 'interrupted' || d.state === 'cancelled' ? `<div class="inline"><button class="btn sm" data-retry>Retry</button></div>` : ''}</div>
      <div class="acts" style="opacity:1">${d.state === 'completed' && !gone ? `<button class="btn sm ghost" data-reveal title="Show in folder">${ICON('folder', 14)}</button>` : ''}<button class="btn sm ghost" data-more title="More actions" aria-label="More actions">⋮</button></div></div>`;
  }

  async function load() {
    if (menuFor) return; // don't redraw under an open menu
    const ql = q.value.trim().toLowerCase();
    const allItems = (await api('downloads:list')).sort((a, b) => b.at - a.at);
    const done = allItems.filter(d => d.state === 'completed');
    const ex = await api('downloads:exists', done.map(d => d.id)).catch(() => done.map(() => true));
    missing = {}; done.forEach((d, i) => { if (!ex[i]) missing[d.id] = true; });
    const chips = $('#cats');
    chips.innerHTML = CATS.map(([k, l]) => { const n = k === 'all' ? allItems.length : allItems.filter(d => cat(d) === k).length; return (k === 'all' || n) ? `<button class="btn sm${active === k ? ' primary' : ''}" data-cat="${k}">${l}${k === 'all' ? '' : ' · ' + n}</button>` : ''; }).join('');
    $$('[data-cat]').forEach(b => b.onclick = () => { active = b.dataset.cat; load(); });
    items = allItems.filter(d => (active === 'all' || cat(d) === active) && (!ql || (d.filename + d.url).toLowerCase().includes(ql)));
    const live = allItems.filter(d => d.state === 'progressing').length;
    const total = done.filter(d => !missing[d.id]).reduce((s, d) => s + (d.total || d.received || 0), 0);
    $('#sum').textContent = allItems.length ? [`${allItems.length} item${allItems.length === 1 ? '' : 's'}`, live ? `${live} downloading` : '', total ? size(total) + ' on disk' : ''].filter(Boolean).join(' · ') : '';
    if (!items.length) { out.innerHTML = `<div class="empty"><div class="ei">${ICON('down', 22)}</div>${ql ? 'No matches.' : 'Files you download, and PDFs, mind maps and screenshots Cairn makes, appear here.'}</div>`; return; }
    const groups = []; for (const d of items) { const k = dayKey(d.at); if (!groups.length || groups[groups.length - 1].k !== k) groups.push({ k, at: d.at, list: [] }); groups[groups.length - 1].list.push(d); }
    out.innerHTML = groups.map(g => `<h3 class="day">${esc(dayLabel(g.at))}</h3><div class="list">${g.list.map(rowHtml).join('')}</div>`).join('');
    const byId = (b) => items.find(x => x.id === b.closest('.row').dataset.id);
    $$('[data-open]').forEach(b => b.onclick = (e) => { e.preventDefault(); api('downloads:open', byId(b).id).catch(err => { toast(cleanErr(err)); load(); }); });
    $$('[data-src]').forEach(b => b.onclick = (e) => { e.preventDefault(); const u = byId(b).url; if (/^https?:/.test(u)) api('tabs:new', u); });
    $$('[data-reveal]').forEach(b => b.onclick = () => api('downloads:reveal', byId(b).id));
    $$('[data-pause]').forEach(b => b.onclick = () => api('downloads:pause', byId(b).id).then(load));
    $$('[data-cancel]').forEach(b => b.onclick = () => api('downloads:cancel', byId(b).id).then(load));
    $$('[data-retry]').forEach(b => b.onclick = () => api('downloads:retry', byId(b).id).then(() => toast('Download started'), e => toast(cleanErr(e))).then(load));
    $$('[data-more]').forEach(b => b.onclick = (e) => { e.stopPropagation(); const d = byId(b); if (menuFor === d.id) closeMenu(); else openMenu(b, d); });
  }
  q.oninput = load;
  $('#clear').onclick = () => { if (confirm('Clear the download list? Files stay on your computer.')) api('downloads:clear').then(load); };
  $('#folder').onclick = () => api('downloads:openFolder');
  document.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); q.focus(); } });
  load(); setInterval(() => { if (document.visibilityState === 'visible') load(); }, 1500);
})();
