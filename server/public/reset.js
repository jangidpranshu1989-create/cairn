document.getElementById('f').addEventListener('submit', async (e) => {
  e.preventDefault();
  const m = document.getElementById('m');
  const r = await fetch('/api/auth/reset', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token: new URLSearchParams(location.search).get('t'), password: document.getElementById('p').value }) });
  const d = await r.json();
  m.className = 'msg ' + (r.ok ? 'ok' : 'err');
  m.innerHTML = r.ok ? 'Password updated. <a href="/account.html">Sign in</a>' : d.error;
});
