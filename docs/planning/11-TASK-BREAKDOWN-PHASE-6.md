# 11 — Task Breakdown: Phase 6

> Skill: `task-breakdown` · Run once per phase · **Re-run at the start of Phase 7 or 8**

**Phase:** Phase 6 — Async Pipeline & AI Triage
**Goal:** Ticket creation returns `202` immediately; a transactional outbox and `SKIP LOCKED` workers drive triage asynchronously; the LLM emits structured signals and a versioned deterministic policy computes priority and routing.
**Tech stack:** Java 21 (virtual threads) · Spring Boot 3.3.x · Spring AI · PostgreSQL 16 · Redis · Resilience4j · WireMock · Testcontainers

---

# ⚠️ Stop and read this section before planning anything

## The estimate has doubled for the third consecutive phase

| Phase | [07](07-DEV-PHASES.md) said | Broken down | Factor |
|---|---|---|---|
| 4 — Auth | 4–5 days | 10–11 days | 2.2× |
| 5 — Ticketing + SLA | 8–11 days | 19–23 days | 2.2× |
| **6 — Async + AI Triage** | **6–8 days** | **20–22 days** | **2.8×** |

Three data points in a row is not noise. **The phase-level estimates in [07](07-DEV-PHASES.md) were systematically low by roughly 2×**, because they counted features and not the tests, DTOs, error paths, governance and plumbing between them. Phase 6 is worse than 2× because it is not one subsystem — it is **three**: the async infrastructure, the LLM integration layer, and the policy engines.

**Projected full-scope total: 86–100 working days ≈ 18–21 calendar weeks** at 3–4 h/day.

## That is too long, and you should descope now rather than in week 12

Five months is not a portfolio project; it is a job. And the deadline that matters — placement season — does not move. Three honest paths:

### Path A — Full scope, ~18–21 weeks
Everything in [02](02-PROJECT-PLAN.md). Viable **only** if you are targeting mid-2027 and can protect the time. Not recommended.

### Path B — Cut Phase 7, ~15–16 weeks
Drop the knowledge base, hybrid retrieval and citation-enforced drafting entirely. Saves ~14 days. You lose signature mechanism #3 and the entire RAG story. Still long.

### Path C — Recommended: ~11–12 weeks
Cut Phase 7 **and** take the lean path through Phase 6 (marked ⚠️ on individual tasks below, −8 days), cut attachments from 5A (−1 day), cut the customer portal and admin screens from the frontend (−3 days).

