(() => {
  const root = $('#root');
  const LOGO = `<svg width="46" height="46" viewBox="0 0 32 32" fill="none"><path d="M16 2.5 27.7 9.25v13.5L16 29.5 4.3 22.75V9.25L16 2.5Z" stroke="var(--accent)" stroke-width="2"/><path d="M20.5 11.5c-1-1.3-2.6-2-4.4-2-2.6 0-4.4 1.4-4.4 3.3 0 4.4 9 2.3 9 6.6 0 2-1.9 3.3-4.6 3.3-1.9 0-3.6-.8-4.6-2.2" stroke="var(--text)" stroke-width="2.2" stroke-linecap="round"/></svg>`;
  let mode = location.hash === '#signup' ? 'signup' : 'signin';
  let cap = null;

  async function render() {
    const s = await api('cloud:session');
    if (s.signedIn) return renderMe(s.user);
    root.innerHTML = `<div class="logo">${LOGO}</div><h1>${mode === 'signin' ? 'Welcome back' : 'Create your Saarthi account'}</h1>
      <p class="muted">${mode === 'signin' ? 'Sign in to pair your phone, approve payments and sync.' : 'Free forever. Pair your phone in seconds.'}</p>
      <div class="tabs"><button data-m="signin" class="${mode === 'signin' ? 'on' : ''}">Sign in</button><button data-m="signup" class="${mode === 'signup' ? 'on' : ''}">Sign up</button></div>
      <form id="f" novalidate>
        ${mode === 'signup' ? '<label>Name<input class="input" name="name" autocomplete="name" required maxlength="80"></label>' : ''}
        <label>Email<input class="input" name="email" type="email" autocomplete="email" required></label>
        <label>Password<input class="input" name="password" type="password" autocomplete="${mode === 'signin' ? 'current-password' : 'new-password'}" required minlength="8"></label>
        ${mode === 'signup' ? '<small class="muted" style="margin-top:-6px">At least 8 characters with a letter and a number.</small>' : ''}
        <div class="cap ${mode === 'signup' ? 'on' : ''}" id="cap"><div class="img" id="capImg"></div><input class="input" name="captcha" placeholder="Type the characters" autocomplete="off" style="flex:1"><button type="button" class="btn sm" id="capNew" title="New captcha">↻</button></div>
        <div class="err" id="err" role="alert"></div>
        <button class="btn primary big" id="go">${mode === 'signin' ? 'Sign in' : 'Create account'}</button>
        ${mode === 'signin' ? '<a class="small" id="forgot" href="#" style="text-align:center">Forgot password?</a>' : ''}
      </form>
      <div class="or">or</div>
      <button class="btn big" id="pk" style="width:100%">${ICON('key')} Sign in with a passkey</button>
      <p class="muted small" style="text-align:center;margin-top:18px">By continuing you agree to the <a href="#" data-link="terms">Terms</a> and <a href="#" data-link="privacy">Privacy Policy</a>.</p>`;
    $$('.tabs button').forEach(b => b.onclick = () => { mode = b.dataset.m; cap = null; render(); });
    $('#capNew').onclick = loadCap;
    if (mode === 'signup') loadCap();
    $('#pk').onclick = () => api('cloud:passkeyPage');
    const base = (await api('cloud:base')).replace(/\/$/, '');
    $$('[data-link]').forEach(a => a.href = `${base}/${a.dataset.link}.html`);
    $('#forgot') && ($('#forgot').href = base + '/reset.html');
    $('#f').onsubmit = submit;
    $('[name=' + (mode === 'signup' ? 'name' : 'email') + ']').focus();
  }
  async function loadCap() {
    try { cap = await api('cloud:captcha'); $('#capImg').innerHTML = cap.svg; $('#cap').classList.add('on'); }
    catch { $('#err').textContent = 'Could not reach Saarthi Cloud. Check the server URL in Settings → Phone & sync.'; }
  }
  async function submit(e) {
    e.preventDefault();
    const f = e.target, err = $('#err'), go = $('#go');
    err.textContent = '';
    const email = f.email.value.trim(), password = f.password.value;
    if (!/^\S+@\S+\.\S+$/.test(email)) return err.textContent = 'Enter a valid email address';
    if (mode === 'signup' && (password.length < 8 || !/[a-z]/i.test(password) || !/\d/.test(password))) return err.textContent = 'Password needs 8+ characters with a letter and a number';
    if (mode === 'signup' && !f.name.value.trim()) return err.textContent = 'Enter your name';
    const captcha = cap ? { captchaToken: cap.token, captchaAnswer: f.captcha.value.trim() } : {};
    go.disabled = true; go.textContent = 'Please wait…';
    try {
      const r = mode === 'signin' ? await api('cloud:login', email, password, captcha) : await api('cloud:signup', { name: f.name.value.trim(), email, password, ...captcha });
      if (r.error) { err.textContent = r.error; if (r.captcha || cap) { await loadCap(); f.captcha.value = ''; } }
      else { toast('Signed in'); render(); return; }
    } catch (x) { err.textContent = /fetch/i.test(cleanErr(x)) ? 'Could not reach Saarthi Cloud.' : cleanErr(x); }
    go.disabled = false; go.textContent = mode === 'signin' ? 'Sign in' : 'Create account';
  }
  async function renderMe(u) {
    const base = (await api('cloud:base')).replace(/\/$/, '');
    root.innerHTML = `<div class="card"><div class="me"><div class="av">${esc((u.name || u.email)[0].toUpperCase())}</div><div style="flex:1;min-width:0"><h2>${esc(u.name || 'Saarthi user')}</h2><div class="muted" style="overflow:hidden;text-overflow:ellipsis">${esc(u.email)}</div></div></div>
      <div class="perks"><div>${ICON('check')} Payment approvals on your phone</div><div>${ICON('check')} Send PDFs &amp; articles to your phone</div><div>${ICON('check')} Saarthi Cloud AI quota</div></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap"><a class="btn primary" href="saarthi://settings#phone">${ICON('phone')} Pair a phone</a><button class="btn" id="pk">${ICON('key')} Manage passkeys</button><button class="btn danger" id="out">Sign out</button></div></div>`;
    $('#pk').onclick = () => api('nav:go', base + '/account.html');
    $('#out').onclick = async () => { await api('cloud:logout'); toast('Signed out'); render(); };
  }
  render();
})();
