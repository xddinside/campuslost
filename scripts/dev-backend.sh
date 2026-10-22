#!/usr/bin/env bash
# Runs the API on http://127.0.0.1:8080 with the local demo seed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/java-env.sh" >/dev/null
if [[ ! -f "$ROOT/.secrets/backend.properties" ]]; then
  echo "Run scripts/bootstrap-local.sh first." >&2
  exit 1
fi
cd "$ROOT/backend"
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
