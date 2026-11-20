(() => {
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
  let active = 'all';
  const eta = (d) => { if (!d.speed || !d.total) return ''; const s = Math.round((d.total - d.received) / d.speed); return s < 60 ? s + 's left' : s < 3600 ? Math.round(s / 60) + ' min left' : Math.floor(s / 3600) + ' h ' + Math.round((s % 3600) / 60) + ' min left'; };
  async function load() {
    const ql = q.value.trim().toLowerCase();
    const allItems = (await api('downloads:list')).sort((a, b) => b.at - a.at);
    const chips = $('#cats');
    chips.innerHTML = CATS.map(([k, l]) => { const n = k === 'all' ? allItems.length : allItems.filter(d => cat(d) === k).length; return (k === 'all' || n) ? `<button class="btn sm${active === k ? ' primary' : ''}" data-cat="${k}">${l}${k === 'all' ? '' : ' · ' + n}</button>` : ''; }).join('');
    $$('[data-cat]').forEach(b => b.onclick = () => { active = b.dataset.cat; load(); });
    const items = allItems.filter(d => (active === 'all' || cat(d) === active) && (!ql || (d.filename + d.url).toLowerCase().includes(ql)));
    if (!items.length) { out.innerHTML = `<div class="empty"><div class="ei">${ICON('down', 22)}</div>${ql ? 'No matches.' : 'Files you download — and PDFs, mind maps and screenshots Cairn makes — appear here.'}</div>`; return; }
    out.innerHTML = '<div class="list">' + items.map(d => {
      const st = d.state === 'progressing' ? (d.paused ? `Paused · ${size(d.received)} of ${size(d.total)}` : [`${size(d.received)} of ${size(d.total)}`, d.speed ? size(d.speed) + '/s' : '', eta(d)].filter(Boolean).join(' · ')) : d.state === 'completed' ? size(d.total || d.received) : d.state === 'cancelled' ? 'Cancelled' : 'Failed';
      const pct = d.total ? Math.round(100 * d.received / d.total) : 0;
      return `<div class="row" data-id="${esc(d.id)}" data-path="${esc(d.path || '')}"><div class="ei" style="width:34px;height:34px;border-radius:9px;background:var(--surface);display:grid;place-items:center;color:var(--accent)">${ICON('down', 16)}</div>
      <div class="main"><div>${d.state === 'completed' ? `<a href="#" data-open>${esc(d.filename)}</a>` : esc(d.filename)}</div><small>${esc(st)} · ${esc(host(d.url))} · ${new Date(d.at).toLocaleString()}</small>
      ${d.state === 'progressing' ? `<div style="height:4px;background:var(--surface-2);border-radius:4px;margin-top:6px;overflow:hidden"><div style="height:100%;width:${pct}%;background:var(--accent)"></div></div>` : ''}</div>
      <div class="acts" style="opacity:1">${d.state === 'progressing' ? `<button class="btn sm" data-pause>${d.paused ? 'Resume' : 'Pause'}</button><button class="btn sm" data-cancel>Cancel</button>` : d.state === 'completed' ? `<button class="btn sm" data-reveal title="Show in folder">${ICON('folder', 14)}</button><button class="btn sm" data-phone title="Send to phone">${ICON('phone', 14)}</button>` : ''}</div></div>`;
    }).join('') + '</div>';
    const row = (b) => b.closest('.row').dataset;
    $$('[data-open]').forEach(b => b.onclick = (e) => { e.preventDefault(); api('file:open', row(b).path); });
    $$('[data-reveal]').forEach(b => b.onclick = () => api('file:reveal', row(b).path));
    $$('[data-pause]').forEach(b => b.onclick = () => api('downloads:pause', row(b).id).then(load));
    $$('[data-cancel]').forEach(b => b.onclick = () => api('downloads:cancel', row(b).id).then(load));
    $$('[data-phone]').forEach(b => b.onclick = async () => { b.disabled = true; try { await api('file:toPhone', row(b).path, 'Downloads'); toast('Sent to your phone'); } catch (e) { toast(cleanErr(e)); } b.disabled = false; });
  }
  q.oninput = load; $('#clear').onclick = () => api('downloads:clear').then(load);
  load(); setInterval(() => { if (document.visibilityState === 'visible') load(); }, 1500);
})();
