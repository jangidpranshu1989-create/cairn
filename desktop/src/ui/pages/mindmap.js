(async () => {
  const id = new URLSearchParams(location.search).get('id');
  const data = await api('mindmap:get', id);
  const vp = $('#vp'), svg = $('#map');
  if (!data) { vp.innerHTML = ''; $('#title').textContent = 'This mind map is no longer available'; return; }
  document.title = 'Mind map — ' + data.title;
  $('#title').textContent = data.tree?.title || data.title;
  $('#src').href = data.url;
  const PAL = ['#E9A23B', '#8B97FF', '#5BC48A', '#F0776B', '#4FC3D9', '#D98BE0', '#E9C46A'];
  let uid = 0;
  const norm = (n, depth = 0, color) => ({ id: uid++, title: String(n.title || n.name || n.label || '').slice(0, 80), depth, color, collapsed: false,
    children: (n.children || []).map((c, i) => norm(c, depth + 1, depth === 0 ? PAL[i % PAL.length] : color)) });
  const root = norm(data.tree || { title: data.title });
  const measure = (() => { const c = document.createElement('canvas').getContext('2d'); return (t, d) => { c.font = d === 0 ? '800 17px "Cabinet Grotesk"' : '600 13px Satoshi'; return c.measureText(t).width; }; })();
  const wrap = (t, max) => { const w = t.split(/\s+/), lines = []; let cur = ''; for (const x of w) { if ((cur + ' ' + x).trim().length > max && cur) { lines.push(cur); cur = x; } else cur = (cur + ' ' + x).trim(); } if (cur) lines.push(cur); return lines.slice(0, 3); };
  const H_GAP = 64, V_GAP = 14;

  function layout() {
    // Right-to-left balanced: first half of branches on right, rest on left
    const sizes = (n) => { n.lines = wrap(n.title, n.depth === 0 ? 22 : 26); n.w = Math.max(...n.lines.map(l => measure(l, n.depth))) + 28; n.h = n.lines.length * (n.depth === 0 ? 22 : 18) + 18;
      const kids = n.collapsed ? [] : n.children; kids.forEach(sizes); n.sub = kids.length ? Math.max(n.h, kids.reduce((s, k) => s + k.sub, 0) + V_GAP * (kids.length - 1)) : n.h; };
    sizes(root);
    const place = (n, x, yTop, dir) => { n.dir = dir; n.y = yTop + n.sub / 2; n.x = x;
      let y = yTop; const kids = n.collapsed ? [] : n.children;
      const total = kids.reduce((s, k) => s + k.sub, 0) + V_GAP * Math.max(0, kids.length - 1); y = n.y - total / 2;
      for (const k of kids) { place(k, dir > 0 ? x + n.w / 2 + H_GAP + k.w / 2 : x - n.w / 2 - H_GAP - k.w / 2, y, dir); y += k.sub + V_GAP; } };
    root.x = 0; root.y = 0; root.dir = 0;
    const kids = root.collapsed ? [] : root.children;
    const right = kids.slice(0, Math.ceil(kids.length / 2)), left = kids.slice(Math.ceil(kids.length / 2));
    for (const [side, dir] of [[right, 1], [left, -1]]) {
      const total = side.reduce((s, k) => s + k.sub, 0) + V_GAP * 2 * Math.max(0, side.length - 1); let y = -total / 2;
      for (const k of side) { place(k, dir * (root.w / 2 + H_GAP + k.w / 2), y, dir); y += k.sub + V_GAP * 2; }
    }
  }
  function draw() {
    layout();
    let links = '', nodes = '';
    const walk = (n) => {
      const kids = n.collapsed ? [] : n.children;
      for (const k of kids) {
        const x1 = n.x + (k.dir > 0 ? n.w / 2 : -n.w / 2), x2 = k.x - (k.dir > 0 ? k.w / 2 : -k.w / 2), mx = (x1 + x2) / 2;
        links += `<path class="link" stroke="${k.color}" d="M${x1},${n.y} C${mx},${n.y} ${mx},${k.y} ${x2},${k.y}"/>`;
        walk(k);
      }
      const isRoot = n.depth === 0;
      const fill = isRoot ? 'var(--accent)' : n.depth === 1 ? `color-mix(in srgb, ${n.color} 22%, var(--bg))` : 'var(--bg)';
      const stroke = isRoot ? 'transparent' : n.depth === 1 ? n.color : `color-mix(in srgb, ${n.color} 45%, transparent)`;
      const lh = isRoot ? 22 : 18;
      nodes += `<g class="node${isRoot ? ' root' : ''}" data-id="${n.id}" transform="translate(${n.x - n.w / 2},${n.y - n.h / 2})"><rect width="${n.w}" height="${n.h}" fill="${fill}" stroke="${stroke}"/>` +
        n.lines.map((l, i) => `<text x="${n.w / 2}" y="${9 + lh * (i + 0.78)}" text-anchor="middle">${esc(l)}</text>`).join('') +
        (n.collapsed && n.children.length ? `<circle cx="${n.dir < 0 ? 0 : n.w}" cy="${n.h / 2}" r="9" fill="${n.color}"/><text x="${n.dir < 0 ? 0 : n.w}" y="${n.h / 2 + 4}" text-anchor="middle" style="font:700 11px Satoshi;fill:#111">${n.children.length}</text>` : '') + '</g>';
    };
    walk(root);
    vp.innerHTML = links + nodes;
    $$('.node', vp).forEach(g => g.onclick = (e) => { e.stopPropagation(); const n = find(root, Number(g.dataset.id)); if (n?.children.length) { n.collapsed = !n.collapsed; draw(); } });
    apply();
  }
  const find = (n, id) => n.id === id ? n : n.children.reduce((r, c) => r || find(c, id), null);
  // pan & zoom
  let T = { x: 0, y: 0, k: 1 };
  const apply = () => vp.setAttribute('transform', `translate(${T.x},${T.y}) scale(${T.k})`);
  function fit() { const b = vp.getBBox(), W = svg.clientWidth, H = svg.clientHeight; const k = Math.min(1.4, 0.92 * Math.min(W / b.width, H / b.height)); T = { k, x: W / 2 - k * (b.x + b.width / 2), y: H / 2 - k * (b.y + b.height / 2) }; apply(); }
  let drag = null;
  svg.addEventListener('pointerdown', (e) => { drag = { x: e.clientX - T.x, y: e.clientY - T.y }; svg.setPointerCapture(e.pointerId); });
  svg.addEventListener('pointermove', (e) => { if (drag) { T.x = e.clientX - drag.x; T.y = e.clientY - drag.y; apply(); } });
  svg.addEventListener('pointerup', () => drag = null);
  svg.addEventListener('wheel', (e) => { e.preventDefault(); const r = svg.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top; const f = Math.exp(-e.deltaY * 0.0015); const k = Math.min(4, Math.max(0.15, T.k * f)); T.x = mx - (mx - T.x) * (k / T.k); T.y = my - (my - T.y) * (k / T.k); T.k = k; apply(); }, { passive: false });
  $('#fit').onclick = fit;
  $('#expand').onclick = () => { const all = (n) => { n.collapsed = false; n.children.forEach(all); }; all(root); draw(); fit(); };
  $('#png').onclick = async () => {
    const b = vp.getBBox(), pad = 40, scale = 2;
    const cs = getComputedStyle(document.documentElement);
    const resolve = (s) => s.replace(/var\((--[\w-]+)\)/g, (_, v) => cs.getPropertyValue(v).trim());
    const clone = vp.cloneNode(true); clone.removeAttribute('transform');
    clone.querySelectorAll('[fill],[stroke]').forEach(el => { for (const a of ['fill', 'stroke']) if (el.getAttribute(a)) el.setAttribute(a, resolve(el.getAttribute(a))); });
    const css = `text{font:600 13px Satoshi,sans-serif;fill:${cs.getPropertyValue('--text')}}.root text{font:800 17px 'Cabinet Grotesk',sans-serif;fill:${cs.getPropertyValue('--accent-ink')}}.link{fill:none;stroke-width:2;opacity:.55}`;
    const xml = `<svg xmlns="http://www.w3.org/2000/svg" width="${(b.width + pad * 2) * scale}" height="${(b.height + pad * 2) * scale}" viewBox="${b.x - pad} ${b.y - pad} ${b.width + pad * 2} ${b.height + pad * 2}"><style>${css}</style><rect x="${b.x - pad}" y="${b.y - pad}" width="${b.width + pad * 2}" height="${b.height + pad * 2}" fill="${cs.getPropertyValue('--bg-2')}"/>${clone.innerHTML}</svg>`;
    const img = new Image(); img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(resolve(xml));
    await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; c.getContext('2d').drawImage(img, 0, 0);
    await api('mindmap:save', c.toDataURL('image/png'), data.title || 'mindmap');
  };
  await document.fonts.ready;
  draw(); fit();
  addEventListener('resize', fit);
})();
