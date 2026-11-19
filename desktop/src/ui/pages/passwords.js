(() => {
  const out = $('#out'), q = $('#q');
  async function load() {
    const all = await api('pw:all'); const ql = q.value.trim().toLowerCase();
    const weak = all.filter(p => p.weak).length, reused = all.filter(p => p.reused).length;
    $('#checkup').innerHTML = all.length ? `<div class="card" style="display:flex;gap:18px;align-items:center;margin-bottom:18px"><div style="color:${weak + reused ? 'var(--warn)' : 'var(--ok)'}">${ICON('shield', 26)}</div><div style="flex:1"><b>${weak + reused ? 'Password checkup found issues' : 'Your passwords look healthy'}</b><div class="muted small">${all.length} saved · ${weak} weak · ${reused} reused</div></div></div>` : '';
    const items = all.filter(p => !ql || (p.origin + p.username).toLowerCase().includes(ql)).sort((a, b) => host(a.origin).localeCompare(host(b.origin)));
    if (!items.length) { out.innerHTML = `<div class="empty"><div class="ei">${ICON('key', 22)}</div>${ql ? 'No matches.' : 'Cairn will offer to save passwords when you sign in to sites.'}</div>`; return; }
    out.innerHTML = '<div class="list">' + items.map(p => `<div class="row" data-id="${esc(p.id)}"><img class="fav" src="${fav(p.origin)}" alt=""><div class="main"><div>${esc(host(p.origin))} ${p.weak ? '<span class="tag warn">Weak</span>' : ''} ${p.reused ? '<span class="tag warn">Reused</span>' : ''}</div><small>${esc(p.username || '(no username)')}</small></div>
      <code class="pw muted" style="font-size:13px;min-width:110px">••••••••</code>
      <div class="acts"><button class="btn sm" data-show title="Show">${ICON('eye', 14)}</button><button class="btn sm" data-copy title="Copy">${ICON('copy', 14)}</button><button class="btn sm" data-ed title="Edit">${ICON('edit', 14)}</button><button class="btn sm" data-rm title="Delete">${ICON('trash', 14)}</button></div></div>`).join('') + '</div>';
    const id = (b) => b.closest('.row').dataset.id;
    $$('[data-show]').forEach(b => b.onclick = async () => { const code = b.closest('.row').querySelector('.pw'); if (code.dataset.on) { code.textContent = '••••••••'; delete code.dataset.on; return; } const v = await api('pw:reveal', id(b)); if (v != null) { code.textContent = v; code.dataset.on = 1; } });
    $$('[data-copy]').forEach(b => b.onclick = async () => { const v = await api('pw:reveal', id(b)); if (v != null) { await api('clipboard:write', v); toast('Password copied'); } });
    $$('[data-rm]').forEach(b => b.onclick = async () => { if (confirm('Delete this saved password?')) { await api('pw:remove', id(b)); load(); } });
    $$('[data-ed]').forEach(b => b.onclick = async () => {
      const row = b.closest('.row'); const v = await api('pw:reveal', id(b)); if (v == null) return;
      const p = items.find(x => x.id === id(b));
      row.innerHTML = `<input class="input" style="flex:1" value="${esc(p.username)}" data-f="username" placeholder="Username"><input class="input" style="flex:1" value="${esc(v)}" data-f="password" placeholder="Password"><button class="btn sm primary" data-ok>Save</button><button class="btn sm" data-cancel>Cancel</button>`;
      $('[data-ok]', row).onclick = async () => { await api('pw:update', p.id, { username: $('[data-f=username]', row).value, password: $('[data-f=password]', row).value }); load(); };
      $('[data-cancel]', row).onclick = load;
    });
  }
  $('#add').onclick = () => {
    const f = $('#addForm');
    if (f.innerHTML) { f.innerHTML = ''; return; }
    f.innerHTML = `<form class="card" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px"><input class="input" name="url" placeholder="https://example.com" required style="flex:1.2;min-width:180px"><input class="input" name="u" placeholder="Username or email" style="flex:1;min-width:150px"><input class="input" name="p" placeholder="Password" required style="flex:1;min-width:150px"><button class="btn" type="button" data-gen>Generate</button><button class="btn primary">Save</button></form>`;
    const form = $('form', f);
    $('[data-gen]', form).onclick = async () => { form.p.value = await api('pw:generate'); };
    form.onsubmit = async (e) => { e.preventDefault(); let url = form.url.value.trim(); if (!/^https?:\/\//.test(url)) url = 'https://' + url; await api('pw:add', url, form.u.value, form.p.value); f.innerHTML = ''; toast('Password saved'); load(); };
    form.url.focus();
  };
  $('#imp').onclick = async () => { const n = await api('pw:import'); if (n) { toast(`Imported ${n} passwords`); load(); } };
  $('#exp').onclick = async () => { if (await api('pw:export')) toast('Passwords exported'); };
  q.oninput = load; load();
})();
