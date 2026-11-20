(() => {
  const { api, store, esc, say } = window.Cairn;
  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const handoff = params.get('handoff') === 'desktop';
  // After sign-in, go back to the consent screen (same-origin relative path only).
  const ret = (() => { const r = params.get('return') || ''; return /^(consent\.html\?\S*|\.\.\/developers\/)$/.test(r) ? r : ''; })();
  let mode = params.get('mode') === 'signup' ? 'signup' : 'signin';
  let captchaToken = null;

  async function loadCaptcha() {
    try {
      const c = await api('/auth/captcha', { noAuth: true });
      captchaToken = c.token; $('captchaImg').innerHTML = c.svg; $('captchaAnswer').value = '';
      $('captcha').classList.add('show');
    } catch (e) { say($('msg'), e.message); }
  }
  function render() {
    const s = mode === 'signup';
    $('title').textContent = s ? 'Create your Cairn Account' : 'Sign in';
    $('subtitle').textContent = s ? 'One account for your browser, phone, agent and apps.' : (ret ? 'Sign in to continue to the app.' : 'Use your Cairn Account on any device, anywhere.');
    $('nameWrap').classList.toggle('hidden', !s);
    $('submit').textContent = s ? 'Create account' : 'Sign in';
    $('password').autocomplete = s ? 'new-password' : 'current-password';
    $('switchText').textContent = s ? 'Already have an account?' : 'New to Cairn?';
    $('switchLink').textContent = s ? 'Sign in' : 'Create an account';
    if (s) loadCaptcha(); else $('captcha').classList.remove('show');
  }
  function finish(data) {
    store.set({ accessToken: data.accessToken, refreshToken: data.refreshToken, user: data.user });
    if (handoff) {
      const h = new URLSearchParams({ access: data.accessToken, refresh: data.refreshToken, user: JSON.stringify(data.user) });
      location.href = 'cairn://auth-callback#' + h.toString();
      return;
    }
    if (ret) { location.href = ret; return; }
    showProfile();
  }

  $('form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('submit'); btn.disabled = true; say($('msg'), '');
    try {
      const body = { email: $('email').value.trim(), password: $('password').value };
      if ($('captcha').classList.contains('show')) Object.assign(body, { captchaToken, captchaAnswer: $('captchaAnswer').value });
      if (mode === 'signup') body.name = $('name').value;
      const data = await api(mode === 'signup' ? '/auth/signup' : '/auth/login', { method: 'POST', body, noAuth: true });
      finish(data);
    } catch (err) {
      say($('msg'), err.message);
      if (err.data?.captcha || mode === 'signup') loadCaptcha();
    } finally { btn.disabled = false; }
  });
  $('captchaRefresh').onclick = loadCaptcha;
  $('switchLink').onclick = (e) => { e.preventDefault(); mode = mode === 'signup' ? 'signin' : 'signup'; say($('msg'), ''); render(); };
  $('forgot').onclick = async (e) => {
    e.preventDefault();
    if (!$('email').value) return say($('msg'), 'Enter your email above first');
    try { await api('/auth/forgot', { method: 'POST', body: { email: $('email').value.trim() }, noAuth: true }); } catch {}
    say($('msg'), 'If that email has an account, a reset link is on its way.', true);
  };
  $('passkeyLogin').onclick = async () => {
    if (!window.PublicKeyCredential) return say($('msg'), 'Passkeys are not supported in this browser');
    try {
      const opts = await api('/passkeys/login/options', { method: 'POST', noAuth: true });
      const response = await SimpleWebAuthnBrowser.startAuthentication({ optionsJSON: opts });
      finish(await api('/passkeys/login/verify', { method: 'POST', body: { flow: opts.flow, response }, noAuth: true }));
    } catch (err) { say($('msg'), err.name === 'NotAllowedError' ? 'Passkey sign-in was cancelled' : err.message); }
  };

  // ---------- Signed-in views ----------
  document.querySelectorAll('[role=tab]').forEach(b => b.onclick = () => {
    document.querySelectorAll('[role=tab]').forEach(x => x.setAttribute('aria-selected', String(x === b)));
    document.querySelectorAll('[data-panel]').forEach(p => p.classList.toggle('hidden', p.dataset.panel !== b.dataset.tab));
    if (b.dataset.tab === 'apps') loadApps();
  });
  const fmt = (t) => t ? new Date(t).toLocaleString() : 'never';
  function device(ua) {
    ua = ua || '';
    const os = /Android/i.test(ua) ? 'Android' : /iPhone|iPad/i.test(ua) ? 'iOS' : /Windows/i.test(ua) ? 'Windows' : /Mac OS/i.test(ua) ? 'macOS' : /Linux/i.test(ua) ? 'Linux' : 'Unknown device';
    const app = /Cairn/i.test(ua) ? 'Cairn' : /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Firefox\//.test(ua) ? 'Firefox' : /Safari\//.test(ua) ? 'Safari' : /okhttp|Dalvik/i.test(ua) ? 'Cairn for Android' : 'App';
    return `${app} on ${os}`;
  }
  async function showProfile() {
    let me;
    try { me = await api('/auth/me'); } catch { store.clear(); $('auth').classList.remove('hidden'); $('profile').classList.add('hidden'); return; }
    $('auth').classList.add('hidden'); $('profile').classList.remove('hidden');
    const nm = me.user.name || me.user.email.split('@')[0];
    $('whoName').textContent = nm; $('whoEmail').textContent = me.user.email + (me.user.emailVerified === false ? ' · not verified' : '');
    $('avatar').textContent = (nm[0] || 'C').toUpperCase(); $('editName').value = me.user.name || '';
    $('passkeys').innerHTML = me.passkeys.length ? '' : '<div class="item"><small>No passkeys yet. Add one to sign in without a password.</small></div>';
    for (const p of me.passkeys) {
      const d = document.createElement('div'); d.className = 'item';
      d.innerHTML = `<div class="grow">${esc(p.name)}<small>Added ${new Date(p.created_at).toLocaleDateString()}</small></div><button class="btn ghost sm danger">Remove</button>`;
      d.querySelector('button').onclick = async () => { await api('/passkeys/' + encodeURIComponent(p.id), { method: 'DELETE' }); showProfile(); };
      $('passkeys').append(d);
    }
    $('sessions').innerHTML = '';
    for (const s of me.sessions) {
      const d = document.createElement('div'); d.className = 'item';
      d.innerHTML = `<div class="grow">${esc(device(s.user_agent))}${s.current ? ' <span class="badge">This device</span>' : ''}<small>Last active ${esc(fmt(s.last_used_at))}${s.ip ? ' · ' + esc(s.ip) : ''}</small></div>${s.current ? '' : '<button class="btn ghost sm danger">Sign out</button>'}`;
      d.querySelector('button')?.addEventListener('click', async () => { await api('/auth/sessions/' + encodeURIComponent(s.id), { method: 'DELETE' }); showProfile(); });
      $('sessions').append(d);
    }
  }
  async function loadApps() {
    const el = $('apps'); el.innerHTML = '<div class="item"><small>Loading…</small></div>';
    try {
      const { apps } = await api('/oauth/consents');
      el.innerHTML = apps.length ? '' : '<div class="item"><small>No apps yet. When you use Continue with Cairn, the app shows up here.</small></div>';
      for (const a of apps) {
        const d = document.createElement('div'); d.className = 'item';
        d.innerHTML = `<div class="applogo" style="width:38px;height:38px;border-radius:10px;font-size:15px">${a.logo ? `<img alt="" src="${esc(a.logo)}">` : esc((a.name[0] || '?').toUpperCase())}</div><div class="grow">${esc(a.name)}<small>${esc(a.domain)} · ${esc(a.scope.replace(/\bopenid\b ?/, '').trim() || 'basic sign-in')} · last used ${esc(fmt(a.lastUsedAt))}</small></div><button class="btn ghost sm danger">Remove access</button>`;
        d.querySelector('button').onclick = async () => { await api('/oauth/consents/' + encodeURIComponent(a.clientId), { method: 'DELETE' }); loadApps(); };
        el.append(d);
      }
    } catch (e) { el.innerHTML = `<div class="item"><small>${esc(e.message)}</small></div>`; }
  }
  $('saveName').onclick = async () => { try { await api('/auth/me', { method: 'PATCH', body: { name: $('editName').value } }); showProfile(); } catch (e) { alert(e.message); } };
  $('addPasskey').onclick = async () => {
    try {
      const opts = await api('/passkeys/register/options', { method: 'POST' });
      const response = await SimpleWebAuthnBrowser.startRegistration({ optionsJSON: opts });
      await api('/passkeys/register/verify', { method: 'POST', body: { response, name: navigator.userAgentData?.platform || navigator.platform || 'Passkey' } });
      say($('msg2'), 'Passkey added. You can now sign in without a password.', true); showProfile();
    } catch (err) { say($('msg2'), err.name === 'NotAllowedError' ? 'Passkey setup was cancelled' : err.message); }
  };
  $('pwForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try { await api('/auth/change-password', { method: 'POST', body: { current: $('pwCur').value, next: $('pwNew').value } }); say($('msg2'), 'Password changed. Other devices were signed out.', true); e.target.reset(); }
    catch (err) { say($('msg2'), err.message); }
  });
  $('logout').onclick = async () => { try { await api('/auth/logout', { method: 'POST' }); } catch {} store.clear(); location.href = './'; };

  if (params.get('verified')) say($('msg'), 'Email verified. Thank you.', true);
  if (params.get('signout')) { (async () => { try { await api('/auth/logout', { method: 'POST' }); } catch {} store.clear(); })(); }
  render();
  if (store.get() && !handoff && !params.get('signout')) { if (ret) location.href = ret; else showProfile(); }
})();
