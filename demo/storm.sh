#!/usr/bin/env bash
# Doc 12 Task 21 — the demo. Posts 38 linguistically varied tickets about one payment
# outage over ~20 seconds, then polls the incident board until the correlation sweep
# proposes an incident (it wakes every 60s; the wait bound below covers a full sweep
# plus margin).
#
#   ./demo/storm.sh [BASE_URL]
#
# Uses the local seed tenant (see IamSeeder): tenantSlug "acme".
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${1:-http://localhost:8080}"
SEED_PASSWORD="resolveai-local-2026"
STORM_FILE="demo/storm.json"

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
  echo "❌ login failed — is the local seed (IamSeeder) present? Start the app with"
  echo "   spring.profiles.active=local against an empty database first."
  exit 1
}

TICKET_COUNT=$(jq length "$STORM_FILE")
echo "── posting $TICKET_COUNT tickets over ~20s ──"
START_EPOCH=$(date +%s)
POSTED=0
for i in $(seq 0 $((TICKET_COUNT - 1))); do
  SUBJECT=$(jq -r ".[$i].subject" "$STORM_FILE")
  BODY=$(jq -r ".[$i].body" "$STORM_FILE")
  curl -fsS -X POST "$BASE_URL/api/v1/tickets" \
    -H "Authorization: Bearer $CUSTOMER_TOKEN" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: storm-$(date +%s%N)-$i" \
    -d "$(jq -n --arg s "$SUBJECT" --arg b "$BODY" '{subject:$s, body:$b}')" \
    >/dev/null
  POSTED=$((POSTED + 1))
  if [ $((POSTED % 12)) -eq 0 ] || [ "$POSTED" -eq "$TICKET_COUNT" ]; then
    ELAPSED=$(( $(date +%s) - START_EPOCH ))
    printf '%02d:%02d:%02d  posted %d tickets\n' \
      $((ELAPSED/3600)) $(((ELAPSED%3600)/60)) $((ELAPSED%60)) "$POSTED"
  fi
  # Jittered gap so the 38 posts spread across ~20 seconds rather than landing as
  # one instant, which is closer to how a real storm actually arrives.
  sleep "0.$((RANDOM % 5 + 3))"
done

echo "── waiting for the correlation sweep (up to 90s) ──"
DEADLINE=$((START_EPOCH + 90))
FOUND=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  BOARD=$(curl -fsS "$BASE_URL/api/v1/incidents?status=PROPOSED" \
    -H "Authorization: Bearer $AGENT_TOKEN")
  COUNT=$(echo "$BOARD" | jq '.data | length')
  if [ "$COUNT" -gt 0 ]; then
    FOUND=$(echo "$BOARD" | jq -r '.data[0]')
    break
  fi
  sleep 3
done

if [ -z "$FOUND" ]; then
  echo "❌ no incident proposed within 90s"
  exit 1
fi

ELAPSED=$(( $(date +%s) - START_EPOCH ))
TITLE=$(echo "$FOUND" | jq -r '.title')
LINKED=$(echo "$FOUND" | jq -r '.linkedTicketCount')
RATE=$(echo "$FOUND" | jq -r '.detection.arrivalRateMultiple')
DETECT_S=$(echo "$FOUND" | jq -r '.timeToDetectSeconds')
printf '%02d:%02d:%02d  ⚠ INCIDENT PROPOSED — "%s"\n' \
  $((ELAPSED/3600)) $(((ELAPSED%3600)/60)) $((ELAPSED%60)) "$TITLE"
echo "          $LINKED linked · detected ${DETECT_S}s after first report · ${RATE}x baseline"
