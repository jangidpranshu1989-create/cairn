/*! Cairn Auth SDK v1 — "Continue with Cairn" for any website.
 * OAuth 2.0 Authorization Code + PKCE (S256) and OpenID Connect. No secrets in the browser.
 *
 *   <script src="https://YOUR-CAIRN-SERVER/sdk/cairn-auth.js"></script>
 *   <div id="cairn-btn"></div>
 *   <script>
 *     CairnAuth.init({ clientId: 'cl_...', redirectUri: location.origin + '/', onSignIn: (user, tokens) => console.log(user) });
 *     CairnAuth.renderButton('#cairn-btn');
 *   </script>
 */
(function (global) {
  'use strict';
  var script = document.currentScript;
  var DEFAULT_ISSUER = script ? new URL('../', script.src).href.replace(/\/$/, '') : '';
  var KEY = 'cairn.sdk.pending';
  var cfg = { issuer: DEFAULT_ISSUER, scope: 'openid profile email', clientId: '', redirectUri: '', onSignIn: null, onError: null };
  var discovery = null;

  function b64url(buf) { var s = ''; var b = new Uint8Array(buf); for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]); return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); }
  function rand(n) { var a = new Uint8Array(n || 32); crypto.getRandomValues(a); return b64url(a); }
  function sha256(s) { return crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)).then(b64url); }
  function decodeJwt(t) { try { var p = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'); return JSON.parse(decodeURIComponent(escape(atob(p)))); } catch (e) { return null; } }
  function discover() {
    if (discovery) return Promise.resolve(discovery);
    return fetch(cfg.issuer + '/.well-known/openid-configuration').then(function (r) { return r.json(); }).then(function (d) { discovery = d; return d; });
  }
  function err(e) { if (cfg.onError) cfg.onError(e); else console.error('[CairnAuth]', e); }

  function signIn(opts) {
    opts = opts || {};
    if (!cfg.clientId || !cfg.redirectUri) return err(new Error('CairnAuth.init({ clientId, redirectUri }) first'));
    var verifier = rand(48), state = rand(16), nonce = rand(16);
    return Promise.all([discover(), sha256(verifier)]).then(function (r) {
      sessionStorage.setItem(KEY, JSON.stringify({ verifier: verifier, state: state, nonce: nonce, redirectUri: cfg.redirectUri, back: location.href }));
      var u = new URL(r[0].authorization_endpoint);
      var q = { response_type: 'code', client_id: cfg.clientId, redirect_uri: cfg.redirectUri, scope: opts.scope || cfg.scope, state: state, nonce: nonce, code_challenge: r[1], code_challenge_method: 'S256' };
      if (opts.prompt) q.prompt = opts.prompt;
      Object.keys(q).forEach(function (k) { u.searchParams.set(k, q[k]); });
      location.assign(u.toString());
    }).catch(err);
  }

  /** Call on the redirect page (done automatically by init). Resolves to {user, tokens} or null. */
  function handleRedirect() {
    var p = new URLSearchParams(location.search);
    var pending; try { pending = JSON.parse(sessionStorage.getItem(KEY) || 'null'); } catch (e) { pending = null; }
    if (!pending || (!p.get('code') && !p.get('error')) || p.get('state') !== pending.state) return Promise.resolve(null);
    sessionStorage.removeItem(KEY);
    var clean = new URL(location.href); ['code', 'state', 'iss', 'error', 'error_description'].forEach(function (k) { clean.searchParams.delete(k); });
    history.replaceState(null, '', clean.toString());
    if (p.get('error')) { var e = new Error(p.get('error_description') || p.get('error')); e.code = p.get('error'); err(e); return Promise.resolve(null); }
    if (p.get('iss') && p.get('iss') !== cfg.issuer) { err(new Error('Issuer mismatch')); return Promise.resolve(null); }
    return discover().then(function (d) {
      var body = new URLSearchParams({ grant_type: 'authorization_code', code: p.get('code'), redirect_uri: pending.redirectUri, client_id: cfg.clientId, code_verifier: pending.verifier });
      return fetch(d.token_endpoint, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
        .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error_description || j.error || 'Token exchange failed'); return j; }); })
        .then(function (tokens) {
          var claims = decodeJwt(tokens.id_token) || {};
          if (claims.nonce !== pending.nonce) throw new Error('Nonce mismatch');
          if (claims.aud !== cfg.clientId && !(Array.isArray(claims.aud) && claims.aud.indexOf(cfg.clientId) >= 0)) throw new Error('Audience mismatch');
          return fetch(d.userinfo_endpoint, { headers: { Authorization: 'Bearer ' + tokens.access_token } }).then(function (r) { return r.json(); }).then(function (user) {
            if (user.sub !== claims.sub) throw new Error('Subject mismatch');
            var result = { user: user, tokens: tokens, idToken: claims };
            if (cfg.onSignIn) cfg.onSignIn(user, tokens, claims);
            return result;
          });
        });
    }).catch(function (e) { err(e); return null; });
  }

  var STYLE = '.cairn-btn{all:unset;box-sizing:border-box;display:inline-flex;align-items:center;justify-content:center;gap:10px;height:44px;padding:0 18px;border-radius:12px;font:600 15px/1 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;cursor:pointer;transition:filter .15s,transform .1s;min-width:220px}' +
    '.cairn-btn:active{transform:scale(.98)}.cairn-btn:focus-visible{outline:2px solid #E9A23B;outline-offset:2px}' +
    '.cairn-btn--dark{background:#121419;color:#ECE9E2;border:1px solid rgba(255,255,255,.14)}.cairn-btn--dark:hover{filter:brightness(1.2)}' +
    '.cairn-btn--light{background:#fff;color:#17150F;border:1px solid rgba(20,18,14,.18)}.cairn-btn--light:hover{background:#F6F3EE}' +
    '.cairn-btn--amber{background:#E9A23B;color:#1B1205;border:0}.cairn-btn--amber:hover{filter:brightness(1.06)}.cairn-btn svg{width:20px;height:20px;flex:none}';
  var ICON = '<svg viewBox="0 0 32 32" aria-hidden="true"><ellipse cx="16" cy="25" rx="11" ry="4" fill="#E9A23B"/><ellipse cx="16" cy="16.5" rx="8" ry="3.6" fill="#E9A23B" opacity=".8"/><ellipse cx="16" cy="9" rx="5" ry="3" fill="#E9A23B" opacity=".6"/></svg>';
  function renderButton(target, o) {
    o = o || {};
    var el = typeof target === 'string' ? document.querySelector(target) : target;
    if (!el) return null;
    if (!document.getElementById('cairn-btn-style')) { var s = document.createElement('style'); s.id = 'cairn-btn-style'; s.textContent = STYLE; document.head.appendChild(s); }
    var b = document.createElement('button'); b.type = 'button';
    var theme = o.theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    b.className = 'cairn-btn cairn-btn--' + theme;
    b.innerHTML = (theme === 'amber' ? ICON.replace(/#E9A23B/g, '#1B1205') : ICON) + '<span></span>';
    b.querySelector('span').textContent = o.text || 'Continue with Cairn';
    b.setAttribute('data-cairn-auth', '1');
    b.addEventListener('click', function () { signIn(o); });
    el.appendChild(b);
    return b;
  }
  function init(o) {
    Object.keys(o || {}).forEach(function (k) { cfg[k] = o[k]; });
    cfg.issuer = String(cfg.issuer || DEFAULT_ISSUER).replace(/\/$/, '');
    return handleRedirect();
  }
  // Declarative: <div data-cairn-client-id="cl_..." data-redirect-uri="..."></div>
  function auto() {
    var nodes = document.querySelectorAll('[data-cairn-client-id]');
    if (!nodes.length) return;
    var n0 = nodes[0];
    init({ clientId: n0.getAttribute('data-cairn-client-id'), redirectUri: n0.getAttribute('data-redirect-uri') || location.origin + location.pathname,
      onSignIn: function (u, t, c) { document.dispatchEvent(new CustomEvent('cairn:signin', { detail: { user: u, tokens: t, idToken: c } })); } });
    for (var i = 0; i < nodes.length; i++) renderButton(nodes[i], { theme: nodes[i].getAttribute('data-theme') || undefined, text: nodes[i].getAttribute('data-text') || undefined });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', auto); else auto();
  global.CairnAuth = { init: init, signIn: signIn, renderButton: renderButton, handleRedirect: handleRedirect, get issuer() { return cfg.issuer; } };
})(window);
