#!/usr/bin/env bash
# Doc 12 Task 21 — the negative control, and arguably the more persuasive script.
# Posts 30 tickets about 30 unrelated things over the same kind of window a storm
# would use, then asserts the gate proposes NOTHING. "It found a pattern" is a weak
# claim on its own; "it distinguishes a pattern from noise" is the one worth having,
# and this is the only script that can demonstrate it.
#
#   ./demo/no-storm.sh [BASE_URL]
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${1:-http://localhost:8080}"
SEED_PASSWORD="resolveai-local-2026"
BURST_FILE="demo/no-storm.json"

command -v jq >/dev/null 2>&1 || { echo "❌ jq is required"; exit 1; }

login() {
  curl -fsS -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"tenantSlug\":\"acme\",\"email\":\"$1\",\"password\":\"$SEED_PASSWORD\"}" \
    | jq -r '.accessToken'
}

echo "── logging in ──"
CUSTOMER_TOKEN=$(login "customer1@example.com")
AGENT_TOKEN=$(login "admin@acme.com")
[ "$CUSTOMER_TOKEN" != "null" ] && [ "$AGENT_TOKEN" != "null" ] || {
  echo "❌ login failed — is the local seed (IamSeeder) present?"
  exit 1
}

BASELINE=$(curl -fsS "$BASE_URL/api/v1/incidents?status=PROPOSED" \
  -H "Authorization: Bearer $AGENT_TOKEN" | jq '.data | length')

TICKET_COUNT=$(jq length "$BURST_FILE")
echo "── posting $TICKET_COUNT unrelated tickets over about two minutes ──"
START_EPOCH=$(date +%s)
for i in $(seq 0 $((TICKET_COUNT - 1))); do
  SUBJECT=$(jq -r ".[$i].subject" "$BURST_FILE")
  BODY=$(jq -r ".[$i].body" "$BURST_FILE")
  curl -fsS -X POST "$BASE_URL/api/v1/tickets" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: nostorm-$(date +%s%N)-$i" \
    -d "$(jq -n --arg s "$SUBJECT" --arg b "$BODY" '{subject:$s, body:$b}')" \
    >/dev/null
  # A normal rate of unrelated traffic, not a burst - the opposite shape from
  # storm.sh, which is the entire point of running both.
  sleep 4
done
ELAPSED=$(( $(date +%s) - START_EPOCH ))
printf 'posted %d tickets over %ds\n' "$TICKET_COUNT" "$ELAPSED"

echo "── waiting one full sweep interval (70s) ──"
sleep 70

AFTER=$(curl -fsS "$BASE_URL/api/v1/incidents?status=PROPOSED" \
  -H "Authorization: Bearer $AGENT_TOKEN" | jq '.data | length')

if [ "$AFTER" -gt "$BASELINE" ]; then
  echo "❌ an incident WAS proposed for an unrelated burst — the gate is too loose"
  exit 1
fi

echo "no incident proposed ✓"
