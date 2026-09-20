# Postman collection

Phase 2, Task 15. Built from [`docs/openapi.yaml`](../../docs/openapi.yaml) — **77 requests
covering all 64 operations**, plus a `Smoke Test` folder and eleven deliberate
negative-path requests.

```
ResolveAI.postman_collection.json          the collection
ResolveAI.local.postman_environment.json   environment template — passwords blank, by design
```

## Setup, once

1. Import both files into Postman.
2. Select the **ResolveAI — local** environment.
3. Fill in `agentPassword`, `customerPassword` and `adminPassword`. **Do not commit them back**
   — `ops/verify-postman.mjs` fails the build if a secret-shaped key has a value.
4. Run **Auth › Login**.

That last step is the whole point. Its post-response script writes `accessToken` and
`refreshToken` into the environment, and collection-level bearer auth picks the access token
up for every other request. **Do this once here, or paste tokens by hand several hundred
times over the next six months.**

## What the import did not give you

An OpenAPI import produces requests with placeholder bodies and no chaining. Added by hand:

| | |
|---|---|
| **Token capture** | `Login` and `Refresh` both store their tokens. Nothing else ever needs an `Authorization` header. |
| **Fresh idempotency keys** | A collection-level pre-request script mints a UUID v4 into `{{idempotencyKey}}` on every send, so the endpoints that require the header never replay a stale key by accident. |
| **Chaining** | `Create ticket` captures `ticketId` and `ETag`; `Incident board` captures `incidentId`; `Request draft` captures `draftId`. The Tickets folder runs top to bottom with no manual editing. |
| **Assertions** | 42 of 77 requests assert something specific. A `200` on its own proves very little. |
| **Realistic bodies** | Copied from [doc 05](../../docs/planning/05-API-CONTRACT.md), not `"string"`. |

## The requests that assert a design decision

These are the ones worth running in front of someone:

| Request | What it proves |
|---|---|
| `Login — wrong password` | `401 INVALID_CREDENTIALS` with no hint about *which* half was wrong — the endpoint is not a user-enumeration oracle. |
| `Create ticket — replay same key` | Pins the previous `Idempotency-Key` and asserts the **same ticket id** comes back. A second ticket here is the bug idempotency exists to prevent. |
| `Get ticket — another tenant's id` | **404, not 403.** A 403 would confirm the ticket exists and turn every id into a probe. |
| `Change status — illegal transition` | `409 ILLEGAL_TRANSITION` carrying `allowedTransitions`, so a client can recover without hard-coding the state machine. |
| `Get SLA` | Asserts at most one open segment per clock, that `elapsedBusinessMinutes` equals the `SUM` of RUNNING segments, and that a paused clock has `nextDeadlineAt: null`. **Those three properties are the SLA design**, checked from outside. |
| `Get draft` | Every *kept* claim has a citation; every *rejected* claim has a reason; a suppressed draft has `assembledText: null` rather than a hedge. |
| `Hybrid search (explain)` | Results carry both the lexical and the vector rank, and are ordered by the fused score. |
| `Confirm incident` | `firstResponseClocksUnaffected === ticketsLinked` — resolution pauses during an outage, acknowledgement does not. |
| `Update SLA policy` | The old row is **superseded, not overwritten**. |
| `Fan-out deliveries` | `sent + pending + failed === total`. |

## Smoke Test

Eight requests that prove the system works end to end. This is the post-deploy check in
[doc 13 Task 17](../../docs/planning/13-TASK-BREAKDOWN-PHASE-9-10.md).

```bash
newman run ops/postman/ResolveAI.postman_collection.json \
  -e ops/postman/ResolveAI.local.postman_environment.json \
  --folder "Smoke Test"
```

1. **Health** — app, database *and* Redis, so a green check cannot hide a dead dependency
2. **Login**
3. **Create ticket**
4. **Triage completed** — polls up to 20× at 1s; **this is the one that proves the async path
   works**, not just the HTTP layer: outbox row written, poller picked it up, worker called the
   model, result persisted. `UNAVAILABLE` is a warning, not a failure — the provider may be
   down and the ticket is still workable.
5. **Ticket is in the queue** — full-text search and the read path
6. **SLA clocks started** — two clocks, one open segment each, deadlines in the future
7. **Assign and reply** — the reply and the clock-stop commit together
8. **No dead outbox events** — the check most likely to catch a broken deploy that every other
   check passes: work being accepted and silently dying in the outbox

Run the whole collection instead of one folder and the negative-path requests run too — but
several of them (`Reject incident`, `Delete document`, `Change password`) mutate state you may
want to keep. The Smoke Test folder is the safe one to automate.

## Verification

```bash
node ops/verify-postman.mjs
```

Checks that every operation in `openapi.yaml` has a request **and** that no request targets a
path the spec does not define — drift in either direction is a defect. Also asserts login still
stores its tokens, the Smoke Test folder still has eight requests, and **that no secret was
committed**, by key name and by scanning both files for anything shaped like an API key or a
real JWT.
