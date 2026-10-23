#!/usr/bin/env bash
# One-time deliberate adoption of a pre-Flyway database. Backs up first,
# verifies the schema is the expected legacy shape (tables AND the columns
# and indexes the app relies on), records row counts and id ranges, migrates
# with Flyway directly — no app server involved — then re-runs the same
# checks and fails on any mismatch.
#
# Usage: scripts/adopt-legacy-db.sh [database]   (default: lostfound)
set -euo pipefail
DB="${1:-lostfound}"
MYSQL="mariadb -ulostfound -plostfound"
DB_URL="jdbc:mariadb://localhost:3306/${DB}?useUnicode=true&characterEncoding=utf8"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP="backup-${DB}-pre-adoption-$(date +%Y%m%d%H%M%S).sql"

echo "==> 1. Backup of $DB"
mariadb-dump -ulostfound -plostfound "$DB" > "$BACKUP"
echo "    wrote $BACKUP"

echo "==> 2. Preflight: schema must be the expected legacy shape"
if [[ $($MYSQL -N "$DB" -e "
SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = '$DB' AND table_name = 'flyway_schema_history';") != "0" ]]; then
  echo "FAIL: $DB already has flyway_schema_history; adoption is not needed." >&2
  exit 1
fi

# The legacy tables must exist with the columns and constraints the
# migrations were written against — not merely tables with those names.
missing=$($MYSQL -N "$DB" -e "
SELECT GROUP_CONCAT(m.expected) FROM (
  SELECT CONCAT(tbl, '.', col) AS expected FROM (
    SELECT 'users' tbl, 'email' col UNION SELECT 'users', 'password_hash'
    UNION SELECT 'users', 'role' UNION SELECT 'items', 'reporter_id'
    UNION SELECT 'items', 'type' UNION SELECT 'items', 'status'
    UNION SELECT 'items', 'title' UNION SELECT 'claims', 'item_id'
    UNION SELECT 'claims', 'claimant_id' UNION SELECT 'claims', 'status'
    UNION SELECT 'claims', 'message'
  ) req
  WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.columns c
    WHERE c.table_schema = '$DB' AND c.table_name = req.tbl AND c.column_name = req.col
  )
) m;
SELECT CONCAT('uk_users_email') WHERE NOT EXISTS (
  SELECT 1 FROM information_schema.statistics s
  WHERE s.table_schema = '$DB' AND s.table_name = 'users'
    AND s.index_name = 'uk_users_email' AND s.column_name = 'email'
);")
if [[ -n "$missing" && "$missing" != "NULL" ]]; then
  echo "FAIL: $DB does not look like the app's legacy schema; missing: $missing" >&2
  echo "This script only adopts the pre-Flyway schema the app shipped." >&2
  exit 1
fi
echo "    legacy tables, columns, and indexes verified in $DB"

echo "==> 3. Record pre-migration row counts and id ranges"
before=$($MYSQL -N "$DB" -e "
SELECT CONCAT('users=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM users;
SELECT CONCAT('items=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM items;
SELECT CONCAT('claims=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM claims;")
echo "$before" | sed 's/^/    before /'

echo "==> 4. Migrate with Flyway directly (no server, baseline adopted explicitly)"
cd "$ROOT/backend"
source "$ROOT/scripts/java-env.sh" >/dev/null
# The newest V-file this script must produce — a partial chain is a failure.
# Shared migrations live in common/; the MariaDB dialect folder holds V2.
expected=$(ls src/main/resources/db/migration/{common,mariadb}/V*__*.sql \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)
expected_count=$(ls src/main/resources/db/migration/{common,mariadb}/V*__*.sql | wc -l | tr -d ' ')
flyway_log=$(mktemp)
if ! timeout 300 ./mvnw -q org.flywaydb:flyway-maven-plugin:12.4.0:migrate \
  -Dflyway.url="$DB_URL" \
  -Dflyway.user=lostfound \
  -Dflyway.password=lostfound \
  -Dflyway.locations="filesystem:src/main/resources/db/migration/common,filesystem:src/main/resources/db/migration/mariadb" \
  -Dflyway.baselineOnMigrate=true >"$flyway_log" 2>&1; then
  grep -iE "error|fail" "$flyway_log" | head -15 >&2 || true
  echo "FAIL: Flyway migration of $DB did not complete." >&2
  echo "    Restore from $BACKUP before retrying." >&2
  rm -f "$flyway_log"
  exit 1
fi
grep -iE "migrat|baseline" "$flyway_log" | head -15 || true
rm -f "$flyway_log"

echo "==> 5. Verify the same database was migrated and data preserved"
# Require the complete, successful chain: every applied version present, no
# failed rows, and the latest version equal to the newest migration file.
state=$($MYSQL -N "$DB" -e "
SELECT CONCAT(
  COALESCE(MAX(CAST(version AS UNSIGNED)), 0), ':',
  COALESCE(SUM(success = 0), 0), ':',
  COUNT(version))
FROM flyway_schema_history;" 2>/dev/null || echo "")
if [[ -z "$state" ]]; then
  echo "FAIL: no flyway_schema_history in $DB — nothing was migrated." >&2
  exit 1
fi
version="${state%%:*}"
if [[ "$state" != "${expected}:0:${expected_count}" ]]; then
  echo "FAIL: migration chain is incomplete or has failed rows (state=$state," >&2
  echo "    expected=${expected}:0:${expected_count}). Restore from $BACKUP." >&2
  exit 1
fi
after=$($MYSQL -N "$DB" -e "
SELECT CONCAT('users=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM users;
SELECT CONCAT('items=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM items;
SELECT CONCAT('claims=', COUNT(*), ':', COALESCE(MIN(id),0), '-', COALESCE(MAX(id),0)) FROM claims;")
echo "$after" | sed 's/^/    after  /'
echo "    schema=$version"

if [[ "$before" != "$after" ]]; then
  echo "FAIL: row counts or id ranges changed during migration." >&2
  echo "    Restore from $BACKUP before retrying." >&2
  exit 1
fi
echo "==> Done: $DB adopted at V$version with all rows preserved."
