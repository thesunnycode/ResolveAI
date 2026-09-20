#!/usr/bin/env bash
# Phase 2 Task 14 — the full OpenAPI check, in the order that finds problems fastest.
#
#   1. Redocly lint — real OpenAPI 3.1 structural validation, and it validates every
#      `example` against its own schema. This is what catches an example that drifted
#      away from the contract it is supposed to illustrate.
#   2. ops/verify-openapi.mjs — the project-specific assertions Redocly has no opinion
#      about: $refs resolve, operationIds unique, the twelve promised endpoints still
#      carry examples.
#
# Both run in CI (doc 13 T16).
#
#   bash ops/verify-openapi.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "── redocly lint ──"
npx --yes @redocly/cli@latest lint docs/openapi.yaml --config ops/redocly.yaml --format=stylish

echo
echo "── project assertions ──"
node ops/verify-openapi.mjs
