#!/usr/bin/env bash
# Usage: scripts/log.sh <channel> <community> "<title>" <url> <campaign> [notes]
# Appends one row to data/tracker.csv (metrics filled later).
set -euo pipefail
cd "$(dirname "$0")/.."
q() { printf '"%s"' "$(printf '%s' "${1:-}" | sed 's/"/""/g')"; }
echo "$(date +%F),$(q "$1"),$(q "$2"),$(q "$3"),$(q "$4"),$(q "$5"),,,,,$(q "${6:-}")" >> data/tracker.csv
echo "logged -> data/tracker.csv"
