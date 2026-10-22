#!/usr/bin/env bash
# Runs the API through portless; the app still binds 127.0.0.1:8080 and the
# named origin campuslost-api.localhost points at it.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/java-env.sh" >/dev/null
if [[ ! -f "$ROOT/.secrets/backend.properties" ]]; then
  echo "Run scripts/bootstrap-local.sh first." >&2
  exit 1
fi
if ! command -v portless >/dev/null 2>&1; then
  echo "portless is required for dev servers. Install it, then retry." >&2
  exit 1
fi
cd "$ROOT/backend"
exec portless campuslost-api --app-port 8080 ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
