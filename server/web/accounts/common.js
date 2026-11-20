// Shared helpers for the Cairn Account portal. Paths are resolved relative to this script, so the portal
// works at any mount point (e.g. https://host/ or https://host/port/8787/).
(() => {
  const BASE = new URL('../', document.currentScript.src).href.replace(/\/$/, '');
  const store = {
    get: () => { try { return JSON.parse(localStorage.getItem('cairn.auth') || 'null'); } catch { return null; } },
    set: (v) => localStorage.setItem('cairn.auth', JSON.stringify(v)),
    clear: () => localStorage.removeItem('cairn.auth'),
  };
  async function api(path, opts = {}) {
    const auth = store.get();
    const headers = { 'Content-Type': 'application/json', ...(auth && !opts.noAuth ? { Authorization: 'Bearer ' + auth.accessToken } : {}) };
    const r = await fetch(BASE + '/api' + path, { method: opts.method || 'GET', headers, body: opts.body ? JSON.stringify(opts.body) : undefined });
    if (r.status === 401 && auth && !opts.noAuth && !opts.retried) {
      const rr = await fetch(BASE + '/api/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: auth.refreshToken }) });
      if (rr.ok) { store.set({ ...auth, ...(await rr.json()) }); return api(path, { ...opts, retried: true }); }
      store.clear();
    }
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw Object.assign(new Error(data.error || 'Something went wrong'), { data, status: r.status });
    return data;
  }
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const say = (el, text, ok) => { if (!el) return; el.textContent = text || ''; el.className = 'msg ' + (ok ? 'ok' : 'err'); };
  // theme toggle (system by default)
  const t = localStorage.getItem('cairn.theme'); if (t) document.documentElement.dataset.theme = t;
  document.addEventListener('click', (e) => {
    if (!e.target.closest('.theme')) return;
    const dark = matchMedia('(prefers-color-scheme: dark)').matches;
    const cur = document.documentElement.dataset.theme || (dark ? 'dark' : 'light');
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next; localStorage.setItem('cairn.theme', next);
  });
  window.Cairn = { BASE, store, api, esc, say };
})();
