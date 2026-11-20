# Deploy Cairn Cloud on Koyeb (free, no sleep)

1. Sign in at https://app.koyeb.com with GitHub → **Create Web Service** → **GitHub** → pick `jangidpranshu1989-create/cairn`.
2. Builder: **Dockerfile**, work directory `server`, Dockerfile `Dockerfile`. Instance: **Free** (Frankfurt or Washington).
3. Port: `8787` (HTTP), health check path `/api/health`.
4. Environment variables:
   | Name | Value |
   |---|---|
   | `NODE_ENV` | `production` |
   | `DATA_DIR` | `/tmp/cairn-data` |
   | `JWT_SECRET` | output of `openssl rand -hex 32` |
   | `PUBLIC_URL` | `https://<your-app>.koyeb.app` |
   | `RP_ID` | `<your-app>.koyeb.app` |
   | `RP_ORIGINS` | `https://<your-app>.koyeb.app` |
   | `GROQ_API_KEY` | free key from https://console.groq.com/keys (needed for camera/photo questions) |
   | `GEMINI_API_KEY` | optional, free key from https://aistudio.google.com/apikey |
   | `SUPABASE_URL` | `https://<project-ref>.supabase.co` (recommended permanent database, see below) |
   | `SUPABASE_SERVICE_ROLE_KEY` | Supabase → Project Settings → API Keys → `service_role` / secret key (server only) |
   | `OPENROUTER_API_KEY` | optional, free key from https://openrouter.ai/keys (extra AI fallback) |
   | `TURSO_DATABASE_URL` / `TURSO_AUTH_TOKEN` | alternative to Supabase: from `turso db show cairn --url` / `turso db tokens create cairn` |
5. Deploy. Check `https://<your-app>.koyeb.app/api/health` returns `{"ok":true,...}`.
6. In the app: Settings → Account → Cairn Cloud URL → paste the URL (or build the APK with `-PcairnServer=<url>` once it's stable).

## Keep it awake (Render only)
Render free services sleep after 15 minutes. Create a free monitor at https://uptimerobot.com → HTTP(s) → `<url>/api/health` → every 5 minutes.

## Turso (free database that survives restarts)
```
curl -sSfL https://get.tur.so/install.sh | bash
turso auth signup
turso db create cairn --location bom   # Mumbai, closest to India
turso db show cairn --url              # → TURSO_DATABASE_URL
turso db tokens create cairn           # → TURSO_AUTH_TOKEN
```
The server keeps a local replica and syncs with Turso every 30 s; nothing else changes.

## Supabase (free permanent database, recommended)
1. Create a free project at https://supabase.com/dashboard (region: Mumbai `ap-south-1`).
2. Project Settings → API Keys → copy the **Project URL** and the **service_role / secret** key.
3. Set `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` on Koyeb (or Render / any host). Nothing else to create: on first
   boot the server makes a private Storage bucket `cairn` (change with `SUPABASE_BUCKET`).

How it works: the server runs on its fast local SQLite file and uploads a compressed snapshot to the bucket within
15 s of any change (`SNAPSHOT_SECONDS`), plus one final snapshot when the host stops it, and keeps one dated copy per day
in `backups/`. On every boot it restores the latest snapshot and the generated secrets (JWT / OIDC keys), so accounts,
sync data, devices and logins survive restarts and redeploys. `/api/health` shows `"storage":"supabase"` when it is on.
Set `SUPABASE_REQUIRED=1` to refuse to start (instead of starting empty) if Supabase can't be reached.
