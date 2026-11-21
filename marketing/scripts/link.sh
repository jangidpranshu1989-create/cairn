#!/usr/bin/env bash
# Usage: scripts/link.sh <source> <campaign> [page]
# Example: scripts/link.sh reddit indiatech        -> download page link with UTM
#          scripts/link.sh instagram bio index.html
# The website beacon records utm_source/utm_campaign, so the admin dashboard shows which post brought visitors.
set -euo pipefail
src="${1:?source e.g. reddit|x|instagram|youtube|linkedin|whatsapp|telegram|hn|ph}"
camp="${2:?campaign e.g. indiatech|launch|reel01}"
page="${3:-download.html}"
clean() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_-' '-' | sed 's/-*$//'; }
echo "https://jangidpranshu1989-create.github.io/cairn/${page}?utm_source=$(clean "$src")&utm_campaign=$(clean "$camp")"