**What Path C still delivers:**
- The **SLA engine** — business-hours arithmetic, append-only clocks, restart-safe polling, exactly-once escalation *(signature mechanism #1)*
- **Incident correlation** — deterministic gate, human confirmation, idempotent fan-out *(signature mechanism #2)*
- A **real async pipeline** — transactional outbox, `SKIP LOCKED` workers, retries, DLQ
- **AI triage with structured outputs, PII redaction and a deterministic priority policy** — the "AI produces signals, code makes decisions" argument intact
- Every concurrency test, the eval harness for classification, a deployed demo and the README

**What you give up:** citation-enforced drafting and hybrid retrieval. That is a real loss on the AI-depth axis — but it is also the most common thing in every other candidate's portfolio, and it is the only part of this project a reviewer has seen before.

**Take Path C.** The remainder of this document marks every lean-path cut inline.

## A correction to Phase 5

[10 Task 32B](10-TASK-BREAKDOWN-PHASE-5.md) tests "50 tickets, 5 agents, auto-assignment running concurrently" — but **auto-assignment does not exist until Phase 6 Task 25.** Phase 5 only has manual `POST /assign`.

Fix: in Phase 5C, Task 32B tests **20 agents concurrently self-assigning 20 different tickets** and asserts every `open_count` increments exactly once. The least-loaded distribution test moves here as **Task 32**.

---

## Sub-phase structure

As with Phase 5, this is too large to run as one block. Four checkpoints:

| | Sub-phase | Tasks | Effort | Checkpoint |
|---|---|---|---|---|
| **6A** | Async pipeline infrastructure | 1–10 | ~19 h ≈ 6 days | `POST /tickets` returns `202`, workers run, **no AI at all** |
| **6B** | AI safety, governance & LLM plumbing | 11–20 | ~24 h ≈ 7 days | A ticket can be classified, safely and within budget |
| **6C** | Triage worker & policy engines | 21–29 | ~17 h ≈ 5 days | Priority and routing computed deterministically |
| **6D** | Tests & close | 30–35 | ~9 h ≈ 3 days | Redelivery, failure and load tests |

**6A ends at a genuinely shippable state** — a fully asynchronous backend with zero AI. If you run out of time entirely, that alone closes the biggest gap on your resume.

---

# PHASE 6A — Async Pipeline Infrastructure

## TASK 1 — Create the `OutboxEvent` entity and repository with the claim query

**📝 Description**
Create `OutboxEvent` in `com.resolveai.platform.outbox` per [04 §Group 8](04-DATABASE-SCHEMA.md), and `OutboxRepository` with the claim statement:

```sql
UPDATE outbox_event
   SET status = 'IN_FLIGHT',
       locked_until = NOW() + (:visibilitySeconds * interval '1 second'),
       attempts = attempts + 1
 WHERE id IN (
     SELECT id FROM outbox_event
      WHERE status = 'PENDING'
        AND next_attempt_at <= NOW()
        AND event_type = ANY(:eventTypes)
      ORDER BY next_attempt_at
      LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
 )
RETURNING *;
```

**Claim-and-return in one statement, not select-then-update.** A separate UPDATE opens a window in which another worker can claim the same row; combining them means the predicate is evaluated under the lock the UPDATE takes.

**Do not map this through JPA for the claim path.** Hibernate's dirty checking and first-level cache actively fight a queue claim. Use `JdbcTemplate` with a `RowMapper`. The entity exists for the *writing* side (Task 2) and for admin reads.

**⛓️ Dependencies**
None — Phase 3 created the table.

**✅ Expected Output**
A test inserts 10 pending events, claims a batch of 5, and asserts: 5 returned, all `IN_FLIGHT` with `locked_until` set and `attempts = 1`; a second concurrent claim returns the **other 5**, not the same ones.

**⏱️ Estimated Time**
2 hours.

---

## TASK 2 — Implement `OutboxPublisher`

**📝 Description**
```java
@Transactional(propagation = MANDATORY)
public void publish(String aggregateType, Long aggregateId, String eventType, Object payload);
```

Serialises the payload to JSONB, sets `tenant_id` from `TenantContext`, `status = PENDING`, `next_attempt_at = NOW()`.

**`propagation = MANDATORY` is the whole point.** Calling this outside a transaction must fail loudly at development time — an event published in its own transaction is exactly the dual-write bug the outbox exists to prevent.

Define `EventType` as an enum, not free strings: `TICKET_CREATED`, `DRAFT_REQUESTED`, `KNOWLEDGE_DOCUMENT_ADDED`, `INCIDENT_UPDATE_PUBLISHED`, `TICKET_RESOLVED`.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
A test: publishing inside a transaction that then rolls back leaves zero rows. Publishing outside any transaction throws `IllegalTransactionStateException`.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 3 — Implement the generic `WorkerRuntime`

**📝 Description**
The core of the async design. Create `WorkerRuntime` plus a `Worker` interface:

```java
public interface Worker {
    Set<EventType> handles();
    Duration visibilityTimeout();     // default 2 min; triage needs 5
    int batchSize();                  // default 20
    void process(OutboxEvent event);  // throws → retry
}
```

`WorkerRuntime` runs one `@Scheduled(fixedDelay = 1000)` loop per registered `Worker`, claims a batch, and dispatches each event onto `Executors.newVirtualThreadPerTaskExecutor()`, awaiting completion before the next poll.

**The single most important structural rule in this phase, and put it in a class comment:**

> **A worker must never hold a database connection across an LLM call.**
>
> The connection pool is capped at 10. An LLM call takes 2–8 seconds. If the worker opens a transaction, calls the model, then writes, ten concurrent triages hold all ten connections for eight seconds each and **the entire application stops serving HTTP requests.** Virtual threads make it *easier* to hit this, not harder — they remove the thread limit that was accidentally protecting you.
>
> Every worker is therefore structured as: short transaction to read → **no transaction** for the network call → short transaction to write.

Also note the Java 21 caveat: `synchronized` blocks pin the carrier thread. Use `ReentrantLock` if you need mutual exclusion inside a worker.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
A no-op test worker registers and its loop claims and completes events. A test asserts that with 20 concurrent slow events, the HikariCP active-connection count never exceeds 2 — proving no connection is held during the simulated network call.

**⏱️ Estimated Time**
3.5 hours.

---

## TASK 4 — Implement retry, backoff and dead-lettering

**📝 Description**
On `process()` throwing:
```
attempts < maxAttempts (5)  → status = PENDING
                              next_attempt_at = NOW() + min(2^attempts, 300)s + jitter(0–30s)
                              last_error = truncated message
attempts >= maxAttempts     → status = DEAD, last_error recorded
```
On success: `status = DONE`, `processed_at = NOW()`.

**Jitter is not optional.** Without it, a provider outage that fails 200 events at once retries all 200 at the same instant, five times over — a self-inflicted thundering herd against a service that is already struggling.

Define a `NonRetryableException`: a malformed payload or a deleted aggregate goes straight to `DEAD` without burning five attempts.

Truncate `last_error` to 1,000 characters and **scrub it** — an exception message can contain a ticket body, and ticket bodies contain PII.

**⛓️ Dependencies**
Task 3.

**✅ Expected Output**
A worker that always throws: after 5 polls the event is `DEAD` with `attempts = 5`, and `next_attempt_at` grew 2s → 4s → 8s → 16s → 32s plus jitter. A `NonRetryableException` reaches `DEAD` on attempt 1.

**⏱️ Estimated Time**
2 hours.

---

## TASK 5 — Implement the outbox reaper

**📝 Description**
*(Moved here from Phase 5 in [07](07-DEV-PHASES.md), where it was listed against `sla_record` by mistake — the SLA poller holds no lock between transactions and needs no reaper.)*

`@Scheduled(fixedDelay = 60_000)`:
```sql
UPDATE outbox_event
   SET status = 'PENDING', locked_until = NULL
 WHERE status = 'IN_FLIGHT' AND locked_until < NOW()
```

This is what recovers from a worker that died mid-stage — a JVM crash, an OOM kill, a deploy in the middle of processing.

**Log every reap at WARN with the event id and type.** A steady trickle of reaped events means a worker is timing out rather than crashing, and the visibility timeout needs raising — a signal you will otherwise never see.

**⛓️ Dependencies**
Task 3.

**✅ Expected Output**
A test manually sets an event to `IN_FLIGHT` with `locked_until` in the past, runs the reaper, and asserts it returns to `PENDING` and is then claimable.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 6 — Wire outbox metrics and the lag health indicator

**📝 Description**
Fill in the `OutboxLagHealthIndicator` stub left in Phase 3 Task 11:
```sql
SELECT COALESCE(EXTRACT(EPOCH FROM NOW() - MIN(created_at)), 0)
  FROM outbox_event WHERE status = 'PENDING'
```
`DOWN` past 300 s, `UP` with the age as a detail otherwise.

Add Micrometer metrics: `outbox.claimed`, `outbox.succeeded`, `outbox.failed`, `outbox.dead` (counters, tagged by event type); `outbox.processing.duration` (timer, tagged by type); `outbox.queue.depth` and `outbox.oldest.pending.seconds` (gauges).

**Oldest-pending-age is the metric that matters, not queue depth.** A depth of 500 that drains in ten seconds is healthy; a depth of 3 where the oldest is 20 minutes old means something is stuck in a retry loop. Depth alone would not tell you.

**⛓️ Dependencies**
Tasks 3, 4.

**✅ Expected Output**
`/actuator/health` shows `outboxLag` with a real age. `/actuator/prometheus` exposes all six metrics. A stuck event drives the health indicator `DOWN`.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 7 — Implement the admin DLQ endpoints ⚠️ *lean path: defer to Phase 10*

**📝 Description**
`GET /api/v1/admin/outbox/dead` — paginated `DEAD` events with type, attempts, error and payload.
`POST /api/v1/admin/outbox/{id}/retry` — reset to `PENDING` with `attempts = 0`.
`POST /api/v1/admin/outbox/retry-all?eventType=` — bulk re-drive after fixing a systemic cause.

`ADMIN` only.

**⚠️ Lean path:** `UPDATE outbox_event SET status='PENDING', attempts=0 WHERE id=?` in psql does the same job during development. Defer the endpoints to Phase 10. **−1.5 h**

**⛓️ Dependencies**
Task 4.

**✅ Expected Output**
A dead event is listed, retried, and processes successfully.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 8 — Write the worker runtime test suite

**📝 Description**
`WorkerRuntimeTest extends IntegrationTestBase` with a controllable `TestWorker` (fail-N-times-then-succeed, block-until-released, throw-non-retryable).

1. **Happy path** — event published, claimed, processed once, `DONE`
2. **Concurrent claim** — two runtimes on a latch against 50 events; each processed **exactly once**; union equals 50
3. **Redelivery** — worker blocks past its visibility timeout, reaper returns it to `PENDING`, second worker completes it
4. **Backoff** — assert the `next_attempt_at` progression
5. **DLQ** — 5 failures → `DEAD`
6. **Connection discipline** — 20 concurrent events with a 2-second simulated network call; assert peak HikariCP active connections ≤ 2

Test 6 is the one that catches the mistake in Task 3's class comment before it reaches production.

**⛓️ Dependencies**
Tasks 3, 4, 5.

**✅ Expected Output**
All six green, 10 consecutive runs without flake. `CountDownLatch` throughout — never `Thread.sleep`.

**⏱️ Estimated Time**
2.5–3 hours.

---

## TASK 9 — Change `POST /tickets` to `202` and publish the event

**📝 Description**
Modify `TicketService.create()` from Phase 5:
```java
@Transactional
public TicketResponse create(...) {
    var ticket = /* … as before … */;
    eventRecorder.record(ticket, CREATED, …);
    outboxPublisher.publish("TICKET", ticket.getId(), TICKET_CREATED, payload);  // SAME transaction
    return TicketResponse.accepted(ticket);
}
```

Change the status to `202 Accepted`, add `analysisStatus: "PROCESSING"` and the `links.analysis` pointer.

**Remove the `slaService.start()` call** — SLA now starts inside the triage transaction (Task 28), because clocks should begin when priority is known. Leave a comment saying so; a ticket with no SLA between creation and triage is a deliberate ~15-second window, not a bug.

**Make this its own commit with a message explaining the `201 → 202` change.** The status code following the semantics is a small thing that reads well in a git history.

**⛓️ Dependencies**
Task 2, Phase 5 Task 8.

**✅ Expected Output**
`POST /tickets` returns `202` in under 150 ms with `analysisStatus: PROCESSING`. One `outbox_event` row exists, in the same transaction as the ticket. Idempotency replay still returns the identical response.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 10 — Implement `GET /tickets/{id}/analysis`

**📝 Description**
The status resource, returning the three shapes from [05 §3.3](05-API-CONTRACT.md): `PROCESSING` (with `retryAfterSeconds`), `READY` (signals, cost, latency, prompt version), `UNAVAILABLE` (reason, attempts, `manualTriageRequired: true`).

Derive the state: no `ai_analysis` row and a `PENDING`/`IN_FLIGHT` outbox event → `PROCESSING`; a row with `status = OK` → `READY`; a `DEAD` event or a row with `status = FAILED` → `UNAVAILABLE`.

**Return `200` for all three, including `UNAVAILABLE`.** The analysis *resource* exists and its state is "we tried and failed" — a successful read of a legitimate state. A `503` here would make a degraded AI feature look like a broken API, and the ticket remains fully workable throughout.

Return a stub `READY` with empty signals for now; Task 21 fills it in.

**⛓️ Dependencies**
Task 9.

**✅ Expected Output**
Immediately after creation → `PROCESSING`. After a manually inserted analysis row → `READY`. After manually deading the event → `UNAVAILABLE` with `manualTriageRequired: true`.

**⏱️ Estimated Time**
2 hours.

---

### ✅ 6A checkpoint

- [ ] `POST /tickets` returns `202`; the event and the ticket commit together
- [ ] Workers claim, process, retry with jittered backoff, and dead-letter
- [ ] A killed worker's event is reaped and redelivered
- [ ] **Peak connection usage stays ≤ 2 under 20 concurrent slow events**
- [ ] `outboxLag` health indicator and six metrics live

**You now have a fully asynchronous backend with zero AI in it.** That alone closes the largest gap on your resume.

---

# PHASE 6B — AI Safety, Governance & LLM Plumbing

## TASK 11 — Create `TenantAiPolicy` and its admin endpoints

**📝 Description**
Entity per [04 §Group 8](04-DATABASE-SCHEMA.md) — `@MapsId`, the tenant id **is** the primary key.

`GET/PUT /api/v1/admin/ai-policy` (ADMIN only), plus `AiPolicyService.check(tenantId)` returning a decision record: external model allowed, allowed providers, redaction required, budget remaining.

Cache in Redis under `policy:{tenantId}` with a 5-minute TTL, evicted on update — it is read before **every** LLM call.

Seed a policy row for every tenant in the Phase 4 seeder. A missing policy must **fail closed** (treat as AI disabled), never fall back to permissive defaults.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Policy readable and updatable. Setting `external_model_allowed = false` is reflected within 5 minutes or immediately on eviction. A tenant with no policy row is treated as AI-disabled.

**⏱️ Estimated Time**
2 hours.

---

## TASK 12 — Implement the PII redactor

**📝 Description**
`PiiRedactor.redact(tenantId, ticketId, text) → RedactionResult(redactedText, placeholderCount)`.

Detectors, applied longest-match-first:

| Type | Detection |
|---|---|
| `EMAIL` | RFC-ish regex |
| `PHONE` | `+91` and bare 10-digit Indian formats, plus generic international |
| `CARD` | 13–19 digits, **Luhn-validated** to avoid mangling order numbers |
| `GOV_ID` | Aadhaar-shaped (12 digits, spaced or not), PAN (`[A-Z]{5}[0-9]{4}[A-Z]`) |
| `IP` | IPv4 and IPv6 |
| `ORDER_REF` | Tenant-configurable prefix pattern |
| `PERSON` | Exact match against known user names in this tenant |

Replace with `«TYPE_N»`, numbered per type per document. Persist `(ticket_id, placeholder, pii_type, encrypted_value)` with AES-GCM using `PII_ENCRYPTION_KEY`.

**Redaction must be deterministic and idempotent.** The same input text must always produce the same placeholders — otherwise the content-hash embedding cache in Task 19 never hits, and you silently pay for every embedding twice. Sort detections by start offset and assign numbers in document order, never in detection order.

**⛓️ Dependencies**
Task 11.

**✅ Expected Output**
*"Hi, I'm Priya Raman, order ORD-88213 charged to 4111 1111 1111 1111, call me on +91 98765 43210"* becomes *"Hi, I'm «PERSON_1», order «ORDER_1» charged to «CARD_1», call me on «PHONE_1»"*. Redacting the same text twice yields byte-identical output. A 16-digit non-Luhn number is **not** redacted.

**⏱️ Estimated Time**
3.5 hours.

---

## TASK 13 — Implement rehydration and the redaction test suite

**📝 Description**
`PiiRedactor.rehydrate(ticketId, text) → text` — replace placeholders with decrypted originals. Called only when rendering for an authorised human, never before persisting an analysis.

Test suite:
1. Round-trip: `rehydrate(redact(x)) == x`
2. Determinism: `redact(x) == redact(x)` across separate invocations
3. Every detector, with three positive and two negative cases each
4. **No leakage:** `redact()` output is asserted to contain no substring of any original value
5. Overlapping matches — a phone number inside an order reference resolves longest-match-first
6. Rehydrating a placeholder from a different ticket returns it **unchanged** (no cross-ticket leakage)

Test 4 is the one that matters. A detector that half-matches — redacting `4111 1111 1111` but leaving `1111` — is worse than not redacting at all, because it looks like it worked.

**⛓️ Dependencies**
Task 12.

**✅ Expected Output**
All six green. Test 4 across 50 seeded ticket bodies.

**⏱️ Estimated Time**
2 hours.

---

## TASK 14 — Create `PromptVersion` and seed `triage@1`

**📝 Description**
Entity per [04 §Group 4](04-DATABASE-SCHEMA.md) — immutable, `uq_prompt_active` allows exactly one active version per name.

Write the `triage@1` prompt and its JSON output schema, seeded via `V10__seed_prompts.sql` so it is versioned with the code rather than created at runtime.

Prompt requirements: describe the eight categories; ask for **observations, not decisions**; explicitly instruct that the input may contain `«PLACEHOLDER»` tokens to be treated as opaque; forbid inferring urgency from politeness.

**The schema must not contain a `priority` field.** The moment it does, the entire architectural argument of this project collapses. Put a comment in the migration saying so — future-you will be tempted, because it would be easier.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Migration applies; `triage@1` is the single active version. `PromptVersionService.active("triage")` returns it. An attempt to UPDATE the template raises the `forbid_mutation` trigger.

**⏱️ Estimated Time**
2 hours.

---

## TASK 15 — Define `TriageSignals` and wire Spring AI structured output

**📝 Description**
```java
public record TriageSignals(
    Category category,
    Severity reportedImpact,        // SINGLE_USER | TEAM | ORG_WIDE
    boolean serviceDownClaimed,
    boolean dataLossClaimed,
    boolean paymentAffected,
    Urgency linguisticUrgency,      // named honestly: it is language, not truth
    Map<String,String> extractedEntities,
    double confidence
) {}
```

Wire `ChatClient` with `BeanOutputConverter`, temperature 0, seed pinned where supported:
```java
TriageSignals signals = chatClient.prompt()
    .user(u -> u.text(promptTemplate).param("ticket", redactedText))
    .call()
    .entity(TriageSignals.class);
```

Handle the parse failure path explicitly: on `ConversionException`, retry **once** with a repair prompt that includes the malformed output and the schema; on a second failure, throw a typed `LlmParseException` for Task 16 to escalate on.

**Never partially accept.** A `TriageSignals` with three of eight fields populated feeds a policy function that will then produce a confidently wrong priority.

**⛓️ Dependencies**
Task 14.

**✅ Expected Output**
Against a WireMock stub returning valid JSON, the record populates correctly. Against malformed JSON, one repair attempt then `LlmParseException`. Against valid JSON with an unknown enum value, it fails rather than defaulting.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 16 — Implement `ModelRouter` ⚠️ *lean path: simplify*

**📝 Description**
`ModelRouter.call(tenantId, promptVersion, input, Class<T>) → LlmResult<T>` carrying the result plus the model used, tokens, cost and latency.

Escalation ladder:
```
1. Policy check: external allowed? provider in the allow-list?
2. Cheap primary model
3. Parse failure or low confidence → strong model, once
4. Provider 5xx/timeout → fallback provider
5. external_model_allowed = false → local Ollama
6. All exhausted → LlmUnavailableException
```

**⚠️ Lean path:** implement steps 1, 2 and 6 only — one model, policy check, typed failure. Steps 3–5 are the difference between "I integrated an LLM" and "I built a routing layer", but they cost a full day and the demo does not show them. **−3 h**

Record `cost_micros` as an **integer** from a per-model rate table. Never `double` for money.

**⛓️ Dependencies**
Tasks 11, 15.

**✅ Expected Output**
Full path: a cheap-model parse failure escalates to the strong model and succeeds; a primary 5xx fails over; `external_model_allowed = false` routes to Ollama. Costs accumulate as integers.

**⏱️ Estimated Time**
3 hours *(1.5 h lean)*.

---

## TASK 17 — Add the circuit breaker and retry policy

**📝 Description**
Wrap every provider call in Resilience4j: 5 failures in 30 s → **open** for 30 s → **half-open** with one probe → closed on success. Separate breaker instance per provider.

Retry with exponential backoff and jitter **inside** the breaker, capped at 3 attempts, and **only** on 429/5xx/timeout — never on a 400, which will fail identically every time.

**Without this, a provider outage means every worker burns its full retry budget on every event**, saturating the virtual-thread pool and turning a degraded AI feature into an application-wide slowdown. The circuit breaker is what keeps an AI failure from becoming a ticketing failure — which is the architectural premise of the entire project.

Expose breaker state as a Micrometer gauge.

**⛓️ Dependencies**
Task 16.

**✅ Expected Output**
WireMock returning 503: after 5 failures the breaker opens and subsequent calls fail immediately without a network round trip. After 30 s a probe is attempted. A 400 is not retried.

**⏱️ Estimated Time**
2 hours.

---

## TASK 18 — Implement per-tenant budget enforcement

**📝 Description**
Before each call: `spend + estimatedCost > monthly_budget_micros` → throw `BudgetExhaustedException`. After: atomically increment `current_month_spend_micros` with the **actual** cost.

The worker catches it, sets the outbox event to `BUDGET_HELD` (a terminal-but-resumable state), and notifies the admin.

Nightly job resets `current_month_spend_micros` on the first of the month.

**Note the race honestly in a comment:** two concurrent calls can both pass the check and jointly overshoot the budget by up to one call's cost. Accepted — the alternative is serialising every LLM call behind a lock, and the overshoot is bounded and tiny. **Being able to name a race you deliberately accepted, with its bound, is a better answer than pretending it does not exist.** Use an atomic SQL increment (`SET spend = spend + :cost`), not read-modify-write, so the *total* stays accurate even though the *check* is racy.

**⛓️ Dependencies**
Tasks 11, 16.

**✅ Expected Output**
A tenant at budget gets `BUDGET_HELD` events and an admin notification; **ticket creation, assignment and SLA continue working normally.** Spend accumulates correctly under 10 concurrent calls.

**⏱️ Estimated Time**
2 hours.

---

## TASK 19 — Implement the embedding service with content-hash caching

**📝 Description**
`EmbeddingService.embed(tenantId, text) → float[768]`:
1. `key = sha256(redactedText)`
2. Redis `GET emb:{key}` → hit, return
3. Miss → provider call → `SETEX` with 30-day TTL → return

Store ticket embeddings on `ticket.embedding` via a native `UPDATE` — pgvector needs a custom Hibernate type and a one-column native update is less work than wiring one.

Embeddings are needed here (not only in Phase 7) because **Phase 8's incident correlation clusters on them**, and they are produced as a by-product of triage.

**Cache on the redacted text, and only on the redacted text.** Hashing raw text would put PII-derived keys in Redis, and it would also miss whenever two tickets differ only in a customer name — which is exactly the case where the embedding should be identical.

**⛓️ Dependencies**
Tasks 12, 16.

**✅ Expected Output**
First call hits the provider, second returns from cache with no network call. `ticket.embedding` is populated and queryable via `<=>`. Cache hit rate is exposed as a metric.

**⏱️ Estimated Time**
2 hours.

---

## TASK 20 — Set up WireMock fixtures for deterministic AI tests

**📝 Description**
Create `src/test/resources/wiremock/` with recorded responses: valid triage for each of the 8 categories, malformed JSON, a 429, a 503, a slow response, and an embedding response.

Build `LlmStub`, a test helper with a fluent API — `LlmStub.returnsSignals(...)`, `.returnsMalformed()`, `.fails(503)`, `.slow(Duration)` — and point the `test` profile at the WireMock base URL.

**Do this now, not later.** Retrofitting means re-recording every fixture, and until it exists your test suite is slow, flaky and costs money on every CI run. It is also what makes the eval suites in Phase 7/9 free to run.

**⛓️ Dependencies**
Tasks 15, 16.

**✅ Expected Output**
The full test suite runs offline with no API key set. A test asserting the malformed path passes deterministically over 10 runs.

**⏱️ Estimated Time**
2.5 hours.

---

### ✅ 6B checkpoint

- [ ] PII redaction round-trips, is deterministic, and leaks no substring
- [ ] A ticket can be classified into `TriageSignals` via structured output
- [ ] `external_model_allowed = false` routes locally or disables cleanly
- [ ] Budget exhaustion holds AI work without touching ticketing
- [ ] The test suite runs offline against WireMock

---

# PHASE 6C — Triage Worker & Policy Engines

## TASK 21 — Implement `TriageWorker`

**📝 Description**
The assembly point. **Structure it as exactly three phases** per Task 3's rule:

```java
public void process(OutboxEvent event) {
    // ── tx1: short read ─────────────────────────────────────────
    var ctx = txTemplate.execute(s -> loadTicketAndPolicy(event));

    // ── no transaction: 2–8 seconds of network ──────────────────
    var redacted = piiRedactor.redact(ctx.tenantId(), ctx.ticketId(), ctx.text());
    var embedding = embeddingService.embed(ctx.tenantId(), redacted.text());
    var result    = modelRouter.call(ctx.tenantId(), "triage", redacted.text(), TriageSignals.class);

    // ── tx2: short write ────────────────────────────────────────
    txTemplate.execute(s -> persistAnalysisAndDecide(ctx, result, embedding));
}
```

Wrap the whole body in `TenantContext.runAs(event.tenantId(), …)` — a worker has no request and therefore no tenant, and `@TenantId` with an unset context is the hole Phase 4 Task 6 tested for.

Persist `AiAnalysis` with signals, tokens, cost, latency, model and prompt version. Handle `LlmUnavailableException` → `status = FAILED`; `BudgetExhaustedException` → `BUDGET_HELD`.

Set `visibilityTimeout()` to 5 minutes — longer than any plausible LLM call plus retries.

**⛓️ Dependencies**
Tasks 3, 12, 16, 18, 19.

**✅ Expected Output**
Creating a ticket produces an `ai_analysis` row within ~15 s. `GET /analysis` returns `READY` with real signals. A provider failure produces `UNAVAILABLE` with the ticket still fully workable. Connection count stays ≤ 2 during the call.

**⏱️ Estimated Time**
3 hours.

---

## TASK 22 — Implement `PriorityPolicy` as a pure versioned function

**📝 Description**
**The component the whole project's argument rests on.** No Spring annotations, no repository, no I/O.

```java
public PriorityDecision evaluate(
    TriageSignals signals,        // from the model
    PlanTier planTier,            // from the system
    boolean linkedToLiveIncident,
    int reopenCount,
    PolicyVersion version);
```

Implement as an ordered rule list, each rule recording whether it matched and its effect:

| Rule | Effect |
|---|---|
| `BASE_FROM_IMPACT` | `ORG_WIDE` → P1, `TEAM` → P2, `SINGLE_USER` → P3 |
| `SERVICE_DOWN_BUMP` | `serviceDownClaimed` → +1 level |
| `DATA_LOSS_BUMP` | `dataLossClaimed` → P1 outright |
| `PAYMENT_BUMP` | `paymentAffected` → +1 |
| `PLAN_TIER_BUMP` | `ENTERPRISE` → +1; `FREE` → −1 |
| `INCIDENT_INHERIT` | linked → inherit the incident's priority |
| `REOPEN_BUMP` | `reopenCount >= 2` → +1 |
| `CLAMP` | clamp to P1…P4 |

Return the computed priority **plus the full rule trace** for the rationale endpoint.

**Version the policy as a constant string in the class** (`"v1"`), recorded on every decision. When you tune the rules in week 12, every historical decision still explains itself.

**⛓️ Dependencies**
Task 15.

**✅ Expected Output**
Compiles with zero Spring or JPA imports. Signals for an ENTERPRISE payment outage produce P1 with a five-entry rule trace.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 23 — Write the `PriorityPolicy` table-driven test

**📝 Description**
Plain JUnit, no Spring context, runs in milliseconds and makes **zero LLM calls**.

A `@CsvSource` of at least 30 rows covering: each impact level alone; each bump in isolation; bumps in combination; clamping at both ends; `FREE` tier downgrade; incident inheritance overriding everything else; `reopenCount` at 1 (no bump) and 2 (bump).

Add a property test: the output is always within P1–P4 for **any** combination of inputs.

**This test is the answer to "so the LLM decides the priority?"** — no, and here are thirty cases proving the decision is a pure function you can run without a model. It costs 90 minutes and is worth more than any amount of prompt tuning.

**⛓️ Dependencies**
Task 22.

**✅ Expected Output**
30+ cases green in under 100 ms. The property test finds no out-of-range output over 1,000 random inputs.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 24 — Implement `RoutingPolicy` — team selection

**📝 Description**
`selectTeam(tenantId, category) → Team`:
1. Teams whose `skills` array overlaps the category — `skills && ARRAY[:category]`, using the GIN index
2. Multiple matches → most specific (fewest skills)
3. No match → the tenant's `is_default` team
4. No default → `UNROUTED`, left for manual assignment

Deterministic and unit-testable with no model involvement. **The model proposed a category; the routing decision is business configuration**, and a tenant changing which team owns billing must not require a prompt change.

**⛓️ Dependencies**
Phase 4's `Team`.

**✅ Expected Output**
`PAYMENT` routes to the Payments team. An unknown category routes to default. A tenant with no default leaves the ticket unrouted rather than erroring.

**⏱️ Estimated Time**
2 hours.

---

## TASK 25 — Implement `claimLeastLoadedAgent` with `SKIP LOCKED`

**📝 Description**
```sql
SELECT * FROM agent_profile
 WHERE tenant_id = :t AND is_available = TRUE
   AND open_count < max_concurrent
   AND user_id IN (SELECT id FROM app_user WHERE team_id = :team AND deleted_at IS NULL)
   AND (shift_start IS NULL OR :localTime BETWEEN shift_start AND shift_end)
 ORDER BY open_count ASC, id ASC
 LIMIT 1
 FOR UPDATE SKIP LOCKED
```
Then increment `open_count` and set `assignee_id` in the same transaction.

**`SKIP LOCKED` is what makes this correct under a burst.** A plain `ORDER BY open_count LIMIT 1 FOR UPDATE` makes every concurrent router block on the *same* agent row, then serialise and pile onto that one agent. `SKIP LOCKED` sends each concurrent router to a different agent — which is precisely the behaviour you want, and precisely the race Hyperlocal's version has.

The `ORDER BY … , id ASC` tiebreak keeps it deterministic when loads are equal, which is what makes Task 32's test assertable.

No agent available → leave unassigned in the team queue. Never fail the ticket.

**⛓️ Dependencies**
Task 24.

**✅ Expected Output**
Single-threaded: picks the least loaded. The full distribution test is Task 32.

**⏱️ Estimated Time**
2 hours.

---

## TASK 26 — Persist `PriorityDecision` and implement `/priority-rationale`

**📝 Description**
Persist the decision with `input_signals` (a JSONB snapshot of **every** input, model and system alike), `policy_version`, `computed_priority` and the `rationale` rule trace.

Implement `GET /tickets/{id}/priority-rationale` per [05 §3.3](05-API-CONTRACT.md), with `inputs` split into `fromModel` and `fromSystem`.

**That split is the point of the endpoint.** When an agent overrides a priority, it makes it possible to tell whether **the model misread the ticket** or **the policy is wrong** — two different bugs with two different fixes. A design where the LLM returns `"priority": "P2"` makes that distinction permanently unrecoverable.

Generate `humanReadable` from the matched rules — it is what the agent console shows in the `ⓘ` popover.

**⛓️ Dependencies**
Tasks 21, 22.

**✅ Expected Output**
The endpoint returns the split inputs and the full rule list with matched flags. The rationale reconstructs the priority exactly.

**⏱️ Estimated Time**
2 hours.

---

## TASK 27 — Implement `POST /tickets/{id}/priority-override`

**📝 Description**
`AGENT`+ only. Requires a `reason` of 10–500 characters. Writes a `priority_override` row, updates the ticket, records an audit event, and **recomputes the SLA target for the new priority** — a P3→P1 override must shorten the deadline, which means resolving the new policy and recomputing `next_deadline_at` on the running record.

**The reason is mandatory and minimum-length because this row is training data.** *"wrong"* is not a label; *"enterprise customer, contract says P1 for any payment issue"* is. The helper text in [06 §8](06-UI-UX-DESIGN.md) says so to the agent.

**⛓️ Dependencies**
Tasks 26, and Phase 5's SLA service.

**✅ Expected Output**
Override changes priority, records the row with its reason, and shortens the SLA deadline. A reason under 10 characters → `400`. Override rate is queryable as a metric.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 28 — Wire SLA start into the triage transaction

**📝 Description**
Move `slaService.start(ticket)` from ticket creation into `persistAnalysisAndDecide` (Task 21), after the priority is computed — so the clock starts against the **correct** target rather than a default.

Handle the failure path: if triage never succeeds, the ticket has no SLA at all. **Add a fallback sweeper** — `@Scheduled` every 5 minutes, find tickets older than 10 minutes with no `sla_record`, apply a default P3 policy, and start the clock.

Without the sweeper, a provider outage means every ticket raised during it is permanently untracked — a silent failure that only surfaces when a customer complains. **This is the kind of gap that only appears when you move a call between transactions**, and it is worth calling out as a thing you found.

**⛓️ Dependencies**
Tasks 21, 22, Phase 5 Task 24.

**✅ Expected Output**
A triaged ticket has SLA records matching its computed priority. A ticket whose triage fails gets default P3 clocks within 15 minutes, with an audit event recording the fallback.

**⏱️ Estimated Time**
2 hours.

---

## TASK 29 — Implement `POST /tickets/{id}/retriage`

**📝 Description**
`AGENT`+. Publishes a fresh `TICKET_CREATED` event with `attempt = n+1`. Used after a provider outage or a prompt fix.

`uq_analysis_ticket_prompt` includes `attempt`, so a retry legitimately creates a second row rather than colliding — which is also why the analysis endpoint must return the **latest** row, not the first.

`409 TRIAGE_IN_PROGRESS` if an event is already pending. Rate limit 5/hour per ticket.

**⛓️ Dependencies**
Tasks 9, 21.

**✅ Expected Output**
Retriaging a `FAILED` ticket produces a new analysis; `GET /analysis` returns the latest. Retriaging while one is pending → `409`.

**⏱️ Estimated Time**
1.5 hours.

---

# PHASE 6D — Tests & Close

## TASK 30 — Duplicate-claim and idempotency test

**📝 Description**
1. Two `WorkerRuntime` instances on a latch against one `TICKET_CREATED` event → **exactly one** `ai_analysis` row and **exactly one** WireMock request. Verify the request count via WireMock's `verify(1, postRequestedFor(...))` — asserting on the row count alone would not catch paying twice for a call that then failed to insert.
2. Force a redelivery after the LLM call but before the write; assert the embedding cache prevents a second provider call.
3. Assert `uq_analysis_ticket_prompt` rejects a genuine duplicate.

**⛓️ Dependencies**
Tasks 8, 21.

**✅ Expected Output**
All three green over 10 runs.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 31 — Worker-crash redelivery test

**📝 Description**
A `TriageWorker` variant that throws after the LLM call but before `tx2`. Assert: the event returns to `PENDING` via the reaper, a second worker completes it, exactly one analysis row exists, and **WireMock recorded only one call** because the embedding and content-hash caches absorbed the retry.

Add: crash *during* `tx2` → the transaction rolls back, no partial state (no analysis without a priority decision, no priority decision without SLA records).

**The second assertion is the valuable one.** At-least-once delivery is only safe because every stage is idempotent; this test is what proves the claim rather than asserting it.

**⛓️ Dependencies**
Tasks 5, 21.

**✅ Expected Output**
Both green. No partial writes under any crash point.

**⏱️ Estimated Time**
2 hours.

---

## TASK 32 — Load-distribution test *(moved from Phase 5C)*

**📝 Description**
50 tickets created concurrently, 5 available agents on one team, auto-routing enabled. Assert:
- Every ticket is assigned
- **`max(open_count) − min(open_count) ≤ 1`**
- `Σ open_count == 50`
- No agent exceeds `max_concurrent`

Then the negative control: replace `SKIP LOCKED` with a plain `FOR UPDATE`, re-run, and **watch the distribution collapse onto one or two agents.** Keep that as a `@Disabled` test with a comment.

**The disabled negative control is the demo.** It shows the race is real rather than theoretical, and it is the concrete form of *"I found this bug in my own earlier project."*

**⛓️ Dependencies**
Task 25.

**✅ Expected Output**
Skew ≤ 1 over 10 runs. The disabled variant demonstrably fails when enabled.

**⏱️ Estimated Time**
2 hours.

---

## TASK 33 — Failure-mode tests

**📝 Description**
1. **Provider down** — WireMock 503 on every call. Assert: the event goes `DEAD` after retries; `GET /analysis` → `UNAVAILABLE`; **ticket creation, assignment, replies, status changes and SLA all continue working.**
2. **Circuit breaker** — 5 failures open it; the 6th call returns immediately with no network request.
3. **Budget exhausted** — set spend above budget; assert `BUDGET_HELD`, an admin notification, and that ticketing is untouched.
4. **Malformed response** — one repair attempt, then escalation, then `FAILED`. Never a partially-populated `TriageSignals`.
5. **AI disabled by policy** — `external_model_allowed = false` with no local model; assert clean `UNAVAILABLE`, not a 500.

Test 1 is the architectural claim of the entire project, made executable: **AI failure degrades the product; it never stops it.**

**⛓️ Dependencies**
Tasks 17, 18, 21.

**✅ Expected Output**
All five green. In test 1, assert a full ticket lifecycle completes while the provider is down.

**⏱️ Estimated Time**
2 hours.

---

## TASK 34 — Classification eval harness (minimal) ⚠️ *lean path: keep this*

**📝 Description**
Even on the lean path — **keep this.** It is the answer to *"how do you know your AI works?"*, and without it the AI half of the project has no evidence behind it.

Build the smallest useful version:
1. Seed 60 `eval_case` rows (suite `CLASSIFICATION`) from the Phase 1 corpus with hand-labelled category and team
2. `EvalRunner` executes the suite against the active prompt version, computing per-category accuracy and macro-F1
3. Persist `eval_run` and `eval_result`
4. A JUnit test runs the suite against WireMock fixtures and **fails the build** if accuracy drops below a committed baseline
5. `GET /admin/eval/runs` returning accuracy over prompt versions

**Commit the baseline as a file**, and when you legitimately improve a prompt, update it in the same commit so the diff shows the gain.

**⛓️ Dependencies**
Tasks 20, 21.

**✅ Expected Output**
`mvn verify` runs the suite and reports accuracy. **Deliberately degrade the prompt, watch CI go red, revert.** A gate you have never seen fail is a gate you do not know works.

**⏱️ Estimated Time**
3 hours.

---

## TASK 35 — Clean up, verify and tag Phase 6

**📝 Description**
Remove stubs and TODOs. Full clean verify from empty. Update the README with an "Async pipeline & AI triage" section covering: the dual-write problem and why the outbox solves it; why there is no Kafka ([03 §9](03-SYSTEM-ARCHITECTURE.md), verbatim); the three-phase worker structure and the connection-pool reasoning; signals-versus-decisions; and PII redaction.

Extend the Postman collection. Record real numbers: p95 triage latency, cost per ticket, cache hit rate, load skew, classification accuracy.

Commit and tag `phase-6-complete`.

**⛓️ Dependencies**
Task 34.

**✅ Expected Output**
Clean verify from empty. CI green. Five benchmark placeholders replaced with measurements. Tag pushed.

**⏱️ Estimated Time**
1.5 hours.

---

## 📅 Suggested Daily Schedule

### 6A — Async pipeline (6 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 3.5 | T1 Outbox entity + claim (2.0) · T2 Publisher (1.5) |
| 2 | 3.5 | T3 WorkerRuntime (3.5) |
| 3 | 3.5 | T4 Retry/backoff/DLQ (2.0) · T5 Reaper (1.5) |
| 4 | 3.0 | T6 Metrics + health (1.5) · T7 DLQ endpoints (1.5) ⚠️ *skip on lean* |
| 5 | 3.0 | T8 Worker test suite (3.0) |
| 6 | 3.5 | T9 POST /tickets → 202 (1.5) · T10 GET /analysis (2.0) |

**→ 6A checkpoint. Fully async backend, zero AI.**

### 6B — AI safety & LLM plumbing (7 days)

| Day | Hours | Tasks |
|---|---|---|
| 7 | 3.5 | T11 TenantAiPolicy (2.0) · start T12 |
| 8 | 3.5 | T12 PII redactor (3.5) |
| 9 | 4.0 | T13 Rehydration + tests (2.0) · T14 PromptVersion + seed (2.0) |
| 10 | 2.5 | T15 TriageSignals + structured output (2.5) |
| 11 | 3.0 | T16 ModelRouter (3.0) ⚠️ *1.5 h on lean* |
| 12 | 4.0 | T17 Circuit breaker (2.0) · T18 Budget (2.0) |
| 13 | 4.5 | T19 Embedding cache (2.0) · T20 WireMock fixtures (2.5) |

**→ 6B checkpoint.**

### 6C — Triage & policies (5 days)

| Day | Hours | Tasks |
|---|---|---|
| 14 | 3.0 | T21 TriageWorker (3.0) |
| 15 | 4.0 | T22 PriorityPolicy (2.5) · T23 Policy tests (1.5) |
| 16 | 4.0 | T24 Team routing (2.0) · T25 claimLeastLoadedAgent (2.0) |
| 17 | 3.5 | T26 PriorityDecision + rationale (2.0) · T27 Override (1.5) |
| 18 | 3.5 | T28 SLA wiring + fallback sweeper (2.0) · T29 Retriage (1.5) |

### 6D — Tests & close (3 days)

| Day | Hours | Tasks |
|---|---|---|
| 19 | 3.5 | T30 Duplicate claim (1.5) · T31 Crash redelivery (2.0) |
| 20 | 4.0 | T32 Load distribution (2.0) · T33 Failure modes (2.0) |
| 21 | 4.5 | T34 Eval harness (3.0) · T35 Close (1.5) |

**Day 22 — Buffer.** It will be used — most likely by T3 (the connection-discipline structure is easy to get subtly wrong), T12 (regex edge cases multiply), or T21 (assembling six components that have only ever been tested alone).

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 35   (6A: 10 · 6B: 10 · 6C: 9 · 6D: 6)
Total Estimate : 69–75 hours full  ·  ~60 hours lean
Suggested Days : 21 working days + 1 buffer  (~18 lean)
                 (NOT the 6–8 days in doc 07 — this phase is three
                  subsystems, not one)

Hardest Task   : Task 21 — TriageWorker.
                 Not individually complex, but it is the assembly point
                 for six components that have only ever been tested in
                 isolation: redaction, embedding, model routing, budget,
                 persistence and the policies. It is also where the
                 three-phase transaction structure either holds or
                 quietly fails — and the failure mode is not an
                 exception, it is connection-pool exhaustion under load,
                 which you will only see if Task 8 test 6 exists.

Runner-up      : Task 12 — PII redaction.
                 The determinism requirement is the non-obvious part.
                 Get it wrong and nothing breaks visibly; you just
                 silently pay for every embedding twice.

Most Skipped   : Task 34 — the classification eval harness.
                 It is last, it is on the cut list in spirit, and it
                 produces no user-visible feature. It is also the ONLY
                 answer to "how do you know your AI works?" — which is
                 the question that decides whether the AI half of this
                 project counts for anything in an interview. Keep it
                 even on the lean path.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 6 exit checklist

- [ ] `docker compose down -v && docker compose up -d && mvn clean verify` passes from empty
- [ ] `POST /tickets` returns `202` in under 150 ms; analysis appears within ~15 s
- [ ] **Peak connection usage ≤ 2 under 20 concurrent triages**
- [ ] A killed worker's event is reaped and redelivered with **no second LLM charge**
- [ ] Priority is computed by a pure function, tested with 30+ cases and zero model calls
- [ ] `/priority-rationale` splits model inputs from system inputs
- [ ] 50 tickets across 5 agents → load skew ≤ 1
- [ ] **Provider down → a full ticket lifecycle still completes**
- [ ] Budget exhaustion holds AI work without touching ticketing
- [ ] PII is redacted deterministically and leaks no substring
- [ ] Classification accuracy is measured and gated in CI, **and you have watched the gate fail**
- [ ] Every new endpoint is in the Phase 4 cross-tenant `@MethodSource`

---

## Next Steps

> ✅ **Task list ready for Phase 6 — Async Pipeline & AI Triage!**
>
> **Before you start, make the scope decision at the top of this document.** Three phases have now each come in at roughly double their estimate; full scope is 18–21 weeks. Decide between Path A, B and C *now*, while it is a planning choice, rather than in week 12 when it becomes a salvage operation. **Path C is the recommendation.**
>
> **Three things the ordering depends on:**
> 1. **Complete 6A before touching any AI.** A fully async backend with zero AI is a real, demoable stopping point and it closes the biggest gap on your resume by itself.
> 2. **Build the WireMock fixtures (Task 20) before the triage worker**, not after. Retrofitting means re-recording everything, and until they exist every test run costs money.
> 3. **Keep Task 34 even on the lean path.** Everything else in 6B/6C is a feature; the eval harness is the evidence.
>
> **If you take Path C, Phase 7 does not happen.** Go from here to **Phase 8 — Incident Correlation**, which needs only ticket embeddings (Task 19) and the worker runtime (Task 3) — both delivered here. Run `task-breakdown` on it next, and expect its 5–6 day estimate to land nearer 10–12.
