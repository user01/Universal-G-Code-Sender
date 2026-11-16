#!/usr/bin/env bash
# Serve the live machine-session checklist on http://127.0.0.1:8790/ (loopback only).
# Claude keeps bridge/checklist/state.json up to date; the page polls it every 2 s.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/../checklist" && pwd)"
PORT="${1:-8790}"
echo "Checklist: http://127.0.0.1:$PORT/"
exec python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$DIR"
