(() => {
  const { api, store, esc } = window.Cairn;
  const card = document.getElementById('card');
  const q = new URLSearchParams(location.search);
  const check = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><path d="M20 6 9 17l-5-5"/></svg>';
  const logo = (c) => c.logo ? `<img alt="" src="${esc(c.logo)}">` : esc((c.name[0] || '?').toUpperCase());

  function fail(msg) {
    card.innerHTML = `<h1>Can't continue</h1><p class="sub">${esc(msg)}</p><a class="btn ghost" href="./">Go to Cairn Account</a>`;
  }
  async function main() {
    if (q.get('error')) return fail(q.get('error'));
    const params = Object.fromEntries(q.entries());
    let req;
    try { req = await api('/oauth/request?' + q.toString(), { noAuth: true }); }
    catch (e) { if (e.data?.redirect) { location.replace(e.data.redirect); return; } return fail(e.message); }
    const c = req.client;
    const auth = store.get();
    let me = null;
    if (auth) { try { me = (await api('/auth/me')).user; } catch { me = null; } }
    if (!me) {
      if ((req.prompt || '').split(' ').includes('none')) {
        const u = new URL(params.redirect_uri); u.searchParams.set('error', 'login_required'); if (params.state) u.searchParams.set('state', params.state);
        location.replace(u.toString()); return;
      }
      card.innerHTML = `
        <div class="link-arrow"><div class="applogo">${logo(c)}</div><div class="dots"><i></i><i></i><i></i></div><div class="applogo" style="color:var(--accent)">C</div></div>
        <h1 style="text-align:center">Continue to ${esc(c.name)}</h1>
        <p class="sub" style="text-align:center">Sign in with your Cairn Account to continue to <b>${esc(c.domain)}</b>.</p>
        <a class="btn primary" id="go">Sign in</a><a class="btn ghost" id="new">Create a Cairn Account</a>`;
      const ret = encodeURIComponent('consent.html?' + q.toString());
      card.querySelector('#go').href = './?return=' + ret;
      card.querySelector('#new').href = './?mode=signup&return=' + ret;
      return;
    }
    const nm = me.name || me.email.split('@')[0];
    const scopes = req.scopes.filter(s => s.id !== 'openid');
    card.innerHTML = `
      <div class="link-arrow"><div class="applogo">${logo(c)}</div><div class="dots"><i></i><i></i><i></i></div><div class="avatar sm" style="width:48px;height:48px">${esc(nm[0].toUpperCase())}</div></div>
      <h1 style="text-align:center">Continue to ${esc(c.name)}</h1>
      <p class="sub" style="text-align:center">${esc(c.domain)}${c.firstParty ? ' · <span class="badge">by Cairn</span>' : ''}</p>
      <div class="who"><div class="avatar sm">${esc(nm[0].toUpperCase())}</div><div class="grow"><b>${esc(nm)}</b><small>${esc(me.email)}</small></div><a href="#" id="switch" style="font-size:13px">Switch</a></div>
      <p style="margin-top:18px;color:var(--muted);font-size:14px">${esc(c.name)} will be able to:</p>
      <ul class="scopes"><li>${check}<span>Know who you are on Cairn</span></li>${scopes.map(s => `<li>${check}<span>${esc(s.label)}</span></li>`).join('')}</ul>
      <button class="btn primary" id="allow">Continue as ${esc(nm)}</button>
      <button class="btn ghost" id="deny">Cancel</button>
      <p class="fine">Cairn never shares your password, passkeys or browsing data. Make sure you trust ${esc(c.domain)}.</p>
      <div class="msg" id="m" role="status"></div>`;
    const send = async (approve) => {
      card.querySelectorAll('button').forEach(b => b.disabled = true);
      try { const r = await api('/oauth/approve', { method: 'POST', body: { ...params, approve, via: 'web' } }); location.replace(r.redirect); }
      catch (e) { card.querySelector('#m').textContent = e.message; card.querySelector('#m').className = 'msg err'; card.querySelectorAll('button').forEach(b => b.disabled = false); }
    };
    card.querySelector('#allow').onclick = () => send(true);
    card.querySelector('#deny').onclick = () => send(false);
    card.querySelector('#switch').onclick = async (e) => {
      e.preventDefault(); try { await api('/auth/logout', { method: 'POST' }); } catch {} store.clear(); location.reload();
    };
  }
  main();
})();
