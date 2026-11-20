import { config } from './config.js';

// Strict CORS + hardening headers. The API is Bearer-token only, so we never send
// Access-Control-Allow-Credentials, and unknown origins get no CORS headers at all.
const PAGES = 'https://jangidpranshu1989-create.github.io';
const DEFAULT_PUBLIC = 'https://cairn-cloud.onrender.com';
const originOf = (u) => { try { return new URL(u).origin; } catch { return null; } };

export const allowedOrigins = new Set([
  originOf(config.publicUrl), DEFAULT_PUBLIC, PAGES,
  // CORS_ORIGINS may add explicit origins; a wildcard is ignored on purpose.
  ...config.corsOrigins.filter(o => o !== '*').map(originOf),
].filter(Boolean));

const LOCALHOST = /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/;
export const isAllowedOrigin = (o) =>
  allowedOrigins.has(o) || /^cairn:\/\/[a-z0-9-]+$/i.test(o) || (!config.isProd && LOCALHOST.test(o));

// Public, cookie-less endpoints that third-party apps call cross-origin (OIDC discovery, JWKS,
// userinfo, token, revoke) plus the embeddable SDK and public safety lists.
const OPEN = [/^\/\.well-known\/openid-configuration$/, /^\/oauth\/(jwks|userinfo|token|revoke)$/, /^\/sdk(\/|$)/, /^\/api\/safety(\/|$)/];
export const isOpenPath = (p) => OPEN.some(r => r.test(p));

const ALLOW_HEADERS = 'Authorization, Content-Type, X-Device-Id, X-Requested-With';
const ALLOW_METHODS = 'GET, POST, PUT, PATCH, DELETE, OPTIONS';

export function strictCors(req, res, next) {
  res.removeHeader('Access-Control-Allow-Credentials');
  const origin = req.headers.origin;
  res.vary('Origin');
  if (isOpenPath(req.path)) {
    res.set('Access-Control-Allow-Origin', '*');
    res.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
    res.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  } else if (origin && isAllowedOrigin(origin)) {
    res.set('Access-Control-Allow-Origin', origin);
    res.set('Access-Control-Allow-Headers', ALLOW_HEADERS);
    res.set('Access-Control-Allow-Methods', ALLOW_METHODS);
    res.set('Access-Control-Max-Age', '600');
  }
  // Preflights end here; unknown origins get a bare 204, so the browser blocks the real request.
  if (req.method === 'OPTIONS' && req.headers['access-control-request-method']) return res.status(204).end();
  next();
}

export function securityHeaders(req, res, next) {
  res.removeHeader('X-Powered-By');
  res.set('Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=()');
  res.set('Cross-Origin-Embedder-Policy', 'credentialless');
  res.set('Cross-Origin-Opener-Policy', 'same-origin');
  res.set('X-Content-Type-Options', 'nosniff');
  res.set('X-Frame-Options', 'DENY');
  next();
}
