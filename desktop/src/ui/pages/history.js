(() => {
  const out = $('#out'), q = $('#q');
  const dayLabel = (t) => { const d = new Date(t), n = new Date(); const y = new Date(n - 864e5);
    if (d.toDateString() === n.toDateString()) return 'Today'; if (d.toDateString() === y.toDateString()) return 'Yesterday';
    return d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }); };
  async function load() {
    const items = await api('history:list', q.value.trim(), 500);
    if (!items.length) { out.innerHTML = `<div class="empty"><div class="ei">${ICON('history', 22)}</div>${q.value ? 'No matches.' : 'Pages you visit will show up here.'}</div>`; return; }
    let html = '', cur = '';
    for (const x of items) {
      const dl = dayLabel(x.at);
      if (dl !== cur) { if (cur) html += '</div>'; html += `<div class="day">${esc(dl)}</div><div class="list">`; cur = dl; }
      html += `<div class="row"><span class="small muted" style="width:52px">${new Date(x.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span><img class="fav" src="${fav(x.url)}" alt=""><a class="main" href="${esc(x.url)}" style="color:inherit;text-decoration:none"><div>${esc(x.title || x.url)}</div><small>${esc(host(x.url))}</small></a><div class="acts"><button class="btn sm" data-rm="${x.at}" title="Remove">${ICON('x', 14)}</button></div></div>`;
    }
    out.innerHTML = html + '</div>';
    $$('[data-rm]').forEach(b => b.onclick = async () => { await api('history:remove', Number(b.dataset.rm)); load(); });
  }
  let t; q.oninput = () => { clearTimeout(t); t = setTimeout(load, 150); };
  $('#clear').onclick = () => location.href = 'saarthi://settings#privacy';
  load();
})();
