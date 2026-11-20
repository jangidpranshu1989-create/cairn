(() => {
  const { api, store, esc, say, BASE } = window.Cairn;
  const $ = (id) => document.getElementById(id);
  const sdk = BASE + '/sdk/cairn-auth.js';
  const snip = (cid) => `<script src="${sdk}"></script>
<div id="cairn-btn"></div>
<script>
  CairnAuth.init({
    clientId: '${cid}',
    redirectUri: location.origin + location.pathname,
    onSignIn: (user, tokens) => {
      // user = { sub, name, email, email_verified }
      console.log('Signed in as', user.name);
    }
  });
  CairnAuth.renderButton('#cairn-btn', { theme: 'dark' }); // dark | light | amber
</script>`;
  const snip2 = (cid) => `<script src="${sdk}" defer></script>
<div data-cairn-client-id="${cid}" data-theme="light"></div>`;
  const setSnips = (cid) => { $('snippet').textContent = snip(cid); $('snippet2').textContent = snip2(cid); };
  setSnips('YOUR_CLIENT_ID');
  CairnAuth.renderButton('#demo', { theme: 'amber' }).onclick = (e) => { e.stopImmediatePropagation(); alert('This is a preview. Register an app to get a working button.'); };
  document.querySelector('#signedOut a').href = '../accounts/?return=' + encodeURIComponent('../developers/');

  fetch(BASE + '/.well-known/openid-configuration').then(r => r.json()).then(d => {
    const rows = [['Issuer', d.issuer], ['Discovery', d.issuer + '/.well-known/openid-configuration'], ['Authorize', d.authorization_endpoint], ['Token', d.token_endpoint], ['UserInfo', d.userinfo_endpoint], ['JWKS', d.jwks_uri]];
    $('endpoints').innerHTML = rows.map(([k, v]) => `<span>${k}</span><code>${esc(v)}</code>`).join('');
  }).catch(() => {});

  async function loadClients() {
    const { clients } = await api('/oauth/clients');
    $('clients').innerHTML = clients.length ? '' : '<div class="item"><small>No apps yet.</small></div>';
    for (const c of clients) {
      const d = document.createElement('div'); d.className = 'item';
      d.innerHTML = `<div class="grow">${esc(c.name)} <span class="badge">${esc(c.type)}</span><small><code>${esc(c.id)}</code> · ${esc(c.redirectUris.join(', '))}</small></div><button class="btn ghost sm">Snippet</button><button class="btn ghost sm danger">Delete</button>`;
      const [s, del] = d.querySelectorAll('button');
      s.onclick = () => { setSnips(c.id); $('snippet').scrollIntoView({ behavior: 'smooth', block: 'center' }); };
      del.onclick = async () => { if (confirm('Delete ' + c.name + '? Users will be signed out of it.')) { await api('/oauth/clients/' + encodeURIComponent(c.id), { method: 'DELETE' }); loadClients(); } };
      $('clients').append(d);
    }
  }
  $('reg').addEventListener('submit', async (e) => {
    e.preventDefault(); say($('m'), '');
    try {
      const r = await api('/oauth/clients', { method: 'POST', body: { name: $('n').value, homepage: $('h').value, type: $('t').value, redirectUris: $('r').value.split(/\s+/).filter(Boolean) } });
      $('created').classList.remove('hidden');
      $('created').innerHTML = `<div class="kv" style="margin-top:14px"><span>Client ID</span><code>${esc(r.clientId)}</code>${r.clientSecret ? `<span>Client secret</span><code>${esc(r.clientSecret)}</code>` : ''}</div>${r.clientSecret ? '<p class="msg err">Copy the secret now. It will not be shown again. Keep it on your server only.</p>' : ''}`;
      setSnips(r.clientId); e.target.reset(); loadClients();
    } catch (err) { say($('m'), err.message); }
  });
  if (store.get()) api('/auth/me').then(() => { $('signedOut').classList.add('hidden'); $('signedIn').classList.remove('hidden'); loadClients(); }).catch(() => {});
})();
