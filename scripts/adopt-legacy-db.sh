#!/usr/bin/env bash
# One-time deliberate adoption of a pre-Flyway database. Backs up first,
# verifies the schema is the expected legacy shape, records row counts and
# ids, runs one boot with explicit baseline adoption, then re-checks.
#
# Usage: scripts/adopt-legacy-db.sh [database]   (default: lostfound)
set -euo pipefail
DB="${1:-lostfound}"
MYSQL="mariadb -ulostfound -plostfound"
BACKUP="backup-${DB}-pre-adoption-$(date +%Y%m%d%H%M%S).sql"

echo "==> 1. Backup"
mariadb-dump -ulostfound -plostfound "$DB" > "$BACKUP"
echo "    wrote $BACKUP"

echo "==> 2. Preflight: schema must be the expected legacy shape"
$MYSQL "$DB" -e "
SELECT CASE
  WHEN (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = '$DB' AND table_name = 'flyway_schema_history') > 0
  THEN 'FAIL: already has flyway_schema_history — nothing to adopt'
  ELSE 'ok: no flyway_schema_history' END AS check_history;
SELECT table_name FROM information_schema.tables
  WHERE table_schema = '$DB' AND table_name IN ('users', 'items', 'claims')
  ORDER BY table_name;
"
expected=$($MYSQL -N "$DB" -e "
SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = '$DB' AND table_name IN ('users', 'items', 'claims');")
history=$($MYSQL -N "$DB" -e "
SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = '$DB' AND table_name = 'flyway_schema_history';")
if [[ "$history" != "0" ]]; then
  echo "FAIL: $DB already has flyway_schema_history; adoption is not needed." >&2
  exit 1
fi
if [[ "$expected" != "3" ]]; then
  echo "FAIL: expected legacy tables users/items/claims (found $expected/3)." >&2
  echo "This script only adopts the pre-Flyway schema the app shipped." >&2
  exit 1
fi

echo "==> 3. Record pre-migration row counts and id ranges"
before=$($MYSQL -N "$DB" -e "
SELECT CONCAT('users=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM users;
SELECT CONCAT('items=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM items;
SELECT CONCAT('claims=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM claims;")
echo "$before" | sed 's/^/    before /'

echo "==> 4. Boot once with explicit baseline adoption"
echo "    Stopping after 'Started'; the server then exits on its own."
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/backend"
source "$ROOT/scripts/java-env.sh" >/dev/null
set +e
SPRING_FLYWAY_BASELINE_ON_MIGRATE=true timeout 300 ./mvnw -q spring-boot:run \
  > /tmp/adopt-boot.log 2>&1 &
boot=$!
for _ in $(seq 1 120); do
  if grep -q "Started BackendApplication" /tmp/adopt-boot.log 2>/dev/null; then break; fi
  if ! kill -0 $boot 2>/dev/null; then break; fi
  sleep 2
done
kill $boot 2>/dev/null
wait $boot 2>/dev/null
set -e
if ! grep -q "Started BackendApplication" /tmp/adopt-boot.log; then
  echo "FAIL: backend did not start; see /tmp/adopt-boot.log" >&2
  exit 1
fi
grep -E "Successfully applied|Flyway" /tmp/adopt-boot.log | tail -3 | sed 's/^/    /'

echo "==> 5. Verify preserved data"
after=$($MYSQL -N "$DB" -e "
SELECT CONCAT('users=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM users;
SELECT CONCAT('items=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM items;
SELECT CONCAT('claims=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM claims;
SELECT CONCAT('schema=', MAX(version)) FROM flyway_schema_history;")
echo "$after" | sed 's/^/    after  /'
$MYSQL -N "$DB" -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' ORDER BY table_name" | sed 's/^/    table: /'

echo "==> Done. Compare the before/after lines: every count and id range must match."
