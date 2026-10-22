#!/usr/bin/env bash
# Starts the frontend through portless so the named URL works.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"
if ! command -v portless >/dev/null 2>&1; then
  echo "portless is required for dev servers. Install it, then retry." >&2
  exit 1
fi
exec portless campuslost --app-port 3000 pnpm dev
