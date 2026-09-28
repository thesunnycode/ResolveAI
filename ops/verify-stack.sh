#!/usr/bin/env bash
# Phase 1 Task 4 verification, as a repeatable script.
# The forerunner of ops/verify-migrations.sh (doc 16 Task 13).
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
chk() { if eval "$2" >/dev/null 2>&1; then echo "  ✅ $1"; else echo "  ❌ $1"; fail=1; fi; }

echo "── containers ──"
for s in postgres redis minio mailhog prometheus grafana ollama; do
  chk "$s running" "docker compose ps --status running --services | grep -qx $s"
done

echo "── postgres ──"
chk "accepts connections" "docker compose exec -T postgres pg_isready -U resolveai -d resolveai"
chk "pgvector >= 0.5 (HNSW)" \
  "docker compose exec -T postgres psql -U resolveai -d resolveai -tAc \"SELECT 1 FROM pg_extension WHERE extname='vector' AND string_to_array(extversion,'.')::int[] >= ARRAY[0,5]\" | grep -q 1"
chk "pg_trgm present" \
  "docker compose exec -T postgres psql -U resolveai -d resolveai -tAc \"SELECT 1 FROM pg_extension WHERE extname='pg_trgm'\" | grep -q 1"

echo "── redis ──"
chk "responds to PING" "docker compose exec -T redis redis-cli ping | grep -q PONG"

echo "── http ──"
chk "minio        :9000" "curl -fsS --max-time 5 http://localhost:9000/minio/health/live"
chk "mailhog      :8025" "curl -fsS --max-time 5 http://localhost:8025/api/v2/messages"
chk "prometheus   :9090" "curl -fsS --max-time 5 http://localhost:9090/-/healthy"
chk "grafana      :3000" "curl -fsS --max-time 5 http://localhost:3000/api/health"
chk "ollama      :11434" "curl -fsS --max-time 5 http://localhost:11434/api/tags"

echo
if [ $fail -eq 0 ]; then echo "✅ stack verified"; else echo "❌ stack has failures"; exit 1; fi
