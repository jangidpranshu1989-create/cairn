(() => {
  const root = $('#root');
  const LOGO = `<svg width="46" height="46" viewBox="0 0 24 24" fill="none"><path fill="currentColor" d="M4.3 17.4c0-2 3.4-3.3 7.9-3.3 4.4 0 7.5 1.3 7.5 3.3 0 1.8-3 2.9-7.6 2.9-4.7 0-7.8-1.1-7.8-2.9Z"/><path fill="currentColor" d="M6.6 11c0-1.7 2.3-2.9 5.3-2.9 3 0 4.9 1.1 4.9 2.7 0 1.6-2.1 2.6-5.1 2.6-3 0-5.1-1-5.1-2.4Z"/><path fill="var(--agent,#D9C3A0)" d="M9.3 5.75c0-1.3 1.3-2.3 3.1-2.3 1.7 0 2.7.9 2.7 2.1 0 1.2-1.2 2-2.9 2-1.8 0-2.9-.7-2.9-1.8Z"/></svg>`;
  let mode = location.hash === '#signup' ? 'signup' : 'signin';
  let cap = null;

  async function render() {
    const s = await api('cloud:session');
    if (s.signedIn) return renderMe(s.user);
    root.innerHTML = `<div class="logo">${LOGO}</div><h1>${mode === 'signin' ? 'Welcome back' : 'Create your Cairn account'}</h1>
      <p class="muted">${mode === 'signin' ? 'Sign in to pair your phone, approve payments and sync.' : 'Free forever. Pair your phone in seconds.'}</p>
      <div class="tabs"><button data-m="signin" class="${mode === 'signin' ? 'on' : ''}">Sign in</button><button data-m="signup" class="${mode === 'signup' ? 'on' : ''}">Sign up</button></div>
      <form id="f" novalidate>
        ${mode === 'signup' ? '<label>Name<input class="input" name="name" autocomplete="name" required maxlength="80"></label>' : ''}
        <label>Email<input class="input" name="email" type="email" autocomplete="email" required></label>
        <label>Password<input class="input" name="password" type="password" autocomplete="${mode === 'signin' ? 'current-password' : 'new-password'}" required minlength="${mode === 'signup' ? 10 : 8}"></label>
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
    $('#forgot') && ($('#forgot').onclick = async (e) => {
      e.preventDefault();
      const email = $('[name=email]').value.trim(); const err = $('#err') || $('.err');
      const say = (m) => { if (err) err.textContent = m; else alert(m); };
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return say('Enter your email above, then tap Forgot password');
      try { await fetch(base + '/api/auth/forgot', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email }) }); say('If that account exists, a reset link is on its way to ' + email); }
      catch { say("Couldn't reach Cairn Cloud. Check your connection."); }
    });
    $('#f').onsubmit = submit;
    $('[name=' + (mode === 'signup' ? 'name' : 'email') + ']').focus();
  }
  async function loadCap() {
    try { cap = await api('cloud:captcha'); $('#capImg').innerHTML = cap.svg; $('#cap').classList.add('on'); }
    catch { $('#err').textContent = 'Could not reach Cairn Cloud. Check the server URL in Settings → Phone & sync.'; }
  }
  async function submit(e) {
    e.preventDefault();
    const f = e.target, err = $('#err'), go = $('#go');
    err.textContent = '';
    const email = f.email.value.trim(), password = f.password.value;
    if (!/^\S+@\S+\.\S+$/.test(email)) return err.textContent = 'Enter a valid email address';
    if (mode === 'signup' && (password.length < 10 || !/\p{L}/u.test(password) || !/\p{N}/u.test(password))) return err.textContent = 'Password needs 10+ characters with a letter and a number';
    if (mode === 'signup' && !f.name.value.trim()) return err.textContent = 'Enter your name';
    const captcha = cap ? { captchaToken: cap.token, captchaAnswer: f.captcha.value.trim() } : {};
    go.disabled = true; go.textContent = 'Please wait…';
    try {
      const r = mode === 'signin' ? await api('cloud:login', email, password, captcha) : await api('cloud:signup', { name: f.name.value.trim(), email, password, ...captcha });
      if (r.error) { err.textContent = r.error; if (r.captcha || cap) { await loadCap(); f.captcha.value = ''; } }
      else { toast('Signed in'); render(); return; }
    } catch (x) { err.textContent = /fetch/i.test(cleanErr(x)) ? 'Could not reach Cairn Cloud.' : cleanErr(x); }
    go.disabled = false; go.textContent = mode === 'signin' ? 'Sign in' : 'Create account';
  }
  async function renderMe(u) {
    const base = (await api('cloud:base')).replace(/\/$/, '');
    root.innerHTML = `<div class="card"><div class="me"><div class="av">${esc((u.name || u.email)[0].toUpperCase())}</div><div style="flex:1;min-width:0"><h2>${esc(u.name || 'Cairn user')}</h2><div class="muted" style="overflow:hidden;text-overflow:ellipsis">${esc(u.email)}</div></div></div>
      <div class="perks"><div>${ICON('check')} Payment approvals on your phone</div><div>${ICON('check')} Send PDFs &amp; articles to your phone</div><div>${ICON('check')} Cairn Cloud AI quota</div></div>
      <div style="display:flex;gap:8px;flex-wrap:wrap"><a class="btn primary" href="cairn://settings#phone">${ICON('phone')} Pair a phone</a><button class="btn" id="pk">${ICON('key')} Manage passkeys</button><button class="btn danger" id="out">Sign out</button></div></div>
      <div class="card" style="margin-top:16px"><h3 style="margin:0 0 6px">Delete account</h3><p class="muted" style="margin:0 0 12px">Permanently deletes your Cairn account, sessions, passkeys, paired devices, pending transfers and synced data from our server. Local browsing data on this computer is not affected.</p>
      <form id="del" style="display:flex;gap:8px;flex-wrap:wrap"><input class="input" type="password" name="pw" placeholder="Confirm with your password" autocomplete="current-password" style="flex:1;min-width:200px"><button class="btn danger" type="submit">Delete permanently</button></form><div class="err" id="delErr" style="margin-top:8px"></div></div>`;
    $('#pk').onclick = () => api('nav:go', base + '/accounts/');
    $('#out').onclick = async () => { await api('cloud:logout'); toast('Signed out'); render(); };
    $('#del').onsubmit = async (e) => {
      e.preventDefault(); const pw = e.target.pw.value; $('#delErr').textContent = '';
      if (!pw) return $('#delErr').textContent = 'Enter your password to confirm';
      if (!confirm('Delete your Cairn account permanently? This cannot be undone.')) return;
      try { await api('cloud:deleteAccount', pw); toast('Account deleted'); render(); } catch (x) { $('#delErr').textContent = cleanErr(x); }
    };
  }
  render();
})();
