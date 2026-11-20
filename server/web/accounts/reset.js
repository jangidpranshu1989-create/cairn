document.getElementById('f').addEventListener('submit', async (e) => {
  e.preventDefault();
  const m = document.getElementById('m');
  try {
    await Cairn.api('/auth/reset', { method: 'POST', noAuth: true, body: { token: new URLSearchParams(location.search).get('t'), password: document.getElementById('p').value } });
    m.className = 'msg ok'; m.innerHTML = 'Password updated. <a href="./">Sign in</a>';
  } catch (err) { Cairn.say(m, err.message); }
});
