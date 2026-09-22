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
MIG="$(pwd)/src/main/resources/db/migration"
DBURL=jdbc:postgresql://${PG}:5432/resolveai

# The compose network is named after the project, which is the directory name - so it is
# resolveai_default locally and whatever the checkout directory is called in CI. Ask Docker
# rather than assuming: a hardcoded name fails with "network not found", which reads like a
# Docker problem rather than a naming one.
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$PG" 2>/dev/null)
if [ -z "$NET" ]; then
  echo "❌ container $PG is not running — start it with: docker compose up -d postgres"
  exit 1
fi

# `docker compose up -d` returns as soon as the container is created, not when Postgres is
# accepting connections. Locally it is usually ready by the time anyone runs this; in CI it
# is usually not, and the failure looks like a migration error rather than a race.
echo "── waiting for postgres ──"
for i in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U resolveai -d postgres >/dev/null 2>&1; then
    echo "  ready after ${i}s (network: $NET)"
    break
  fi
  [ "$i" = 60 ] && { echo "❌ postgres did not become ready in 60s"; exit 1; }
  sleep 1
done

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
# Counts below were set at Phase 2 (V1-V7: 7 migrations, 39 tables, 73 FKs) and updated
# here at Phase 8 (V1-V14: Phase 7 added V8-V12 as described in the prior note; Phase 8
# adds V13, a materialized view — not a table, so the table count is unchanged — and
# V14, a prompt seed with no schema change). Tables, FKs and partial indexes are
# unchanged from Phase 7's count because doc 04's incident/incident_ticket/
# incident_update/incident_update_delivery/ticket_entity tables were already created at
# Phase 2 in V2/V5. A number that only ever goes up between phases and is never
# re-derived is a number nobody trusts; re-deriving it here each phase is what keeps
# this script worth running instead of worth ignoring.
expect "migrations succeeded"   "14" "$(q "SELECT count(*) FROM flyway_schema_history WHERE success")"
expect "tables"                 "41" "$(q "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history'")"
expect "partial indexes"        "23" "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%WHERE%'")"
expect "triggers"               "10" "$(q "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal")"
expect "hnsw indexes"           "2"  "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%hnsw%'")"
expect "gin indexes"            "3"  "$(q "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexdef ILIKE '%gin%'")"
expect "vector extension"       "1"  "$(q "SELECT count(*) FROM pg_extension WHERE extname='vector'")"
expect "pg_trgm extension"      "1"  "$(q "SELECT count(*) FROM pg_extension WHERE extname='pg_trgm'")"
expect "foreign keys"           "77" "$(q "SELECT count(*) FROM pg_constraint WHERE contype='f'")"

echo "── structural guarantees ──"
verdict=$(docker compose exec -T postgres psql -U resolveai -d resolveai -tA \
            -f - < ops/structural-guarantees.sql 2>/dev/null | grep -E "GUARANTEES HOLD|FAILURES" | tr -d '\r')
if [[ "$verdict" == *"GUARANTEES HOLD"* ]]; then printf "  ✅ %s\n" "$verdict"
else printf "  ❌ %s\n" "$verdict"; fail=1; fi

echo
if [ $fail -eq 0 ]; then echo "✅ migrations verified from empty"; else echo "❌ verification failed"; exit 1; fi
