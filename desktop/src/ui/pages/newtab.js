(() => {
  const q = $('#q'), box = $('#box'), go = $('.go');
  let mode = localStorage.getItem('nt-mode') || 'ask';
  const setMode = (m) => {
    mode = m; localStorage.setItem('nt-mode', m);
    $$('.m').forEach(b => { const on = b.dataset.m === m; b.classList.toggle('on', on); b.setAttribute('aria-selected', on); });
    box.classList.toggle('ask', m === 'ask');
    q.placeholder = m === 'ask' ? 'Ask anything or give Saarthi a task…' : 'Search the web or type a URL';
  };
  $$('.m').forEach(b => b.onclick = () => { setMode(b.dataset.m); q.focus(); });
  setMode(mode);
  const grow = () => { q.style.height = 'auto'; q.style.height = Math.min(180, q.scrollHeight) + 'px'; go.disabled = !q.value.trim(); };
  q.addEventListener('input', grow);
  q.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); box.requestSubmit(); }
    if (e.key === 'Tab' && !e.shiftKey) { e.preventDefault(); setMode(mode === 'ask' ? 'search' : 'ask'); }
  });
  box.addEventListener('submit', (e) => {
    e.preventDefault(); const t = q.value.trim(); if (!t) return;
    if (mode === 'ask') { api('ui:ask', t); q.value = ''; grow(); } else api('nav:go', t);
  });
  const SP = '<path d="M12 3c.6 4.6 2.4 6.4 7 7-4.6.6-6.4 2.4-7 7-.6-4.6-2.4-6.4-7-7 4.6-.6 6.4-2.4 7-7Z"/>';
  const ideas = [
    'Find the best price for an iPhone 16 128GB',
    'Read me the Wikipedia article on the Taj Mahal',
    'Compare Sony WH-1000XM5 prices',
    'Summarize today\u2019s top tech news',
  ];
  $('#ideas').innerHTML = ideas.map(i => `<button class="idea" type="button"><svg class="ic" viewBox="0 0 24 24" width="13" height="13">${SP}</svg>${esc(i)}</button>`).join('');
  $$('.idea').forEach(c => c.onclick = () => { setMode('ask'); q.value = c.textContent; grow(); q.focus(); });

  const defaults = [
    { url: 'https://www.google.com', title: 'Google' }, { url: 'https://www.youtube.com', title: 'YouTube' },
    { url: 'https://www.wikipedia.org', title: 'Wikipedia' }, { url: 'https://www.amazon.in', title: 'Amazon' },
    { url: 'https://mail.google.com', title: 'Gmail' }, { url: 'https://github.com', title: 'GitHub' },
    { url: 'https://www.reddit.com', title: 'Reddit' }, { url: 'https://news.ycombinator.com', title: 'Hacker News' },
  ];
  const nice = (u, t) => { const hh = host(u); const known = defaults.find(d => host(d.url) === hh); if (known) return known.title; const base = hh.split('.').slice(-2, -1)[0] || hh; return base.charAt(0).toUpperCase() + base.slice(1); };
  const site = (u) => { const p = host(u).split('.'); while (p.length > 2 && /^(www\d?|m|mobile|[a-z]{2})$/.test(p[0])) p.shift(); return p.join('.'); };
  api('history:top', 12).then(top => {
    const seen = new Set(); const list = [];
    for (const t of [...top, ...defaults]) { const k = site(t.url); if (seen.has(k)) continue; seen.add(k); list.push(t); if (list.length >= 8) break; }
    $('#tiles').innerHTML = list.map(t => `<a class="tile" href="${esc(t.url)}" title="${esc(host(t.url))}"><div class="ti"><img src="${fav(t.url)}" alt=""></div><span>${esc(nice(t.url, t.title))}</span></a>`).join('');
  }).catch(() => {});
  api('history:list', '', 40).then(h => {
    const seen = new Set(); const out = [];
    for (const x of h) { if (!/^https?:/.test(x.url) || seen.has(x.url) || !x.title) continue; seen.add(x.url); out.push(x); if (out.length >= 4) break; }
    if (out.length) $('#recentList').innerHTML = out.map(x => `<a href="${esc(x.url)}"><img src="${fav(x.url)}" alt=""><span class="t">${esc(x.title)}</span><span class="h">${esc(host(x.url))}</span></a>`).join('');
  }).catch(() => {});
  api('settings:get').then(s => {
    const n = Number(s.shieldTotal || s.blockedTotal || 0);
    $('#shieldN').textContent = n.toLocaleString();
    if (!s.shield) $('.stat span').textContent = 'Shield is off — turn it on in Settings';
  }).catch(() => {});
  const tick = () => {
    const d = new Date();
    $('#dateLbl').textContent = d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' });
    $('#clock').textContent = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
  };
  tick(); setInterval(tick, 15000);
  q.focus();
})();
