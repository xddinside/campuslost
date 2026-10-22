#!/usr/bin/env bash
# Starts the frontend through portless when available so the named URL works.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/frontend"
if command -v portless >/dev/null 2>&1; then
  exec portless campuslost --app-port 3000 pnpm dev
fi
exec pnpm dev
