#!/usr/bin/env bash
# Phase 2 Task 13 — migration repeatability.
#
# Drops the database, migrates from truly empty, asserts the schema, and runs the
# six structural guarantees. Runs in CI (doc 13 T16) and is what the
# `docker compose down -v` line in every phase's exit checklist actually exercises.
#
#   bash ops/verify-migrations.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PG=resolveai-postgres
NET=resolveai_default
MIG="$(pwd)/src/main/resources/db/migration"
DBURL=jdbc:postgresql://${PG}:5432/resolveai

fail=0
expect() { # label expected actual
  if [ "$2" = "$3" ]; then printf "  ✅ %-34s %s\n" "$1" "$3"
  else printf "  ❌ %-34s expected %s, got %s\n" "$1" "$2" "$3"; fail=1; fi
}
q() { docker compose exec -T postgres psql -U resolveai -d resolveai -tAc "$1" | tr -d '\r'; }

echo "── dropping and recreating the database ──"
docker compose exec -T postgres psql -U resolveai -d postgres -q \
  -c "DROP DATABASE IF EXISTS resolveai WITH (FORCE);" \
  -c "CREATE DATABASE resolveai OWNER resolveai;" >/dev/null
echo "  database is empty (extensions gone — V1 must create them)"

echo "── flyway migrate ──"
docker run --rm --network "$NET" -v "/${MIG}:/flyway/sql" flyway/flyway:10 \
  -url="$DBURL" -user=resolveai -password=resolveai_local -connectRetries=10 \
  -outputType=json migrate 2>/dev/null \
  | grep -oE '"migrationsExecuted"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+$' \
  | xargs -I{} echo "  applied: {} migrations"

echo "── schema assertions ──"
expect "migrations succeeded"   "7"  "$(q "SELECT count(*) FROM flyway_schema_history WHERE success")"
expect "tables"                 "39" "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'")"
expect "partial indexes"        "23" "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%WHERE%'")"
expect "triggers"               "10" "$(q "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal")"
expect "hnsw indexes"           "2"  "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%hnsw%'")"
expect "gin indexes"            "3"  "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%gin%'")"
expect "vector extension"       "1"  "$(q "SELECT count(*) FROM pg_extension WHERE extname='vector'")"
expect "pg_trgm extension"      "1"  "$(q "SELECT count(*) FROM pg_extension WHERE extname='pg_trgm'")"
expect "foreign keys"           "73" "$(q "SELECT count(*) FROM pg_constraint WHERE contype='f'")"

echo "── structural guarantees ──"
verdict=$(docker compose exec -T postgres psql -U resolveai -d resolveai -tA \
            -f - < ops/structural-guarantees.sql 2>/dev/null | grep -E "GUARANTEES HOLD|FAILURES" | tr -d '\r')
if [[ "$verdict" == *"GUARANTEES HOLD"* ]]; then printf "  ✅ %s\n" "$verdict"
else printf "  ❌ %s\n" "$verdict"; fail=1; fi

echo
if [ $fail -eq 0 ]; then echo "✅ migrations verified from empty"; else echo "❌ verification failed"; exit 1; fi
