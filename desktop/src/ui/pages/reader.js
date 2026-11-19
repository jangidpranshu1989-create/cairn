(async () => {
  const a = await api('reader:get'); const el = $('#a');
  if (!a) { el.innerHTML = '<h1>Nothing to read</h1><p class="muted">Open an article and choose Reader view from the page tools.</p>'; return; }
  document.title = a.title; document.documentElement.lang = a.lang || 'en';
  const words = a.text.split(/\s+/).length;
  el.innerHTML = `<h1>${esc(a.title)}</h1><div class="meta"><a href="${esc(a.url)}">${esc(host(a.url))}</a>${a.byline ? `<span>${esc(a.byline)}</span>` : ''}<span>${Math.max(1, Math.round(words / 220))} min read</span></div>` +
    a.text.split('\n').filter(Boolean).map(p => p.length < 90 && !/[.!?।:]$/.test(p) ? `<h2>${esc(p)}</h2>` : `<p>${esc(p)}</p>`).join('');
  let size = Number(localStorage.getItem('rsize') || 18);
  const setSize = (d) => { size = Math.min(26, Math.max(14, size + d)); el.style.fontSize = size + 'px'; localStorage.setItem('rsize', size); };
  setSize(0);
  $('#minus').onclick = () => setSize(-1); $('#plus').onclick = () => setSize(1);
  if (localStorage.getItem('rserif')) document.body.classList.add('serif');
  $('#font').onclick = () => { document.body.classList.toggle('serif'); document.body.classList.contains('serif') ? localStorage.setItem('rserif', 1) : localStorage.removeItem('rserif'); };
  $('#listen').onclick = () => api('page:tool', 'read');
})();
