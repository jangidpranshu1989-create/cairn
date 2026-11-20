// Cairn website — small, dependency-free interactions.
(() => {
  const API = 'https://cairn-browser.pplx.app/port/8787'; // Cairn Cloud (server/), handles /api/public/contact
  const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
  document.documentElement.classList.remove('no-js');
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Header
  const hdr = $('.hdr');
  const onScroll = () => hdr && hdr.classList.toggle('solid', scrollY > 12 || document.body.classList.contains('menu-open'));
  addEventListener('scroll', onScroll, { passive: true }); onScroll();
  const mb = $('.menu-btn');
  if (mb) mb.addEventListener('click', () => { const o = document.body.classList.toggle('menu-open'); mb.setAttribute('aria-expanded', o); onScroll(); });
  $$('.mnav a').forEach(a => a.addEventListener('click', () => document.body.classList.remove('menu-open')));
  addEventListener('keydown', (e) => { if (e.key === 'Escape') document.body.classList.remove('menu-open'); });

  // Reveal on scroll
  const io = new IntersectionObserver((es) => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { rootMargin: '0px 0px -8% 0px', threshold: .08 });
  $$('.rv').forEach(el => io.observe(el));

  // Hero stage: tilted browser flattens as you scroll
  const frame = $('.stage .frame');
  if (frame && !reduce) {
    const upd = () => { const r = frame.getBoundingClientRect(); const p = Math.min(1, Math.max(0, 1 - (r.top - 80) / (innerHeight * .7)));
      frame.style.setProperty('--tilt', (14 * (1 - p)).toFixed(2) + 'deg'); frame.style.setProperty('--sc', (.94 + .06 * p).toFixed(3)); };
    addEventListener('scroll', upd, { passive: true }); addEventListener('resize', upd); upd();
  } else if (frame) { frame.style.setProperty('--tilt', '0deg'); frame.style.setProperty('--sc', '1'); }

  // Sticky agent flow
  const steps = $$('.fstep'), vis = $$('.flow-vis [data-v]'), prog = $('.prog i');
  if (steps.length) {
    let cur = -1;
    const set = (i) => { if (i === cur) return; cur = i; steps.forEach((s, k) => s.classList.toggle('on', k === i)); vis.forEach(v => v.classList.toggle('on', +v.dataset.v === i)); };
    const tick = () => {
      const mid = innerHeight * .5; let best = 0, bd = 1e9;
      steps.forEach((s, i) => { const r = s.getBoundingClientRect(); const d = Math.abs(r.top + r.height / 2 - mid); if (d < bd) { bd = d; best = i; } });
      set(best);
      if (prog) { const f = steps[0].getBoundingClientRect().top, l = steps[steps.length - 1].getBoundingClientRect().bottom; const p = Math.min(1, Math.max(0, (mid - f) / (l - f))); prog.parentElement.parentElement && (prog.style.height = (p * 100).toFixed(1) + '%'); }
    };
    addEventListener('scroll', tick, { passive: true }); addEventListener('resize', tick); tick();
  }

  // FAQ: animated accordions
  $$('.faq details').forEach(d => {
    const s = $('summary', d), a = $('.a', d);
    s.addEventListener('click', (e) => {
      if (reduce) return; e.preventDefault();
      if (d.open) { const h = a.scrollHeight; a.animate([{ height: h + 'px' }, { height: '0px' }], { duration: 260, easing: 'ease-out' }).onfinish = () => d.open = false; }
      else { d.open = true; const h = a.scrollHeight; a.animate([{ height: '0px' }, { height: h + 'px' }], { duration: 300, easing: 'ease-out' }); }
    });
  });

  // Download: highlight the visitor's platform
  const ua = navigator.userAgent, plat = (navigator.userAgentData?.platform || navigator.platform || '').toLowerCase();
  const os = /android/i.test(ua) ? 'android' : /iphone|ipad/i.test(ua) ? 'ios' : /mac/.test(plat) || /Mac OS X/.test(ua) ? 'mac' : /win/.test(plat) || /Windows/.test(ua) ? 'win' : /linux/.test(plat) ? 'linux' : 'win';
  $$('.dl[data-os]').forEach(c => c.classList.toggle('mine', c.dataset.os === os));
  const names = { win: 'Windows', mac: 'macOS', linux: 'Linux', android: 'Android', ios: 'your computer' };
  $$('[data-os-name]').forEach(el => el.textContent = names[os]);
  $$('[data-os-link]').forEach(a => { const t = $(`.dl[data-os="${os === 'ios' ? 'win' : os}"] .files a`); if (t) a.href = t.href; });

  // Forms → Cairn Cloud (falls back to email if the server is unreachable)
  $$('form[data-api]').forEach(f => {
    const msg = $('.form-msg', f), btn = $('button[type=submit]', f);
    f.addEventListener('submit', async (e) => {
      e.preventDefault(); msg.className = 'form-msg'; msg.textContent = '';
      const data = Object.fromEntries(new FormData(f)); data.kind = f.dataset.api;
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email || '')) { msg.className = 'form-msg err'; msg.textContent = 'Please enter a valid email address.'; return; }
      btn.disabled = true; const label = btn.innerHTML; btn.textContent = 'Sending…';
      try {
        const r = await fetch(API + '/api/public/contact', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data), signal: AbortSignal.timeout(12000) });
        const j = await r.json().catch(() => ({}));
        if (!r.ok) throw Object.assign(new Error(j.error || 'Something went wrong'), { user: true });
        f.reset(); msg.className = 'form-msg ok'; msg.textContent = f.dataset.api === 'waitlist' ? 'You are on the list. We will email you when Pro opens.' : 'Thanks — we usually reply within one working day.';
      } catch (err) {
        msg.className = 'form-msg err';
        if (err.user) msg.textContent = err.message;
        else { const sub = encodeURIComponent(f.dataset.api === 'waitlist' ? 'Cairn Pro waitlist' : 'Cairn: ' + (data.topic || 'Hello')); const body = encodeURIComponent(data.message || 'Please add me to the Pro waitlist.');
          msg.innerHTML = `Could not reach our server. <a class="tlink" href="https://github.com/jangidpranshu1989-create/cairn/issues/new?title=${sub}&body=${body}">Open a GitHub issue instead</a>`; }
      } finally { btn.disabled = false; btn.innerHTML = label; }
    });
  });

  // Legal pages: highlight current section in the table of contents
  const toc = $$('.toc a');
  if (toc.length) {
    const map = new Map(toc.map(a => [a.getAttribute('href').slice(1), a]));
    const tio = new IntersectionObserver((es) => es.forEach(e => { if (e.isIntersecting) { toc.forEach(a => a.classList.remove('on')); map.get(e.target.id)?.classList.add('on'); } }), { rootMargin: '-20% 0px -70% 0px' });
    map.forEach((_a, id) => { const el = document.getElementById(id); if (el) tio.observe(el); });
  }

  $$('[data-year]').forEach(el => el.textContent = new Date().getFullYear());
})();
