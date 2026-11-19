(() => {
  const out = $('#out'), q = $('#q');
  async function load() {
    const ql = q.value.trim().toLowerCase();
    const items = (await api('bookmarks:list')).filter(b => !ql || (b.title + b.url).toLowerCase().includes(ql));
    if (!items.length) { out.innerHTML = `<div class="empty"><div class="ei">${ICON('star', 22)}</div>${ql ? 'No matches.' : 'Press Ctrl+D (⌘D on Mac) or the star in the address bar to save a page.'}</div>`; return; }
    out.innerHTML = '<div class="list">' + items.map(b => `<div class="row" data-id="${esc(b.id)}"><img class="fav" src="${fav(b.url)}" alt=""><a class="main" href="${esc(b.url)}" style="color:inherit;text-decoration:none"><div>${esc(b.title || b.url)}</div><small>${esc(b.url)}</small></a>
      <div class="acts"><button class="btn sm" data-ed title="Edit">${ICON('edit', 14)}</button><button class="btn sm" data-rm title="Delete">${ICON('trash', 14)}</button></div></div>`).join('') + '</div>';
    $$('[data-rm]').forEach(b => b.onclick = async () => { await api('bookmarks:remove', b.closest('.row').dataset.id); load(); });
    $$('[data-ed]').forEach(b => b.onclick = () => {
      const row = b.closest('.row'), it = items.find(x => x.id === row.dataset.id);
      row.innerHTML = `<input class="input" style="flex:1" value="${esc(it.title)}" data-f="title"><input class="input" style="flex:1.4" value="${esc(it.url)}" data-f="url"><button class="btn sm primary" data-ok>Save</button><button class="btn sm" data-cancel>Cancel</button>`;
      $('[data-ok]', row).onclick = async () => { await api('bookmarks:update', it.id, { title: $('[data-f=title]', row).value, url: $('[data-f=url]', row).value }); load(); };
      $('[data-cancel]', row).onclick = load;
    });
  }
  q.oninput = load; load();
})();
