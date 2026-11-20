// Permanent storage on Supabase (free tier) for hosts whose disk is wiped on every restart / redeploy (Koyeb, Render,
// pplx.app previews). The server keeps working on its fast local SQLite file; this module
//   1) restores the latest copy from a private Supabase Storage bucket on boot,
//   2) uploads a compressed snapshot whenever the data changed (at most every SNAPSHOT_SECONDS, default 5 s),
//   3) flushes one last snapshot on shutdown (SIGTERM / SIGINT), and
//   4) keeps one dated backup per day under backups/.
// Set SUPABASE_URL + SUPABASE_SERVICE_ROLE_KEY (server-side secret, never ship it in an app). Bucket: SUPABASE_BUCKET.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { config } from './config.js';

const enabled = () => !!(config.supabaseUrl && config.supabaseKey);
const base = () => config.supabaseUrl.replace(/\/+$/, '');
const headers = (extra = {}) => ({ Authorization: `Bearer ${config.supabaseKey}`, apikey: config.supabaseKey, ...extra });
const OBJ = 'cairn.db.gz';

async function ensureBucket() {
  const r = await fetch(`${base()}/storage/v1/bucket`, {
    method: 'POST', headers: headers({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ id: config.supabaseBucket, name: config.supabaseBucket, public: false }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!r.ok && r.status !== 409 && r.status !== 400) throw new Error(`bucket ${r.status}: ${(await r.text()).slice(0, 160)}`);
}

async function upload(name, buf) {
  const r = await fetch(`${base()}/storage/v1/object/${config.supabaseBucket}/${name}`, {
    method: 'POST', headers: headers({ 'Content-Type': 'application/gzip', 'x-upsert': 'true', 'cache-control': 'no-cache' }),
    body: buf, signal: AbortSignal.timeout(60_000),
  });
  if (!r.ok) throw new Error(`upload ${r.status}: ${(await r.text()).slice(0, 160)}`);
}

/** Downloads the last snapshot into DATA_DIR/cairn.db before SQLite opens it. Returns true if restored. */
export async function restore(file) {
  if (!enabled()) return false;
  try {
    await ensureBucket();
    const r = await fetch(`${base()}/storage/v1/object/${config.supabaseBucket}/${OBJ}`, { headers: headers(), signal: AbortSignal.timeout(60_000) });
    if (r.status === 404 || r.status === 400) { console.log('[supabase] no snapshot yet; starting fresh'); return false; }
    if (!r.ok) throw new Error(`download ${r.status}: ${(await r.text()).slice(0, 160)}`);
    const raw = zlib.gunzipSync(Buffer.from(await r.arrayBuffer()));
    if (raw.subarray(0, 15).toString() !== 'SQLite format 3') throw new Error('snapshot is not a SQLite database');
    for (const s of ['', '-wal', '-shm']) fs.rmSync(file + s, { force: true });
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, raw);
    console.log(`[supabase] restored ${(raw.length / 1024).toFixed(0)} KB from ${config.supabaseBucket}/${OBJ}`);
    return true;
  } catch (e) {
    // Refuse to start with an empty database over real data if Supabase is configured but unreachable.
    if (config.supabaseRequired) throw new Error(`[supabase] restore failed: ${e.message}`);
    console.error('[supabase] restore failed, continuing with the local file:', e.message);
    return false;
  }
}

/** Starts change-driven snapshots of a better-sqlite3 database. */
export function startSnapshots(db) {
  if (!enabled()) return;
  const changes = db.prepare('SELECT total_changes() AS c');
  let saved = -1, busy = false, lastDaily = '';
  const snap = async (why) => {
    if (busy) return; busy = true;
    try {
      const now = changes.get().c;
      if (now === saved && why === 'tick') return;
      // Online backup API: a consistent copy that includes pages still in the WAL (serialize() can miss them).
      const tmp = path.join(config.dataDir, '.snapshot.db');
      await db.backup(tmp);
      const gz = zlib.gzipSync(fs.readFileSync(tmp), { level: 6 });
      fs.rmSync(tmp, { force: true });
      await upload(OBJ, gz);
      saved = now;
      const day = new Date().toISOString().slice(0, 10);
      if (day !== lastDaily) { await upload(`backups/cairn-${day}.db.gz`, gz).catch(() => {}); lastDaily = day; }
      if (why !== 'tick') console.log(`[supabase] snapshot saved (${why}, ${(gz.length / 1024).toFixed(0)} KB)`);
    } catch (e) { console.error('[supabase] snapshot failed:', e.message); }
    finally { busy = false; }
  };
  // Small DATA_DIR files (generated secrets, OIDC key) — restored by config.js on boot.
  const saveFiles = async () => {
    const files = {};
    for (const f of fs.readdirSync(config.dataDir, { withFileTypes: true })) {
      if (!f.isFile() || /^cairn\.db|^\.snapshot/.test(f.name) || !/^[\w.-]+$/.test(f.name)) continue;
      const full = path.join(config.dataDir, f.name);
      if (fs.statSync(full).size <= 64 * 1024) files[f.name] = fs.readFileSync(full).toString('base64');
    }
    const r = await fetch(`${base()}/storage/v1/object/${config.supabaseBucket}/files.json`, {
      method: 'POST', headers: headers({ 'Content-Type': 'application/json', 'x-upsert': 'true' }), body: JSON.stringify(files), signal: AbortSignal.timeout(30_000),
    });
    if (!r.ok) throw new Error(`files ${r.status}`);
  };
  setTimeout(() => saveFiles().catch(e => console.error('[supabase] saving DATA_DIR files failed:', e.message)), 2500).unref();
  setTimeout(() => snap('boot'), 3000).unref();
  // Change-driven: look for writes every second and save at most once per SNAPSHOT_SECONDS (default 5 s), so a
  // redeploy or crash on Render's ephemeral disk loses at most a few seconds of data.
  let lastSnap = 0;
  setInterval(() => {
    if (busy || changes.get().c === saved || Date.now() - lastSnap < Math.max(2, config.snapshotSeconds) * 1000) return;
    lastSnap = Date.now(); snap('tick');
  }, 1000).unref();
  let exiting = false;
  const flush = async (sig) => {
    if (exiting) return; exiting = true;
    busy = false; saved = -1;
    await Promise.race([snap(sig), new Promise(r => setTimeout(r, 8000))]);
    process.exit(0);
  };
  process.once('SIGTERM', () => flush('SIGTERM'));
  process.once('SIGINT', () => flush('SIGINT'));
  console.log(`[supabase] permanent storage on (bucket ${config.supabaseBucket}, every ${config.snapshotSeconds}s when changed)`);
}

export const supabaseEnabled = enabled;
