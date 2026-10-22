#!/usr/bin/env bash
# Generates local secrets outside Git and optional demo-only frontend settings.
# Safe to run repeatedly; use --force to rotate every secret.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS="$ROOT/.secrets"
FORCE="${1:-}"

mkdir -p "$SECRETS"
if [[ -f "$SECRETS/backend.properties" && "$FORCE" != "--force" ]]; then
  echo "Secrets already exist at $SECRETS/backend.properties (use --force to rotate)."
else
  JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
  HANDOVER_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
  umask 077
  cat > "$SECRETS/backend.properties" <<PROPS
# Generated locally. Never commit this file.
app.jwt.secret=$JWT_SECRET
app.handover.hmac-secret=$HANDOVER_SECRET
PROPS
  chmod 600 "$SECRETS/backend.properties"
  echo "Wrote $SECRETS/backend.properties"
fi

if [[ ! -f "$ROOT/frontend/.env.local" || "$FORCE" == "--force" ]]; then
  cat > "$ROOT/frontend/.env.local" <<'ENV'
# Local demo affordances. Remove this file for a production-like run.
VITE_DEMO_MODE=true
ENV
  echo "Wrote frontend/.env.local"
fi
