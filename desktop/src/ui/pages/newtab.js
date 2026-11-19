(() => {
  const h = new Date().getHours();
  const g = h < 5 ? 'Late night, still curious?' : h < 12 ? 'Suprabhat — good morning' : h < 17 ? 'Good afternoon' : h < 21 ? 'Good evening' : 'Shubh ratri — good night';
  $('#greet').textContent = g;
  let mode = localStorage.getItem('nt-mode') || 'ask';
  const q = $('#q'), box = $('#box');
  const setMode = (m) => { mode = m; localStorage.setItem('nt-mode', m); $$('.m').forEach(b => b.classList.toggle('on', b.dataset.m === m)); box.classList.toggle('search', m === 'search');
    q.placeholder = m === 'ask' ? 'Mujhe iPhone 16 sabse saste rate me chahiye…' : 'Search the web or type a URL'; };
  $$('.m').forEach(b => b.onclick = () => { setMode(b.dataset.m); q.focus(); });
  setMode(mode);
  const grow = () => { q.style.height = 'auto'; q.style.height = Math.min(160, q.scrollHeight) + 'px'; };
  q.addEventListener('input', grow);
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); box.requestSubmit(); } if (e.key === 'Tab' && !e.shiftKey && !q.value) { e.preventDefault(); setMode(mode === 'ask' ? 'search' : 'ask'); } });
  box.addEventListener('submit', (e) => {
    e.preventDefault(); const t = q.value.trim(); if (!t) return;
    if (mode === 'ask') { api('ui:ask', t); q.value = ''; grow(); } else api('nav:go', t);
  });
  const ideas = [
    'Find the cheapest iPhone 16 128GB from a trusted store',
    'Wikipedia se Taj Mahal ka article padh ke sunao',
    'Compare Sony WH-1000XM5 prices across India',
    'Summarize this week\'s top tech news',
    'Best rated air fryer under ₹6000',
  ];
  $('#chips').innerHTML = ideas.map(i => `<button class="chip" type="button">${esc(i)}</button>`).join('');
  $$('.chip').forEach(c => c.onclick = () => { setMode('ask'); q.value = c.textContent; grow(); q.focus(); });
  const defaults = [
    { url: 'https://www.wikipedia.org', title: 'Wikipedia' }, { url: 'https://www.youtube.com', title: 'YouTube' },
    { url: 'https://www.amazon.in', title: 'Amazon' }, { url: 'https://www.flipkart.com', title: 'Flipkart' },
    { url: 'https://news.google.com', title: 'News' }, { url: 'https://github.com', title: 'GitHub' },
  ];
  api('history:top', 8).then(top => {
    const seen = new Set(top.map(t => host(t.url)));
    const list = [...top, ...defaults.filter(d => !seen.has(host(d.url)))].slice(0, 8);
    $('#tiles').innerHTML = list.map(t => `<a class="tile" href="${esc(t.url)}"><div class="ti"><img src="${fav(t.url)}" alt=""></div><span>${esc(host(t.url).split('.')[0].replace(/^./, c => c.toUpperCase()))}</span></a>`).join('');
  }).catch(() => {});
  api('settings:get').then(s => { $('#shield').textContent = s.shield ? 'Shield on · ads & trackers blocked' : 'Shield off'; });
  q.focus();
})();
