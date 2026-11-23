import { WebSocketServer } from 'ws';
import { redeemTicket } from './auth.js';
import { db, now } from './db.js';

// userId -> Set<{ ws, deviceId, kind }>
const clients = new Map();

export function attachHub(server) {
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 4096 }) // clients only send small pings;
  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://x');
    // One-time tickets only: access tokens in URLs end up in proxy logs. Every released client fetches a ticket first.
    const p = redeemTicket(url.searchParams.get('ticket'));
    if (!p) { ws.close(4401, 'unauthorized'); return; }
    const dev = p.did ? db.prepare('SELECT kind FROM devices WHERE id=?').get(p.did) : null;
    const entry = { ws, deviceId: p.did || null, kind: dev?.kind || 'desktop', userId: p.sub };
    if (!clients.has(p.sub)) clients.set(p.sub, new Set());
    clients.get(p.sub).add(entry);
    if (entry.deviceId) db.prepare('UPDATE devices SET last_seen_at=? WHERE id=?').run(now(), entry.deviceId);
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw);
        if (msg.type === 'ping') ws.send(JSON.stringify({ type: 'pong', t: now() }));
      } catch { /* ignore */ }
    });
    ws.on('close', () => clients.get(p.sub)?.delete(entry));
    ws.send(JSON.stringify({ type: 'hello', kind: entry.kind }));
  });
  setInterval(() => {
    for (const set of clients.values()) for (const c of set) {
      if (!c.ws.isAlive) { c.ws.terminate(); set.delete(c); continue; }
      c.ws.isAlive = false; c.ws.ping();
    }
  }, 25_000).unref();
}

/** Send to a user's connected clients. filter: 'android' | 'desktop' | undefined (all) */
export function notify(userId, message, filter) {
  let delivered = 0;
  for (const c of clients.get(userId) || []) {
    if (filter && c.kind !== filter) continue;
    if (c.ws.readyState === 1) { c.ws.send(JSON.stringify(message)); delivered++; }
  }
  return delivered;
}

export const onlineDevices = (userId) => [...(clients.get(userId) || [])].map(c => c.deviceId).filter(Boolean);
