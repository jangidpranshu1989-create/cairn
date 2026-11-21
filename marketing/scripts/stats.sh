#!/usr/bin/env bash
# Usage: CAIRN_ADMIN_KEY=... scripts/stats.sh [days]
# Prints a short growth summary from the Cairn analytics API. Needs: curl, jq (pkg install curl jq).
# Never commit the key. Put `export CAIRN_ADMIN_KEY=...` in ~/.bashrc instead.
set -euo pipefail
days="${1:-7}"
: "${CAIRN_ADMIN_KEY:?set CAIRN_ADMIN_KEY first (export CAIRN_ADMIN_KEY=...)}"
api="${CAIRN_API:-https://cairn-cloud.onrender.com}"
json="$(curl -fsS --max-time 60 -H "Authorization: Bearer $CAIRN_ADMIN_KEY" "$api/api/admin/analytics?days=$days")" || {
  echo "API call failed (server may be waking up on Render free tier, retry in 30 s)"; exit 1; }
if [ "${2:-}" = "--raw" ]; then echo "$json"; exit 0; fi
# GitHub counts straight from GitHub (server-side call is often rate-limited on Render)
gh_total="$(curl -fsS --max-time 20 "https://api.github.com/repos/jangidpranshu1989-create/cairn/releases?per_page=100" \
  | jq '[.[].assets[] | select(.name|test("Analytics")|not) | .download_count] | add // 0' 2>/dev/null || echo n/a)"
echo "$json" | jq -r --arg d "$days" --arg gh "$gh_total" '
  def top(a): (a // []) | .[:8] | if length==0 then "  (none yet)" else map("  - \(.k): \(.n)") | join("\n") end;
  .kpis as $k |
  "== Cairn stats, last \($d) days ==",
  "Active right now: \(.realtime.active // 0) (apps \(.realtime.apps // 0), web \(.realtime.web // 0))",
  "Users total: \($k.totalUsers) / goal \($k.goal)  | DAU \($k.dau)  WAU \($k.wau)  MAU \($k.mau)  new today \($k.newToday)",
  "AI devices: \($k.aiDevices)  accounts: \($k.accounts)  web visitors today \($k.webVisitorsToday) / 30d \($k.webVisitors30d)",
  "GitHub downloads (all release files): \(if (.github.total // 0) > 0 then .github.total else $gh end)",
  "Top UTM (source/campaign):", top(.web.utm),
  "Top referrers:", top(.web.referrers),
  "Top pages:", top(.web.pages),
  "Countries:", top(.web.countries),
  "Download clicks:", top(.web.downloadClicks)'
