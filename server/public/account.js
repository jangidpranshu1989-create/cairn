(() => {
  const $ = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  const handoff = params.get('handoff') === 'desktop';
  let mode = params.get('mode') === 'signup' ? 'signup' : 'signin';
  let captchaToken = null;

  const store = {
    get: () => JSON.parse(localStorage.getItem('cairn.auth') || 'null'),
    set: (v) => localStorage.setItem('cairn.auth', JSON.stringify(v)),
    clear: () => localStorage.removeItem('cairn.auth'),
  };

  async function api(path, opts = {}) {
    const auth = store.get();
    const headers = { 'Content-Type': 'application/json', ...(auth && !opts.noAuth ? { Authorization: 'Bearer ' + auth.accessToken } : {}) };
    let r = await fetch('/api' + path, { ...opts, headers, body: opts.body ? JSON.stringify(opts.body) : undefined });
    if (r.status === 401 && auth && !opts.noAuth && !opts.retried) {
      const rr = await fetch('/api/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: auth.refreshToken }) });
      if (rr.ok) { store.set({ ...auth, ...(await rr.json()) }); return api(path, { ...opts, retried: true }); }
      store.clear();
    }
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw Object.assign(new Error(data.error || 'Something went wrong'), { data });
    return data;
  }

  const say = (el, text, ok) => { el.textContent = text || ''; el.className = 'msg ' + (ok ? 'ok' : 'err'); };

  async function loadCaptcha() {
    const c = await api('/auth/captcha', { noAuth: true });
    captchaToken = c.token; $('captchaImg').innerHTML = c.svg; $('captchaAnswer').value = '';
    $('captcha').classList.add('show');
  }

  function render() {
    const s = mode === 'signup';
    $('title').textContent = s ? 'Create your account' : 'Welcome back';
    $('subtitle').textContent = s ? 'One account for your browser, agent and phone.' : 'Sign in to sync your browser and pair your phone.';
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
    showProfile();
  }

  $('form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('submit'); btn.disabled = true; say($('msg'), '');
    try {
      const body = { email: $('email').value, password: $('password').value };
      if ($('captcha').classList.contains('show')) Object.assign(body, { captchaToken, captchaAnswer: $('captchaAnswer').value });
      if (mode === 'signup') body.name = $('name').value;
      finish(await api(mode === 'signup' ? '/auth/signup' : '/auth/login', { method: 'POST', body, noAuth: true }));
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
    await api('/auth/forgot', { method: 'POST', body: { email: $('email').value }, noAuth: true });
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

  async function showProfile() {
    try {
      const me = await api('/auth/me');
      $('auth').classList.add('hidden'); $('profile').classList.remove('hidden');
      $('who').textContent = `${me.user.name || 'Signed in'} · ${me.user.email}`;
      $('passkeys').innerHTML = me.passkeys.length ? '' : '<div class="item"><small>No passkeys yet</small></div>';
      for (const p of me.passkeys) {
        const d = document.createElement('div'); d.className = 'item';
        d.innerHTML = `<div>${escapeHtml(p.name)}<small>Added ${new Date(p.created_at).toLocaleDateString()}</small></div><button>Remove</button>`;
        d.querySelector('button').onclick = async () => { await api('/passkeys/' + encodeURIComponent(p.id), { method: 'DELETE' }); showProfile(); };
        $('passkeys').append(d);
      }
      $('sessions').innerHTML = '';
      for (const s of me.sessions) {
        const d = document.createElement('div'); d.className = 'item';
        d.innerHTML = `<div>${escapeHtml((s.user_agent || 'Unknown device').slice(0, 48))}${s.current ? ' · this device' : ''}<small>Last active ${new Date(s.last_used_at).toLocaleString()}</small></div>${s.current ? '' : '<button>Revoke</button>'}`;
        d.querySelector('button')?.addEventListener('click', async () => { await api('/auth/sessions/' + s.id, { method: 'DELETE' }); showProfile(); });
        $('sessions').append(d);
      }
    } catch { store.clear(); $('auth').classList.remove('hidden'); $('profile').classList.add('hidden'); }
  }

  $('addPasskey').onclick = async () => {
    try {
      const opts = await api('/passkeys/register/options', { method: 'POST' });
      const response = await SimpleWebAuthnBrowser.startRegistration({ optionsJSON: opts });
      await api('/passkeys/register/verify', { method: 'POST', body: { response, name: navigator.platform || 'Passkey' } });
      say($('msg2'), 'Passkey added. You can now sign in without a password.', true); showProfile();
    } catch (err) { say($('msg2'), err.name === 'NotAllowedError' ? 'Passkey setup was cancelled' : err.message); }
  };
  $('logout').onclick = async () => { try { await api('/auth/logout', { method: 'POST' }); } catch {} store.clear(); location.reload(); };

  function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

  if (params.get('verified')) say($('msg'), 'Email verified. Thank you.', true);
  render();
  if (store.get() && !handoff) showProfile();
})();
