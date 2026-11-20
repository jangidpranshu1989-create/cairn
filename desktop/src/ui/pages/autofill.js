(() => {
  const CARD = [['number', 'Card number', 'inputmode="numeric" autocomplete="off" required'], ['name', 'Name on card', ''], ['expMonth', 'MM', 'maxlength="2" inputmode="numeric" style="max-width:70px"'], ['expYear', 'YYYY', 'maxlength="4" inputmode="numeric" style="max-width:90px"'], ['nickname', 'Nickname (optional)', '']];
  const ADDR = [['name', 'Full name', ''], ['phone', 'Phone', 'type="tel"'], ['email', 'Email', 'type="email"'], ['line1', 'Address line 1', 'required'], ['line2', 'Address line 2', ''], ['city', 'City', ''], ['state', 'State', ''], ['zip', 'PIN / ZIP code', ''], ['country', 'Country', '']];
  function form(box, kind, fields, item, done) {
    box.innerHTML = `<form class="card" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:18px">${fields.map(([k, ph, a]) => `<input class="input" name="${k}" placeholder="${ph}" ${a} value="${esc(item?.[k] || '')}" style="flex:1;min-width:140px">`).join('')}<button class="btn" type="button" data-x>Cancel</button><button class="btn primary">Save</button></form>`;
    const f = $('form', box);
    $('[data-x]', f).onclick = () => { box.innerHTML = ''; };
    f.onsubmit = async (e) => { e.preventDefault(); const j = Object.fromEntries(fields.map(([k]) => [k, f[k].value.trim()]));
      try { await api('af:save', kind, j, item?.id); box.innerHTML = ''; toast('Saved'); done(); } catch (err) { toast(cleanErr(err)); } };
  }
  async function load() {
    const cards = await api('af:all', 'cards'), addrs = await api('af:all', 'addresses');
    $('#cards').innerHTML = cards.length ? '<div class="list">' + cards.map(c => `<div class="row" data-id="${esc(c.id)}"><div class="ei" style="width:34px;height:34px;border-radius:9px;background:var(--surface);display:grid;place-items:center;font-weight:700;font-size:11px">${esc(c.brand.slice(0, 4))}</div><div class="main"><div>${esc(c.label)}</div><small>${esc(c.sub || '')}</small></div><div class="acts"><button class="btn sm" data-ed>${ICON('edit', 14)}</button><button class="btn sm" data-rm>${ICON('trash', 14)}</button></div></div>`).join('') + '</div>'
      : `<div class="empty">Cards you save at checkout appear here.</div>`;
    $('#addrs').innerHTML = addrs.length ? '<div class="list">' + addrs.map(a => `<div class="row" data-id="${esc(a.id)}"><div class="main"><div>${esc(a.label)}</div><small>${esc(a.sub || '')}</small></div><div class="acts"><button class="btn sm" data-ed>${ICON('edit', 14)}</button><button class="btn sm" data-rm>${ICON('trash', 14)}</button></div></div>`).join('') + '</div>'
      : `<div class="empty">Addresses you save on forms appear here.</div>`;
    const id = (b) => b.closest('.row').dataset.id;
    $$('#cards [data-rm]').forEach(b => b.onclick = async () => { if (confirm('Delete this card from all your devices?')) { await api('af:remove', 'cards', id(b)); load(); } });
    $$('#addrs [data-rm]').forEach(b => b.onclick = async () => { if (confirm('Delete this address from all your devices?')) { await api('af:remove', 'addresses', id(b)); load(); } });
    $$('#cards [data-ed]').forEach(b => b.onclick = async () => { const c = await api('af:get', 'cards', id(b)); if (c) form($('#cardForm'), 'cards', CARD, c, load); });
    $$('#addrs [data-ed]').forEach(b => b.onclick = async () => { const a = await api('af:get', 'addresses', id(b)); if (a) form($('#addrForm'), 'addresses', ADDR, a, load); });
  }
  $('#addCard').onclick = () => form($('#cardForm'), 'cards', CARD, null, load);
  $('#addAddr').onclick = () => form($('#addrForm'), 'addresses', ADDR, null, load);
  load();
})();
