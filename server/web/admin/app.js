/* Cairn Analytics dashboard. No dependencies; served under strict CSP (script-src 'self'). */
(() => {
  'use strict';
  const $ = (s) => document.querySelector(s);
  const DEFAULT_API = 'https://cairn-cloud.onrender.com';
  const sameOrigin = /onrender\.com$|^localhost$|^127\.0\.0\.1$/.test(location.hostname) && location.protocol.startsWith('http');
  const store = { get: (k) => { try { return localStorage.getItem(k); } catch { return null; } }, set: (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} } };
  const host = window.CairnHost; // present inside the Cairn Analytics Android app

  let key = store.get('cairn_admin_key') || '';
  let api = store.get('cairn_admin_api') || (sameOrigin ? location.origin : DEFAULT_API);
  let days = Number(store.get('cairn_admin_days')) || 30;
  let timer = null; let lastOk = 0; let busy = false; let data = null;

  const m = /[#&]key=([^&]+)/.exec(location.hash);
  if (m) { key = decodeURIComponent(m[1]); store.set('cairn_admin_key', key); history.replaceState(null, '', location.pathname + location.search); }

  const fmt = (n) => (n == null ? '–' : Number(n).toLocaleString('en-IN'));
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const dayLabel = (d) => { const [y, mo, da] = d.split('-').map(Number); return new Date(Date.UTC(y, mo - 1, da)).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' }); };
  const COUNTRY = { IN: 'India', US: 'United States', GB: 'United Kingdom', AE: 'UAE', SG: 'Singapore', NP: 'Nepal', BD: 'Bangladesh', PK: 'Pakistan', LK: 'Sri Lanka', CA: 'Canada', AU: 'Australia', DE: 'Germany', SA: 'Saudi Arabia' };
  const LABEL = { android: 'Android', desktop: 'Desktop', web: 'Website', install: 'Installs', app_open: 'Sessions', heartbeat: 'Active minutes', page_load: 'Pages loaded', search: 'Searches', agent_run: 'Agent runs', chat_run: 'Chats', agent_done: 'Agent finished', compare: 'Price compares', voice: 'Voice', circle: 'Circle to search', pdf: 'PDF', download: 'File downloads', crash: 'Crashes', signin: 'Sign-ins', shield_off: 'Shield turned off', tab_new: 'New tabs', unknown: 'Unknown' };
  const label = (k, kind) => kind === 'country' ? (COUNTRY[k] || k || 'Unknown') : (LABEL[k] || k || 'Unknown');

  // ---------- auth ----------
  function showLogin(msg) {
    clearInterval(timer); $('#app').hidden = true; $('#login').hidden = false;
    $('#apiInput').value = api; $('#loginErr').textContent = msg || ''; $('#keyInput').focus();
  }
  $('#loginForm').addEventListener('submit', (e) => {
    e.preventDefault();
    key = $('#keyInput').value.trim();
    const a = $('#apiInput').value.trim().replace(/\/+$/, ''); if (a) api = a;
    store.set('cairn_admin_key', key); store.set('cairn_admin_api', api);
    start();
  });
  $('#logoutBtn').addEventListener('click', () => { key = ''; store.set('cairn_admin_key', null); host?.saveKey?.('', api); showLogin(); });
  $('#refreshBtn').addEventListener('click', () => load(true));
  document.querySelectorAll('.seg button').forEach((b) => {
    b.classList.toggle('on', Number(b.dataset.days) === days);
    b.addEventListener('click', () => { days = Number(b.dataset.days); store.set('cairn_admin_days', String(days)); document.querySelectorAll('.seg button').forEach((x) => x.classList.toggle('on', x === b)); load(true); });
  });

  function start() {
    if (!key) return showLogin();
    $('#login').hidden = true; $('#app').hidden = false;
    load(true);
    clearInterval(timer); timer = setInterval(() => { if (!document.hidden) load(false); }, 15000);
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && key && Date.now() - lastOk > 15000) load(false); });

  async function load(manual) {
    if (busy) return; busy = true;
    if (manual) $('#refreshBtn').classList.add('spin');
    try {
      const r = await fetch(`${api}/api/admin/analytics?days=${days}`, { headers: { Authorization: `Bearer ${key}` }, cache: 'no-store', signal: AbortSignal.timeout(manual ? 60000 : 25000) });
      if (r.status === 401 || r.status === 404) { store.set('cairn_admin_key', null); host?.saveKey?.('', api); return showLogin('That key was not accepted.'); }
      if (!r.ok) throw new Error(`Server returned ${r.status}`);
      data = await r.json(); lastOk = Date.now();
      host?.saveKey?.(key, api);
      try { host?.push?.(JSON.stringify({ realtime: data.realtime && { active: data.realtime.active, apps: data.realtime.apps, web: data.realtime.web }, kpis: data.kpis })); } catch {}
      if (!data.github || data.github.error) data.github = await githubDirect() || data.github;
      render(data); setStatus(true);
    } catch (e) {
      setStatus(false, e.name === 'TimeoutError' ? 'Server is waking up, retrying' : (e.message || 'Offline'));
      if (!$('#login').hidden && !data) $('#loginErr').textContent = 'Could not reach the server.';
    } finally { busy = false; $('#refreshBtn').classList.remove('spin'); }
  }

  // The server shares an IP with other apps on Render, so GitHub's anonymous rate limit can run out there; ask GitHub directly then.
  let ghCache = null;
  async function githubDirect() {
    if (ghCache && Date.now() - ghCache.at < 10 * 60_000) return ghCache.data;
    try {
      const r = await fetch('https://api.github.com/repos/jangidpranshu1989-create/cairn/releases?per_page=50', { headers: { Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(8000) });
      if (!r.ok) return null;
      const kind = (n) => /\.apk$/i.test(n) ? 'android' : /\.(exe|msi)$/i.test(n) ? 'windows' : /\.(dmg|zip)$/i.test(n) && /mac/i.test(n) ? 'mac' : /\.(AppImage|deb|rpm)$/i.test(n) ? 'linux' : null;
      const totals = { android: 0, windows: 0, mac: 0, linux: 0 }; const releases = [];
      for (const x of await r.json()) {
        if (x.draft || /^analytics/i.test(x.tag_name)) continue;
        const row = { tag: x.tag_name, android: 0, windows: 0, mac: 0, linux: 0 };
        for (const a of x.assets || []) { const k = kind(a.name); if (k) { row[k] += a.download_count; totals[k] += a.download_count; } }
        releases.push(row);
      }
      ghCache = { at: Date.now(), data: { totals, total: Object.values(totals).reduce((a, b) => a + b, 0), releases: releases.slice(0, 12) } };
      return ghCache.data;
    } catch { return null; }
  }
  function setStatus(ok, msg) {
    $('#liveDot').className = 'dot ' + (ok ? 'on' : 'off');
    $('#updated').textContent = ok ? 'Live, updated ' + new Date(lastOk).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : msg;
  }

  // ---------- render ----------
  function bars(el, rows, kind, max = 8) {
    const list = (Array.isArray(rows) ? rows : Object.entries(rows || {}).map(([k, n]) => ({ k, n }))).filter((r) => r.n > 0).sort((a, b) => b.n - a.n).slice(0, max);
    const top = Math.max(1, ...list.map((r) => r.n));
    $(el).innerHTML = list.length ? list.map((r) => `<li><div class="fill" style="width:${(r.n / top) * 100}%"></div><span title="${esc(label(r.k, kind))}">${esc(label(r.k, kind))}</span><b>${fmt(r.n)}</b></li>`).join('') : '<li class="empty">No data yet</li>';
  }
  function delta(cur, prev) {
    if (!prev) return cur ? '<div class="d up">New</div>' : '<div class="d">No change</div>';
    const p = Math.round(((cur - prev) / prev) * 100);
    return `<div class="d ${p > 0 ? 'up' : p < 0 ? 'down' : ''}">${p > 0 ? '+' : ''}${p}% vs yesterday</div>`;
  }
  function render(d) {
    const rt = d.realtime || {}; const k = d.kpis || {}; const s = d.series || [];
    $('#rtActive').textContent = fmt(rt.active); $('#rtApps').textContent = fmt(rt.apps); $('#rtWeb').textContent = fmt(rt.web);
    bars('#rtCountry', rt.byCountry, 'country', 5); bars('#rtPlatform', rt.byPlatform, '', 5); bars('#rtVersion', rt.byVersion, '', 5);
    barChart('#rtBars', (rt.minutes || []).map((x) => ({ label: new Date(x.m).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }), v: x.n })), 'var(--accent)');

    const goal = k.goal || 10000; const total = k.totalUsers || 0;
    $('#goalNow').textContent = fmt(total);
    const pct = Math.min(100, (total / goal) * 100);
    $('#goalPct').textContent = (pct < 1 && total ? pct.toFixed(2) : pct.toFixed(1)) + '%';
    $('#goalBar').style.width = Math.max(pct, total ? 0.5 : 0) + '%';
    $('#ghTotal').textContent = fmt(d.github?.total); $('#kAccounts').textContent = fmt(k.accounts); $('#kAiDevices').textContent = fmt(k.aiDevices); $('#kAiToday').textContent = fmt(k.aiUsersToday);

    const t = s[s.length - 1] || {}; const y = s[s.length - 2] || {};
    const kp = [
      ['Users today', k.dau, delta(t.users || 0, y.users || 0)],
      ['New installs today', k.newToday, delta(t.newUsers || 0, y.newUsers || 0)],
      ['Weekly users', k.wau, '<div class="d">Last 7 days</div>'],
      ['Monthly users', k.mau, '<div class="d">Last 30 days</div>'],
      ['Sessions today', k.sessionsToday, delta(t.sessions || 0, y.sessions || 0)],
      ['Avg minutes per user', k.avgMinutes, '<div class="d">Today</div>'],
      ['Website visitors today', k.webVisitorsToday, delta(t.web || 0, y.web || 0)],
      ['Website visitors 30d', k.webVisitors30d, '<div class="d">Unique</div>'],
    ];
    $('#kpis').innerHTML = kp.map(([l, v, dl]) => `<div class="kpi"><div class="l">${l}</div><div class="v">${fmt(v)}</div>${dl}</div>`).join('');

    const x = s.map((r) => dayLabel(r.day));
    lineChart('#usersChart', '#usersLegend', x, [
      { name: 'Active users', color: 'var(--accent)', v: s.map((r) => r.users), area: true },
      { name: 'Android', color: 'var(--accent3)', v: s.map((r) => r.android) },
      { name: 'Desktop', color: 'var(--accent2)', v: s.map((r) => r.desktop) },
      { name: 'New installs', color: 'var(--accent4)', v: s.map((r) => r.newUsers) },
    ]);
    lineChart('#webChart', '#webLegend', x, [
      { name: 'Visitors', color: 'var(--accent2)', v: s.map((r) => r.web), area: true },
      { name: 'Page views', color: 'var(--accent)', v: s.map((r) => r.webViews) },
      { name: 'Download clicks', color: 'var(--accent3)', v: s.map((r) => r.downloads) },
    ]);
    lineChart('#sessChart', '#sessLegend', x, [
      { name: 'Sessions', color: 'var(--accent)', v: s.map((r) => r.sessions), area: true },
      { name: 'Active minutes', color: 'var(--accent2)', v: s.map((r) => r.minutes) },
    ]);

    const b = d.breakdown || {}; const w = d.web || {};
    bars('#bPlatform', b.platform); bars('#bCountry', b.country, 'country', 6);
    bars('#bVersion', b.version); bars('#bOs', b.os); bars('#bLocale', b.locale);
    bars('#events', (d.events || []).filter((e) => e.k !== 'heartbeat'), '', 12);
    bars('#wPages', w.pages, '', 10); bars('#wRefs', w.referrers, '', 10); bars('#wUtm', w.utm, '', 10);
    bars('#wDevices', w.devices); bars('#wDownloads', w.downloadClicks);

    const heat = (n, size) => { if (n == null) return '<td class="muted">–</td>'; const p = size ? n / size : 0; return `<td class="heat" style="background:rgba(212,180,131,${(0.08 + p * 0.5).toFixed(2)})">${Math.round(p * 100)}% <span class="muted">(${n})</span></td>`; };
    const coh = (d.cohorts || []).slice().reverse();
    $('#cohorts tbody').innerHTML = coh.some((c) => c.size) ? coh.map((c) => `<tr><td>${dayLabel(c.day)}</td><td>${fmt(c.size)}</td>${c.size ? heat(c.d1, c.size) + heat(c.d7, c.size) : '<td class="muted">–</td><td class="muted">–</td>'}</tr>`).join('') : '<tr><td colspan="4" class="muted">Retention appears after the first installs report in.</td></tr>';
    const gh = d.github || {};
    $('#ghTable tbody').innerHTML = (gh.releases || []).length ? gh.releases.slice(0, 8).map((r) => `<tr><td>${esc(r.tag)}</td><td>${fmt(r.android)}</td><td>${fmt(r.windows)}</td><td>${fmt(r.mac)}</td><td>${fmt(r.linux)}</td></tr>`).join('') + `<tr><td><b>Total</b></td><td><b>${fmt(gh.totals?.android)}</b></td><td><b>${fmt(gh.totals?.windows)}</b></td><td><b>${fmt(gh.totals?.mac)}</b></td><td><b>${fmt(gh.totals?.linux)}</b></td></tr>` : `<tr><td colspan="5" class="muted">${gh.error ? 'GitHub not reachable' : 'No releases yet'}</td></tr>`;
  }

  // ---------- charts (plain SVG) ----------
  const tip = $('#tip');
  const showTip = (ev, html) => { tip.innerHTML = html; tip.hidden = false; const r = tip.getBoundingClientRect(); let x = ev.clientX + 14, y = ev.clientY - r.height - 10; if (x + r.width > innerWidth - 8) x = ev.clientX - r.width - 14; if (y < 8) y = ev.clientY + 16; tip.style.left = x + 'px'; tip.style.top = y + 'px'; };
  const hideTip = () => { tip.hidden = true; };
  const niceMax = (v) => { if (v <= 5) return 5; const p = Math.pow(10, Math.floor(Math.log10(v))); const n = v / p; return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p; };

  function lineChart(sel, legendSel, labels, series) {
    const el = $(sel); const W = el.clientWidth || 600; const H = el.clientHeight || 240;
    const P = { l: 36, r: 8, t: 8, b: 24 }; const iw = W - P.l - P.r; const ih = H - P.t - P.b;
    const max = niceMax(Math.max(1, ...series.flatMap((s) => s.v)));
    const n = labels.length; const X = (i) => P.l + (n <= 1 ? iw / 2 : (i / (n - 1)) * iw); const Y = (v) => P.t + ih - (v / max) * ih;
    let g = '';
    for (let i = 0; i <= 5; i++) { const v = (max / 5) * i; g += `<line class="grid-line" x1="${P.l}" x2="${W - P.r}" y1="${Y(v)}" y2="${Y(v)}"/><text x="${P.l - 6}" y="${Y(v) + 4}" text-anchor="end">${v >= 1000 ? (v / 1000).toFixed(v % 1000 ? 1 : 0) + 'k' : Math.round(v)}</text>`; }
    const step = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(iw / 70))));
    for (let i = 0; i < n; i += step) g += `<text x="${X(i)}" y="${H - 6}" text-anchor="middle">${esc(labels[i])}</text>`;
    let paths = '';
    series.forEach((s, si) => {
      const pts = s.v.map((v, i) => `${X(i).toFixed(1)},${Y(v || 0).toFixed(1)}`);
      if (s.area) paths += `<path d="M${X(0)},${Y(0)} L${pts.join(' L')} L${X(n - 1)},${Y(0)} Z" fill="${s.color}" opacity=".12"/>`;
      paths += `<polyline points="${pts.join(' ')}" fill="none" stroke="${s.color}" stroke-width="${si === 0 ? 2.25 : 1.75}" stroke-linejoin="round" stroke-linecap="round"/>`;
    });
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(series.map((s) => s.name).join(', '))} over time"><g class="axis">${g}</g>${paths}<line class="cursor" x1="0" x2="0" y1="${P.t}" y2="${P.t + ih}" stroke="var(--line2)" visibility="hidden"/><g class="dots"></g><rect x="${P.l}" y="${P.t}" width="${iw}" height="${ih}" fill="transparent"/></svg>`;
    $(legendSel).innerHTML = series.map((s) => `<span><i style="background:${s.color}"></i>${esc(s.name)}</span>`).join('');
    const svg = el.querySelector('svg'); const cur = svg.querySelector('.cursor'); const dots = svg.querySelector('.dots');
    const move = (ev) => {
      const r = svg.getBoundingClientRect(); const px = ((ev.clientX - r.left) / r.width) * W;
      const i = Math.max(0, Math.min(n - 1, Math.round(((px - P.l) / iw) * (n - 1))));
      cur.setAttribute('x1', X(i)); cur.setAttribute('x2', X(i)); cur.setAttribute('visibility', 'visible');
      dots.innerHTML = series.map((s) => `<circle cx="${X(i)}" cy="${Y(s.v[i] || 0)}" r="3.5" fill="${s.color}" stroke="var(--s1)" stroke-width="2"/>`).join('');
      showTip(ev, `<b>${esc(labels[i])}</b><br>` + series.map((s) => `<span style="color:${s.color}">●</span> ${esc(s.name)}: <b>${fmt(s.v[i])}</b>`).join('<br>'));
    };
    svg.addEventListener('pointermove', move); svg.addEventListener('pointerdown', move);
    svg.addEventListener('pointerleave', () => { cur.setAttribute('visibility', 'hidden'); dots.innerHTML = ''; hideTip(); });
  }

  function barChart(sel, rows, color) {
    const el = $(sel); const W = el.clientWidth || 400; const H = el.clientHeight || 110; const n = rows.length || 1;
    const max = Math.max(1, ...rows.map((r) => r.v)); const gap = 3; const bw = Math.max(2, (W - gap * (n - 1)) / n);
    el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Active users per minute">${rows.map((r, i) => { const h = r.v ? Math.max(3, (r.v / max) * (H - 4)) : 2; return `<rect data-i="${i}" x="${(i * (bw + gap)).toFixed(1)}" y="${H - h}" width="${bw.toFixed(1)}" height="${h}" rx="2" fill="${r.v ? color : 'var(--s3)'}"/>`; }).join('')}</svg>`;
    el.querySelector('svg').addEventListener('pointermove', (ev) => { const i = ev.target.dataset?.i; if (i == null) return hideTip(); const r = rows[i]; showTip(ev, `<b>${esc(r.label)}</b><br>${fmt(r.v)} active`); });
    el.querySelector('svg').addEventListener('pointerleave', hideTip);
  }

  let rz; addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(() => data && render(data), 150); });
  start();
})();
