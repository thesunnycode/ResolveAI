# 10 — Task Breakdown: Phase 5

> Skill: `task-breakdown` · Run once per phase · **Re-run at the start of Phase 6**

**Phase:** Phase 5 — Core MVP: Ticketing & the SLA Engine
**Goal:** The complete ticket lifecycle with an enforced state machine and audit trail, plus the entire SLA engine — business-hours arithmetic, pausable append-only clocks, the deadline poller, and an escalation ladder that fires exactly once per rung.
**Tech stack:** Java 21 · Spring Boot 3.3.x · Spring Data JPA / Hibernate 6 · PostgreSQL 16 · Redis · MinIO · jqwik · Testcontainers · JUnit 5

**No AI in this phase. That is deliberate** — see the ordering rationale in [07](07-DEV-PHASES.md).

---

## ⚠️ Read this before planning your calendar

Two things came out of breaking this phase into tasks.

### 1. The estimate was low by a factor of two

[07](07-DEV-PHASES.md) put Phase 5 at **8–11 days**. The real figure is **62–70 hours ≈ 18–20 working days** at 3–4 focused hours a day.

This is the second consecutive phase to roughly double (Phase 4 went 4–5 → 10–11). The pattern is consistent and worth naming: **the phase-level estimates in [07](07-DEV-PHASES.md) counted the features, not the tests, the DTOs, the error paths, or the plumbing between them.** Treat every remaining phase estimate as a lower bound until it has been broken down the same way.

**Revised project total: 71–85 working days ≈ 15–18 calendar weeks** at 3–4 h/day.

### 2. Split the phase in two

A 19-day phase with no intermediate checkpoint is unmanageable, and [07](07-DEV-PHASES.md) already describes it as two halves internally ("days 1–4 ticketing, days 5–9 SLA"). Make that formal:

| | Sub-phase | Tasks | Effort | Checkpoint |
|---|---|---|---|---|
| **5A** | Ticketing | 1–17 | 33–37 h ≈ 10 days | A fully working ticket lifecycle you can demo, with zero SLA |
| **5B** | SLA Engine | 18–31 | 22–25 h ≈ 7 days | Clocks, poller, escalation |
| **5C** | Concurrency tests & close | 32–35 | 7–8 h ≈ 2 days | The tests you demo in interviews |

5A ends at a genuinely shippable state. If something goes wrong in your semester, that is a real stopping point rather than a half-built one.

### And the conversation you should have with yourself now

At 15–18 weeks this is no longer an eight-week project, and pretending otherwise will hurt you in week 10. Three honest options:

- **Accept it.** If you are targeting placements in Feb–Mar 2027, finishing in January is fine.
- **Cut Phase 7 entirely** (knowledge base, retrieval, drafting): **−8 days**, and it is the most common thing in everyone else's portfolio anyway. Also cut attachments (Task 17, −1 day) and the customer portal. **→ ~12 weeks.**
- **Raise the daily rate to 6 hours** on weekends and holidays. → ~10 weeks.

The recommendation is the second one. It preserves all three signature mechanisms and cuts only the part a reviewer has seen before.

---

# PHASE 5A — Ticketing

## TASK 1 — Create the four ticketing entity classes

**📝 Description**
Create `Ticket`, `TicketMessage`, `TicketEvent`, `Attachment` in `com.resolveai.ticketing.domain`, mapping to [04 §Group 2](04-DATABASE-SCHEMA.md).

Carry forward the Phase 4 conventions: `@TenantId` on every `tenantId` field, `@Enumerated(STRING)`, all `@ManyToOne` LAZY, `@Version` on `Ticket`.

**Three columns need special handling:**
- **`search_tsv`** — maintained by the `trg_ticket_tsv` trigger. **Do not map it.** Hibernate would try to write it and fight the trigger. It is only ever read from native search queries. (`ddl-auto: validate` does not require every column to be mapped, only that mapped ones exist.)
- **`embedding`** — `vector(768)`, set in Phase 6. **Leave it unmapped for now.** pgvector needs a custom Hibernate type and there is no reason to wire it before anything writes to it.
- **`first_responded_at`** — a plain nullable field, but it is the column the SLA race in Task 33 hinges on. Give it a comment saying so.

Create `TicketStatus`, `Priority`, `Visibility` and `TicketEventType` enums. `TicketEvent` gets **no setters** — it is append-only and the `forbid_mutation` trigger will reject an UPDATE anyway; matching that in Java means the compiler catches it first.

**⛓️ Dependencies**
None — Phase 4 delivered the tenant filter and a validating context.

**✅ Expected Output**
Four entities and four enums compile. The application starts and `ddl-auto: validate` passes against the Flyway schema.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 2 — Add the `tenant_sequence` migration and build the reference generator

**📝 Description**
**A gap in [04](04-DATABASE-SCHEMA.md) that you will hit immediately:** `ticket.reference` is `NOT NULL` with `UNIQUE (tenant_id, reference)`, but the schema defines no way to generate it. Fix it here.

Write `V8__tenant_sequence.sql`:
```sql
CREATE TABLE tenant_sequence (
    tenant_id   BIGINT      NOT NULL,
    entity_type VARCHAR(20) NOT NULL,     -- 'TICKET' | 'INCIDENT'
    next_value  BIGINT      NOT NULL DEFAULT 1000,
    PRIMARY KEY (tenant_id, entity_type),
    CONSTRAINT fk_tenseq_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);
```

Implement `ReferenceGenerator.next(tenantId, EntityType) → "TKT-10428"` using `SELECT … FOR UPDATE` on the counter row, increment, return. Runs inside the caller's transaction.

**Why not just use the `BIGSERIAL` id with a prefix:** `TKT-88213` tells every customer of every tenant roughly how many tickets exist system-wide. A per-tenant counter leaks nothing.

**The trade-off to write down:** the row lock **serialises ticket creation per tenant**. At the peak of ~1 write/second from [03 §2](03-SYSTEM-ARCHITECTURE.md) that is irrelevant. If it ever were not, the fix is hi/lo allocation — claim 100 values at a time and hand them out from memory, accepting gaps in the sequence. Being able to name that escape hatch is the point of choosing this design.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Migration applies. A test calling `next()` 5 times for one tenant returns `TKT-1000` … `TKT-1004`. A second tenant starts at `TKT-1000` independently. A concurrency test with 10 threads produces 10 distinct references and no gaps.

**⏱️ Estimated Time**
2 hours.

---

## TASK 3 — Create the ticketing repositories

**📝 Description**
Create `TicketRepository`, `TicketMessageRepository`, `TicketEventRepository`, `AttachmentRepository`.

Derived finders for the simple cases. Two need `@Query`:
- The cursor-paginated queue query — leave it as a stub returning `findAll()` for now; Task 10 fills it in
- `TicketMessageRepository.existsByTicketIdAndIsFirstResponseTrue(Long)`

Add `@Lock(PESSIMISTIC_WRITE)` on a `findByIdForUpdate(Long)` on `TicketRepository` — Task 15 and Phase 6's routing both need it.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
Four interfaces compile; the context starts (Spring Data validates derived query names at boot).

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 4 — Implement `TicketStateMachine` as a pure component

**📝 Description**
Create `com.resolveai.ticketing.domain.TicketStateMachine` — **no Spring annotations, no repository, no I/O.** A pure holder of the transition table from [05 §3.2](05-API-CONTRACT.md).

API:
```java
Set<TicketStatus> allowedFrom(TicketStatus current);
boolean canTransition(TicketStatus from, TicketStatus to);
SlaEffect sideEffectOf(TicketStatus from, TicketStatus to);  // PAUSE | RESUME | STOP | NONE
```

Implement the table as an `EnumMap<TicketStatus, EnumSet<TicketStatus>>` built once in a static initialiser.

**The `sideEffectOf` method is the part that matters.** Entering `WAITING_ON_CUSTOMER` or `PENDING_THIRD_PARTY` pauses the resolution clock; leaving them resumes it; entering `RESOLVED` stops it. Encoding that here — rather than scattering `if (status == WAITING_ON_CUSTOMER)` across the service layer — is what keeps Task 25 honest and means a new status added later cannot silently forget its clock behaviour.

**⛓️ Dependencies**
Task 1 (needs the enum).

**✅ Expected Output**
Compiles. `allowedFrom(CLOSED)` returns an empty set. `canTransition(CLOSED, IN_PROGRESS)` is false.

**⏱️ Estimated Time**
2 hours.

---

## TASK 5 — Write the state machine table-driven test

**📝 Description**
Create `TicketStateMachineTest` — a plain JUnit test, no Spring context, so it runs in milliseconds.

Write a `@ParameterizedTest` over **every** `(from, to)` pair in the full cross product of `TicketStatus` — 64 combinations — with the expected boolean supplied from a `@CsvSource` or a hand-written `@MethodSource`. Every legal transition from [05 §3.2](05-API-CONTRACT.md) is `true`; **every other pair is explicitly `false`.**

Add assertions that `sideEffectOf` returns `PAUSE` for exactly the two entries into a waiting state and `RESUME` for exactly the exits.

**Enumerating the whole cross product rather than only the legal transitions is the point.** It means adding a status to the enum breaks the test until you have consciously decided what it may transition to — which is exactly the moment you want to be forced to think about it.

**⛓️ Dependencies**
Task 4.

**✅ Expected Output**
64 parameterised cases green in under 200ms.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 6 — Implement the `TicketEventRecorder`

**📝 Description**
Create `TicketEventRecorder` in `ticketing.service` with one method:
```java
void record(Ticket ticket, TicketEventType type, String from, String to, Map<String,Object> payload);
```

It resolves the actor from the security context — **`null` when there is no authentication**, which is how a worker-initiated event is distinguished from a human one — and inserts a `TicketEvent`.

**It must be called inside the caller's transaction, never in a new one.** If the business change rolls back, the audit entry must roll back with it. Annotate it `@Transactional(propagation = MANDATORY)` so a call from outside a transaction fails loudly at development time rather than silently writing an orphaned audit row in production.

**⛓️ Dependencies**
Tasks 1, 3.

**✅ Expected Output**
A test asserts: calling inside a transaction that then rolls back leaves zero rows; calling outside any transaction throws; an authenticated call records the actor and an unauthenticated one records `null`.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 7 — Implement the `Idempotency-Key` interceptor

**📝 Description**
Create `@Idempotent` (a marker annotation) and `IdempotencyInterceptor` in `com.resolveai.platform.idempotency`, implementing the contract from [05 §4](05-API-CONTRACT.md).

Flow on a request to an `@Idempotent` handler:
1. Read `Idempotency-Key`; missing or not matching `[A-Za-z0-9_-]{16,128}` → `400 IDEMPOTENCY_KEY_REQUIRED`
2. Compute `requestHash = SHA-256(canonicalised body)`
3. Look up Redis `idem:{tenantId}:{endpoint}:{key}`
   - **Hit with a matching hash** → replay the stored status and body verbatim, do not invoke the handler
   - **Hit with a different hash** → `422 IDEMPOTENCY_KEY_CONFLICT`
   - Miss → proceed, then store the response with a 24h TTL
4. **Redis unavailable → `503 IDEMPOTENCY_UNAVAILABLE`**

Also write through to the `idempotency_record` table as the durable backstop for a cold Redis.

**Step 4 is the decision worth defending.** Idempotency **fails closed** while rate limiting fails open — because the cost of a missed rate limit is a bit of extra load, and the cost of a missed idempotency check is a duplicate ticket or a customer messaged twice. [03 §4.4](03-SYSTEM-ARCHITECTURE.md) has the per-use-case table; make the code match it.

**⛓️ Dependencies**
None beyond Phase 3's Redis config.

**✅ Expected Output**
Tests: same key + same body twice → one handler invocation, two identical responses. Same key + different body → `422`. Missing key → `400`. Redis down (stop the container mid-test) → `503`.

**⏱️ Estimated Time**
2.5–3 hours.

---

## TASK 8 — Implement `POST /api/v1/tickets`

**📝 Description**
Create `TicketController` and `TicketService.create()`.

`CreateTicketRequest` with Bean Validation per [05 §3.2](05-API-CONTRACT.md): subject 1–200, body 1–20,000, `attachmentIds` max 5, optional `onBehalfOf` rejected with `403` for a `CUSTOMER`.

In one transaction: generate the reference (Task 2), insert the ticket with `status = OPEN` and `priority = UNTRIAGED`, record a `CREATED` event (Task 6).

**Return `201 Created` in Phase 5, not `202`.** There is no outbox and no async triage yet, so the representation returned *is* final — `202` would be a lie. **Phase 6 changes this to `202` when triage becomes asynchronous**, and that diff is a good thing to have in your git history: it shows the status code following the semantics rather than being chosen once and defended forever.

Apply `@Idempotent`.

**Remember the 20,000-character cap is a security control**, not a style rule — an uncapped body becomes a token-cost denial of service in Phase 6.

**⛓️ Dependencies**
Tasks 2, 3, 6, 7.

**✅ Expected Output**
Postman: valid POST → `201` with a `TKT-` reference. Over-length body → `400` with a populated `errors[]`. A `CUSTOMER` sending `onBehalfOf` → `403`. Replaying the same `Idempotency-Key` → the identical response and still exactly one row in the database.

**⏱️ Estimated Time**
2 hours.

---

## TASK 9 — Implement cursor pagination utilities

**📝 Description**
Create `com.resolveai.common.pagination`: a `Cursor` record of `(OffsetDateTime createdAt, Long id)`, base64url encode/decode of its JSON form, and a `CursorPage<T>` response wrapper matching [05 §4](05-API-CONTRACT.md).

Write the keyset predicate generator: for `ORDER BY created_at DESC, id DESC`, the next page is
```sql
WHERE (created_at, id) < (:cursorCreatedAt, :cursorId)
```
using PostgreSQL's row-value comparison, which is index-friendly against `idx_ticket_queue`.

Decoding a malformed cursor → `400 INVALID_CURSOR`, never a `500`.

**Include `id` in the tuple, not just `created_at`.** Two tickets created in the same millisecond — which happens constantly under a burst — would otherwise make the boundary ambiguous and silently skip or duplicate one.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Unit tests: a round-tripped cursor is identical; a tampered cursor throws the typed exception; paging through 100 rows in pages of 25 yields exactly 100 distinct rows with no repeats — **including when rows are deleted between pages**, which is the whole reason for not using `OFFSET`.

**⏱️ Estimated Time**
2 hours.

---

## TASK 10 — Implement `GET /api/v1/tickets` with role scoping and filters

**📝 Description**
Implement the list endpoint from [05 §3.2](05-API-CONTRACT.md) with all query parameters: `cursor`, `size` (clamped 1–100), repeatable `status` and `priority`, `teamId`, `assigneeId` (accepting the literal `me`), `incidentId`, `slaState`, `q`, `createdFrom`/`createdTo`, `sort`, `order`.

**Role scoping is applied server-side and is not overridable:**

| Role | Visible set |
|---|---|
| `CUSTOMER` | `requester_id = me` only |
| `AGENT` | assigned to me **OR** in my team's queue |
| `TEAM_LEAD` | all tickets in my team |
| `ADMIN` | all tickets in the tenant |

Build it as a native query or a Criteria specification. If native, **it must carry an explicit `WHERE tenant_id = :tenantId`** — `@TenantId` does not apply to native SQL, and this is the first place in the project where that gap bites. Flag it in a comment.

Full-text search on `q` uses `search_tsv @@ plainto_tsquery('english', :q)` — **`plainto_tsquery`, never string concatenation.**

**⛓️ Dependencies**
Tasks 3, 9.

**✅ Expected Output**
Postman with four different role tokens returns four correctly-scoped result sets. A `CUSTOMER` passing `teamId` gets `403`. `size=1000` is clamped to 100, not rejected. Search finds a ticket by a word in its body. **Add a line to the Phase 4 cross-tenant `@MethodSource` for this endpoint.**

**⏱️ Estimated Time**
2.5–3 hours.

---

## TASK 11 — Implement `GET /api/v1/tickets/{id}` with role-specific DTOs

**📝 Description**
Implement the detail endpoint with the `include` parameter (`timeline`, `analysis`, `sla`, `incident`).

**Create two separate response types, not one with conditional nulls:**
- `TicketDetailResponse` — for `AGENT`+
- `TicketCustomerResponse` — for `CUSTOMER`: public messages only, no `priorityRationale`, no `analysisStatus`, no `latestDraftId`, no internal SLA detail

Select the type in the controller based on the principal's role.

**Why two types rather than `if (role != CUSTOMER) dto.setInternalNotes(null)`:** the conditional version is one forgotten branch away from leaking an internal note to a customer. Separate types make that leak a compile error. This is the single highest-consequence field-visibility decision in the API.

Return `404 TICKET_NOT_FOUND` — **not `403`** — when the ticket belongs to another tenant or another customer. A `403` confirms the resource exists.

Set the `ETag` response header from `version`.

**⛓️ Dependencies**
Tasks 3, 10.

**✅ Expected Output**
An agent's response contains internal messages; the same ticket fetched by its requester does not, and the field is **absent from the JSON**, not null. A customer fetching another customer's ticket gets `404`. The `ETag` header is present.

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 12 — Implement `ETag` / `If-Match` optimistic concurrency

**📝 Description**
Create `@RequiresIfMatch` and an interceptor that, on annotated handlers, requires the `If-Match` header, parses `W/"7"` to an integer, and compares it to the entity's current `version` before the handler runs. Mismatch → `409 VERSION_CONFLICT` via the Phase 3 `@RestControllerAdvice`.

Also map Hibernate's `OptimisticLockingFailureException` to the same `409` — belt and braces, since the pre-check is advisory and the database version check is authoritative.

Apply to every ticket mutation: `PATCH`, `/assign`, `/status`, `/resolve`, `/reopen`.

**Say what this buys you:** two agents with the same ticket open, one edits, the second's edit is rejected with a clear code instead of silently overwriting the first. The interceptor gives a fast, cheap failure; `@Version` is the real guarantee.

**⛓️ Dependencies**
Task 11.

**✅ Expected Output**
A test: GET a ticket, PATCH it with the returned `ETag` → `200`. PATCH again with the **stale** `ETag` → `409 VERSION_CONFLICT`. PATCH with no `If-Match` → `428 Precondition Required` or `400`, whichever you choose — **pick one and document it**.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 13 — Implement `PATCH /api/v1/tickets/{id}`

**📝 Description**
Partial update of **subject, category and team only**. Explicitly not status, not priority, not assignee — each of those has a dedicated endpoint with its own authorization and side effects ([05 §5 Deviation 1](05-API-CONTRACT.md)).

Use a DTO of `Optional<T>` fields, or a JSON-Merge-Patch style map, so "absent" and "explicitly null" are distinguishable. Record a `STATUS_CHANGED`-style event per changed field.

`AGENT`+ only.

**Never bind a JPA entity to the request body.** A `PATCH` handler taking a `Ticket` is the textbook mass-assignment vulnerability — it would let a customer set `priority`, `assigneeId` or `tenantId`.

**⛓️ Dependencies**
Tasks 6, 12.

**✅ Expected Output**
Patching only the subject leaves the category unchanged. Patching a field the DTO does not expose is silently ignored, not applied. Each change produces an audit event.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 14 — Implement `POST /api/v1/tickets/{id}/messages`

**📝 Description**
Implement the contract from [05 §3.2](05-API-CONTRACT.md). `visibility = INTERNAL` → `403` for a `CUSTOMER`. Max 5 attachments. `fromDraftId` validated in Phase 7; accept and store it for now.

**The critical part — the first-response write, in one transaction:**
```java
@Transactional
public MessageResponse addMessage(...) {
    boolean isFirst = isPublic
        && !author.getId().equals(ticket.getRequesterId())
        && ticket.getFirstRespondedAt() == null;

    // insert message with is_first_response = isFirst
    if (isFirst) {
        ticket.setFirstRespondedAt(now);           // from Postgres NOW(), not the JVM clock
        slaService.markFirstResponseMet(ticket);   // stubbed until Task 27
    }
    eventRecorder.record(ticket, MESSAGE_ADDED, ...);
}
```

The `slaService` call is a no-op stub until Task 27 wires it in. **Leave the call site here now** so that wiring SLA later is one method body rather than a hunt through the message service.

The partial unique index `idx_message_first_response` means two concurrent replies cannot both be marked first — the loser gets a constraint violation, which the service catches and re-reads.

**⛓️ Dependencies**
Tasks 6, 12.

**✅ Expected Output**
An agent's first public reply sets `is_first_response` and `first_responded_at`. A second reply does not. The requester's own reply never counts as a first response. A customer attempting `INTERNAL` → `403`. Posting to a `CLOSED` ticket → `409 TICKET_CLOSED`.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 15 — Implement `POST /api/v1/tickets/{id}/assign`

**📝 Description**
**The endpoint with a concurrency test behind it.** Implement with a conditional update and an affected-row check — **not** a `SELECT` followed by an `UPDATE`:

```java
int updated = ticketRepository.assignIfUnassigned(ticketId, agentId);
if (updated == 0) throw new ApiException(ALREADY_ASSIGNED);   // → 409
```
```sql
UPDATE ticket
   SET assignee_id = :agentId, status = 'ASSIGNED', version = version + 1
 WHERE id = :ticketId AND assignee_id IS NULL AND tenant_id = :tenantId
```

Also: increment `agent_profile.open_count` in the same transaction; reject with `422 AGENT_AT_CAPACITY` if the agent is at `max_concurrent` or unavailable; an `AGENT` may only self-assign (`403` otherwise); `TEAM_LEAD`+ may pass `force: true` to reassign, which uses a different statement without the `assignee_id IS NULL` predicate.

**The `WHERE assignee_id IS NULL` is the entire mechanism.** A read-then-write has a window between the two statements in which another request can win; a conditional update has no window, because the predicate is evaluated under the row lock the UPDATE itself takes.

**⛓️ Dependencies**
Tasks 6, 12, and Phase 4's `AgentProfile`.

**✅ Expected Output**
Assigning an unassigned ticket → `200`. Assigning an already-assigned one → `409 ALREADY_ASSIGNED`. `force: true` as a `TEAM_LEAD` → `200`. An agent at capacity → `422`. `open_count` incremented exactly once.

**⏱️ Estimated Time**
2 hours.

---

## TASK 16 — Implement `/status`, `/resolve` and `/reopen`

**📝 Description**
Three endpoints, all routed through `TicketStateMachine`.

`POST /status` — validate with `canTransition`; on failure return `409 ILLEGAL_TRANSITION` **with the `allowedTransitions` array in the problem body**, so a client can recover without hard-coding the state machine. Require a `reason` for `WAITING_ON_CUSTOMER` and `PENDING_THIRD_PARTY`. Call `slaService` per `sideEffectOf` — stubbed until Task 27.

`POST /resolve` — set `status = RESOLVED`, `resolved_at`, store resolution text as a final public message, stop the resolution clock (stubbed). Queue the ticket for knowledge-base indexing in Phase 7 — leave a TODO with the event name.

`POST /reopen` — `RESOLVED`/`CLOSED` → `OPEN`, **increment `reopen_count`** (an input to the Phase 6 priority policy), restart the resolution clock (stubbed). Both the requester and an agent may reopen.

**⛓️ Dependencies**
Tasks 4, 6, 12.

**✅ Expected Output**
Every legal transition succeeds; every illegal one returns `409` with a populated `allowedTransitions`. Reopening increments the counter and clears `resolved_at`. Each produces the right audit event.

**⏱️ Estimated Time**
2 hours.

---

## TASK 17 — Implement attachment upload ⚠️ *cuttable*

**📝 Description**
Two-step upload: `POST /attachments` returns a presigned MinIO PUT URL and creates an `attachment` row in a pending state; the client uploads directly to MinIO; the id is then passed in `attachmentIds` on ticket or message creation.

Security requirements, all of them non-optional if you build this at all:
- `storage_key` is a generated UUID path — **never** the user's filename
- Verify the declared MIME type against **actual magic bytes** after upload, not the header
- Enforce the 10 MB cap in the presigned policy *and* re-check after
- Presigned GET URLs with a 5-minute TTL; **never proxy file bytes through the application**
- Ownership check before signing any GET

**⚠️ This is the first task to cut if you are behind.** It is a full day, it is orthogonal to everything else, and no part of the demo needs it. Ship a "attachments coming soon" note instead.

**⛓️ Dependencies**
Tasks 8, 14, and Phase 1's MinIO container.

**✅ Expected Output**
Upload a PNG and attach it to a message; the download URL works and expires. A `.exe` renamed to `.png` is rejected on magic-byte check. An 11 MB file is rejected.

**⏱️ Estimated Time**
2.5–3 hours.

---

### ✅ Phase 5A checkpoint

Before starting 5B, all of these must hold:
- [ ] Full lifecycle in Postman: create → assign → reply → status change → resolve → reopen
- [ ] Every illegal transition returns `409` with `allowedTransitions`
- [ ] Cursor pagination returns no duplicates across pages, including with concurrent deletes
- [ ] A customer's ticket detail contains no internal fields at all
- [ ] Idempotency replay returns the identical response with one row created
- [ ] Every new endpoint has a line in the Phase 4 cross-tenant `@MethodSource`

---

# PHASE 5B — The SLA Engine

## TASK 18 — Create the six SLA entity classes

**📝 Description**
`BusinessCalendar`, `BusinessHoliday`, `SlaPolicy`, `SlaRecord`, `SlaClockSegment`, `SlaEscalation` in `com.resolveai.sla.domain`, per [04 §Group 3](04-DATABASE-SCHEMA.md).

Specific mappings:
- `BusinessCalendar.workingDays` is a `Short[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`; `timezone` is a `String` holding an IANA name, **converted to `ZoneId` in the domain layer, never stored as an offset**
- `SlaPolicy.escalationRungs` is a `Short[]`
- `SlaRecord.nextDeadlineAt` is nullable — null means paused or terminal
- **`SlaClockSegment` has no setter for `startedAt`** and only a package-private setter for `endedAt`. The `trg_segment_close_once` trigger rejects reopening a closed segment; matching that in Java catches it at compile time.
- `SlaEscalation` is fully immutable — constructor only.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Six entities compile; `ddl-auto: validate` passes.

**⏱️ Estimated Time**
2 hours.

---

## TASK 19 — Create the SLA repositories and the poller claim query

**📝 Description**
Six repositories. Three queries need writing by hand:

**The poller claim** — the most important query in the system:
```sql
SELECT id FROM sla_record
 WHERE state = 'RUNNING' AND next_deadline_at <= NOW()
 ORDER BY next_deadline_at
 LIMIT :batchSize
 FOR UPDATE SKIP LOCKED
```
Returns **ids only**, in a short transaction. Task 28 explains why.

**The effective-dated policy lookup:**
```sql
SELECT * FROM sla_policy
 WHERE tenant_id = :t AND priority = :p AND plan_tier = :pt
   AND effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)
 LIMIT 1
```

**The open segment:** `findBySlaRecordIdAndEndedAtIsNull`.

Both native queries need an explicit `tenant_id` predicate where applicable — `@TenantId` does not cover native SQL. The poller query is deliberately cross-tenant (it is a system job), which is exactly why it must run under `TenantContext.runAs()` per record in Task 28.

**⛓️ Dependencies**
Task 18.

**✅ Expected Output**
Repositories compile. A test asserts the claim query returns only due, running records and that a second concurrent caller skips the locked rows rather than blocking.

**⏱️ Estimated Time**
1.5–2 hours.

---

## TASK 20 — Define `BusinessHours` and write the failing property suite

**📝 Description**
**Write the tests first.** This is the one place in the project where TDD is not optional, because the bugs are in cases you will not think to write an example for.

Define the interface:
```java
public interface BusinessHours {
    ZonedDateTime add(ZonedDateTime from, long businessMinutes, CalendarSpec cal);
    long elapsedBusinessMinutes(ZonedDateTime from, ZonedDateTime to, CalendarSpec cal);
}
```
plus `CalendarSpec` — a record of `(ZoneId zone, Set<DayOfWeek> workingDays, LocalTime dayStart, LocalTime dayEnd, Set<LocalDate> holidays)`.

Write `BusinessHoursPropertyTest` with jqwik. Five properties:

| Property | Statement |
|---|---|
| **Identity** | `add(t, 0) == t` when `t` is inside working hours |
| **Inside hours** | `add(t, n)` for `n > 0` always lands within working hours on a working day |
| **Additivity** | `add(add(t, a), b) == add(t, a + b)` |
| **Monotonicity** | `a < b` ⟹ `add(t, a) <= add(t, b)` |
| **Round-trip** | `elapsedBusinessMinutes(t, add(t, n)) == n` |

Generate `t` across a full year, `n` from 1 to 5,000 minutes, and **at least three calendar specs**: Asia/Kolkata Mon–Fri 09:00–18:00; America/New_York Mon–Fri 09:00–17:00 (which has DST); and Asia/Dubai Sun–Thu 08:00–16:00 (a non-Mon–Fri week). Include holidays that fall on a Monday and on a Friday.

**All five will fail. That is the expected output.**

**Round-trip is the property that finds the real bugs.** It composes both methods, so any disagreement between them surfaces immediately — and they *will* disagree at boundaries: exactly at `dayEnd`, exactly at `dayStart`, and for an `n` larger than a full working day.

**⛓️ Dependencies**
None.

**✅ Expected Output**
`CalendarSpec` and the interface compile. All five properties run and fail with a `UnsupportedOperationException` from the stub. jqwik reports shrunk counterexamples.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 21 — Implement `BusinessHours.add()`

**📝 Description**
Implement until the identity, inside-hours, additivity and monotonicity properties pass.

Algorithm:
1. Convert `from` to the calendar's zone
2. If outside working hours, advance to the **next** working-hours start (the clock does not run at 03:00)
3. Loop: compute minutes remaining in the current working day; if the budget fits, add and return; otherwise subtract that day's remainder and advance to the next working day's start
4. Skip non-working days and holidays

**Three traps to handle explicitly, and to leave a comment at each:**
- **DST.** Do the arithmetic on `LocalDateTime` within the zone, then resolve to `ZonedDateTime` at the end. Adding a `Duration` to a `ZonedDateTime` across a spring-forward silently loses an hour.
- **A target longer than a working day.** 5,000 minutes across a 9-hour day spans nine working days. The loop must handle that; a single subtraction will not.
- **A calendar with zero working days.** Guard and throw — otherwise you have written an infinite loop.

**⛓️ Dependencies**
Task 20.

**✅ Expected Output**
Four of five properties green. Round-trip still fails (it needs Task 22). jqwik finds no counterexample across the three calendars in 1,000 tries.

**⏱️ Estimated Time**
3–3.5 hours. *(Add 1 hour if timezone arithmetic is new. It always takes longer than expected — that is why it is its own task.)*

---

## TASK 22 — Implement `elapsedBusinessMinutes()`

**📝 Description**
Implement until the round-trip property passes.

Walk from `from` to `to`, summing the overlap of each day's `[dayStart, dayEnd]` window with the interval, skipping non-working days and holidays.

**Define the boundary convention and make both methods agree on it.** Specifically: is a timestamp exactly at `dayEnd` inside the day or at the start of the next? Pick one — recommend **half-open `[dayStart, dayEnd)`**, so `dayEnd` belongs to no day — and apply it identically in `add()`. **The round-trip property exists to catch exactly this disagreement**, and if it is still red after you implement this, the mismatch is almost certainly here.

Return 0 when `to <= from` rather than a negative number.

**⛓️ Dependencies**
Task 21.

**✅ Expected Output**
**All five properties green**, across all three calendars, at 1,000 tries each. Add three hand-written examples as documentation: Friday 16:30 + 4 business hours with a Monday holiday → Tuesday 11:30; a full week; and an interval spanning a DST transition.

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 23 — Implement effective-dated `SlaPolicy` resolution

**📝 Description**
`SlaPolicyResolver.resolve(tenantId, priority, planTier, at) → SlaPolicy`, using the query from Task 19. Cache the result in Redis under `policy:{tenant}:{priority}:{tier}` with a 5-minute TTL, evicted on policy update.

Throw a typed exception when no policy matches rather than falling back to a default — a missing policy is a configuration error and should be loud.

**Take the `at` timestamp as a parameter rather than using `now()` internally.** That is what makes the Phase 10 what-if replay possible, and it makes this class trivially testable.

**⛓️ Dependencies**
Tasks 18, 19.

**✅ Expected Output**
Resolving at a date before a policy change returns the old version; after, the new one. A cache hit avoids the query — assert on the SQL count.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 24 — Implement `SlaService.start()`

**📝 Description**
Called when a ticket's priority becomes known. In one transaction:

1. Resolve the policy for `(tenant, priority, planTier)` at `now()`
2. Create **two** `SlaRecord`s — `FIRST_RESPONSE` and `RESOLUTION` — snapshotting `target_minutes` and `policy_version` onto each
3. Open one `RUNNING` `SlaClockSegment` per record
4. Compute `next_deadline_at` for each as `BusinessHours.add(now, target × firstRung/100, calendar)`
5. Set `next_rung = 50`
6. Record `SLA_STARTED` events

**Snapshotting `target_minutes` onto the record rather than joining to the policy at read time is what makes a historical SLA explicable.** Tightening a policy in October must not retroactively breach a ticket from September.

Handle re-entry idempotently: `uq_sla_ticket_kind` means calling `start()` twice raises a constraint violation. Catch it, verify the constraint name, and return the existing records.

**⛓️ Dependencies**
Tasks 22, 23.

**✅ Expected Output**
Starting SLA on a P2/PRO ticket creates two records with correct targets, two open segments, and two future deadlines computed in business hours. Calling twice creates nothing new.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 25 — Implement `pause()` and `resume()` ⚠️ *the trap*

**📝 Description**
**The single most likely source of a mysterious constraint violation in this project** ([04 §Step 8 trap 3](04-DATABASE-SCHEMA.md)).

Pause, in one transaction, **with explicit statements in this exact order**:
```java
int closed = segmentRepo.closeOpenSegment(recordId, now);   // UPDATE … WHERE ended_at IS NULL
if (closed == 0) return currentState(recordId);             // already paused → idempotent 200
segmentRepo.insertPaused(recordId, now, reason);            // INSERT
record.setState(PAUSED);
record.setNextDeadlineAt(null);                             // removes it from the poller's index
eventRecorder.record(ticket, SLA_PAUSED, …);
```

Resume is the mirror, and additionally **recomputes `next_deadline_at` from the elapsed total**, not from the original start:
```java
long elapsed   = sumBusinessMinutesOfRunningSegments(recordId);
long remaining = (target × nextRung / 100) - elapsed;
record.setNextDeadlineAt(businessHours.add(now, remaining, calendar));
```

**Do not model this as a cascading `@OneToMany` collection you mutate.** Closing one segment and adding another through a managed collection lets Hibernate reorder the INSERT before the UPDATE at flush time, which trips `uq_segment_open` with an error that points at the wrong place entirely. **Explicit repository calls, in order, with an affected-row check.**

The `closed == 0` branch is also how two concurrent pause requests resolve: one wins, the other observes that the work is already done and returns the same `200`.

**⛓️ Dependencies**
Task 24.

**✅ Expected Output**
Pause closes the running segment and opens a paused one; exactly one open segment exists. Resume reverses it and recomputes the deadline correctly. Pausing twice is a no-op returning `200`. Pausing a `MET` record → `409 SLA_ALREADY_TERMINAL`.

**⏱️ Estimated Time**
2.5–3 hours.

---

## TASK 26 — Implement elapsed derivation and `GET /tickets/{id}/sla`

**📝 Description**
`SlaCalculator.elapsedBusinessMinutes(recordId)` — load all segments, sum `BusinessHours.elapsedBusinessMinutes(start, end ?? now)` over the `RUNNING` ones.

**Confirm no `elapsed_minutes` column exists anywhere.** If you find yourself wanting to add one for performance, the answer is a cached read model, not a mutable counter — a counter under concurrency is a lost update.

Implement the endpoint from [05 §3.4](05-API-CONTRACT.md), returning both clocks with their full segment arrays, remaining minutes, escalations fired, and the calendar. The segment array is exposed deliberately: it makes the append-only design visible and it is the fastest way to debug a wrong clock.

Leave `prediction` as `null` until Task 30.

**⛓️ Dependencies**
Tasks 22, 25.

**✅ Expected Output**
For a ticket paused for two days over a weekend, `elapsedBusinessMinutes` excludes both the pause and the weekend. The endpoint returns both clocks with correct segment histories.

**⏱️ Estimated Time**
2 hours.

---

## TASK 27 — Wire SLA into the ticket lifecycle

**📝 Description**
Replace every stub left in 5A with a real call:

| Trigger | Action |
|---|---|
| Ticket created *(Phase 5; moves to post-triage in Phase 6)* | `slaService.start(ticket)` |
| First public agent reply (Task 14) | `markFirstResponseMet` — close the segment, `state = MET`, `met_at`, `next_deadline_at = null` |
| → `WAITING_ON_CUSTOMER` / `PENDING_THIRD_PARTY` | `pause(RESOLUTION, reason)` |
| ← leaving a waiting state | `resume(RESOLUTION)` |
| → `RESOLVED` | `stop(RESOLUTION)` → `MET` or `BREACHED` by comparison |
| → `REOPENED` | new `RESOLUTION` record; the old one stays terminal for history |

Drive it all from `TicketStateMachine.sideEffectOf` (Task 4) rather than scattered `if` statements.

**Every one of these runs in the same transaction as the ticket change.** A status change that commits while its clock pause rolls back leaves a permanently wrong SLA, and nothing would tell you.

**⛓️ Dependencies**
Tasks 14, 16, 24, 25.

**✅ Expected Output**
Full lifecycle in Postman with `GET /sla` checked at each step: create → clocks start; reply → first-response `MET`; waiting → resolution `PAUSED`; back → `RUNNING`; resolve → `MET`; reopen → a fresh resolution clock.

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 28 — Implement the deadline poller

**📝 Description**
`SlaDeadlinePoller`, `@Scheduled(fixedDelay = 10_000)`.

**Two-transaction design, and the reasoning matters:**

```java
// Transaction 1 — short. Claim ids only.
List<Long> due = slaRecordRepo.claimDueIds(batchSize);   // FOR UPDATE SKIP LOCKED

// Transaction 2..N — one per record, each independent.
for (Long id : due) {
    txTemplate.execute(() -> {
        var record = slaRecordRepo.findByIdForUpdate(id);
        if (record.getState() != RUNNING) return null;        // re-check under the lock
        if (record.getNextDeadlineAt().isAfter(now())) return null;
        TenantContext.runAs(record.getTenantId(), () -> processRung(record));
        return null;
    });
}
```

**Why not process all 200 inside the claiming transaction:** the `FOR UPDATE` locks would be held for the whole batch, and any agent replying to one of those 200 tickets would block behind it. Claiming ids in a short transaction and re-locking per record keeps every lock to milliseconds.

**Why re-checking state under the lock is safe even though two poller runs might pick the same id:** `uq_escalation_rung` makes the effect idempotent. The cheap claim strategy is only viable *because* that constraint exists — which is a nice illustration of a database constraint buying you a simpler application design.

**`TenantContext.runAs` is mandatory.** The poller is a system job with no request and therefore no tenant, and `@TenantId` with an unset context is exactly the hole Phase 4 Task 6 tested for.

Set `spring.task.scheduling.pool.size` above 1 so the SLA poller does not block the other schedulers added in Phase 6.

**⛓️ Dependencies**
Tasks 19, 26.

**✅ Expected Output**
A test inserts a record with `next_deadline_at` in the past, runs one poll, and asserts the rung fired. A record with a future deadline is untouched. A `PAUSED` record is never claimed.

**⏱️ Estimated Time**
3–3.5 hours.

---

## TASK 29 — Implement the escalation ladder

**📝 Description**
`processRung(record)`:

1. `elapsed = SlaCalculator.elapsedBusinessMinutes(record)`
2. `pct = elapsed × 100 / target`
3. Determine the highest uncrossed rung in `policy.escalationRungs`
4. **Insert the `sla_escalation` row first** — it is the guard
5. Create the notification: 50% → assignee, 75% → team lead, 90% → mark at-risk, 100% → breach
6. Rung 100: `state = BREACHED`, `breached_at`, `SLA_BREACHED` event, apply the reassignment/priority-bump policy
7. Rung < 100: recompute `next_deadline_at` for the *next* rung and set `next_rung`

**The exactly-once mechanism:**
```java
try {
    escalationRepo.insert(recordId, rung, elapsed);
} catch (DataIntegrityViolationException e) {
    if (!isConstraint(e, "uq_escalation_rung")) throw e;
    return;   // already fired — this is the mechanism working, not an error
}
```

**Check the constraint name before swallowing.** A bare `catch (DataIntegrityViolationException)` would also hide a genuine bug in the notification insert, and you would never see it.

This is the payoff for the whole design: the poller is at-least-once by construction, and a unique index turns that into an exactly-once *effect* with no distributed coordination.

**⛓️ Dependencies**
Task 28.

**✅ Expected Output**
A clock fast-forwarded past 50% fires one escalation and one notification, and `next_rung` becomes 75. Running the poller again immediately fires nothing. Crossing 100% marks `BREACHED` and stops the clock.

**⏱️ Estimated Time**
2–2.5 hours.

---

## TASK 30 — Implement breach prediction

**📝 Description**
Write `V9__resolved_ticket_stats.sql` creating a **materialized view** of resolved tickets from the last 90 days with their resolution business-minutes, category, priority and team, plus `REFRESH MATERIALIZED VIEW CONCURRENTLY` on a nightly schedule.

Then:
```sql
SELECT percentile_cont(0.75) WITHIN GROUP (ORDER BY business_minutes) AS p75,
       count(*) AS sample_size
  FROM resolved_ticket_stats
 WHERE tenant_id = :t AND category = :c AND priority = :p AND team_id = :tm
```

`atRisk = remainingBudget < p75`. Fall back to a coarser grouping — drop team, then drop category — when `sample_size < 20`, and **report which grouping was used** in the `basis` string.

**The materialized view is necessary, not decoration.** Computing resolution business-minutes per ticket means summing over `sla_clock_segment` and running `BusinessHours` per row. Doing that live on every dashboard load would be the slowest query in the application.

**And say this out loud in interviews:** *"I considered an LLM here and rejected it. A percentile over 90 days of the same class of ticket is faster, cheaper, more accurate and explainable. Knowing where not to use the model is part of the design."*

**⛓️ Dependencies**
Tasks 26, 29.

**✅ Expected Output**
With seeded history, prediction returns a p75 and a sample size. A `(category, priority, team)` combination with fewer than 20 samples falls back and says so in `basis`. `REFRESH` completes without locking reads.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 31 — Implement `GET /api/v1/sla/at-risk`

**📝 Description**
Implement the endpoint from [05 §3.4](05-API-CONTRACT.md): running clocks where `atRisk` is true or the deadline is within `withinBusinessMinutes` (default 120), ranked by `riskScore`, cursor-paginated, scoped by role like the ticket queue.

Include `reason` as a human-readable string — *"Predicted resolution (180 min, p75 for this class) exceeds remaining budget (34 min)"* — because a ranked list with no explanation is a list nobody trusts.

Wire `prediction` into `GET /tickets/{id}/sla` (the null left in Task 26).

**⛓️ Dependencies**
Tasks 9, 30.

**✅ Expected Output**
Returns at-risk tickets ranked correctly, role-scoped, with populated reasons. An agent sees only their team's. **Add the endpoint to the cross-tenant `@MethodSource`.**

**⏱️ Estimated Time**
1.5–2 hours.

---

# PHASE 5C — Concurrency Tests & Close

These four tasks produce **the artifacts you demo in interviews.** Do not skip them, and do not leave them for Phase 9 — write them while the code is fresh.

## TASK 32 — Concurrent assignment test

**📝 Description**
`ConcurrentAssignmentTest extends IntegrationTestBase`.

**Test A — the race.** One unassigned ticket, 20 threads calling `POST /assign` simultaneously, released by a `CountDownLatch`. Assert **exactly one `200` and nineteen `409 ALREADY_ASSIGNED`**, and that `open_count` incremented by exactly 1.

**Test B — load distribution.** 50 tickets, 5 available agents, auto-assignment running concurrently. Assert **`max(open_count) − min(open_count) ≤ 1`**.

Use a `CountDownLatch` to release all threads at once — **never `Thread.sleep`**. A sleep-based concurrency test is flaky, gets `@Disabled` within a week, and then protects nothing.

**Test B is the one with a story attached.** Hyperlocal assigns to the least-loaded agent too, and that implementation has this exact race — a concurrent burst piles onto one agent. *"I found the bug in my own earlier project when I built this properly, and here is the test that proves the fix"* is one of the strongest things you can say in an interview, because it demonstrates growth rather than just knowledge.

**⛓️ Dependencies**
Task 15.

**✅ Expected Output**
Both tests green, run 10 times consecutively with no flake.

**⏱️ Estimated Time**
2 hours.

---

## TASK 33 — Reply-versus-breach race and concurrent pause tests

**📝 Description**
**Test A — reply beats the poller.** Set a first-response deadline 1 second in the future. Start two threads on a latch: one posts an agent reply, one runs the poller. Repeat 50 times. Assert **for every run: either the SLA is `MET` with no breach row, or it is `BREACHED` with no first response — never both, and never neither.**

The invariant is that the outcome is *consistent*, not that a particular side always wins. The row lock serialises them; the test proves the serialisation is correct.

**Test B — concurrent pause.** Two threads pause the same record simultaneously. Assert both receive `200`, and that **exactly one open segment exists** and exactly two segment rows total.

**Test C — pause during resume.** Pause and resume fired simultaneously; assert the record ends in a valid state with exactly one open segment, whichever order wins.

**⛓️ Dependencies**
Tasks 25, 27, 28.

**✅ Expected Output**
Test A green over 50 iterations. Tests B and C green over 10. **Watch Test A fail on purpose** by removing the `FOR UPDATE` from the poller's per-record re-read — if it still passes, the test is not actually exercising the race and needs tightening.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 34 — Escalation exactly-once and poller-restart tests

**📝 Description**
**Test A — exactly-once.** Fast-forward a clock past all four rungs. Run the poller **three times in a row**. Assert exactly four `sla_escalation` rows and four notifications, not twelve.

**Test B — concurrent pollers.** Two poller instances on a latch against the same due record. Assert one escalation row.

**Test C — restart recovery.** This is the one that demonstrates the design's payoff. Create a record with a deadline 2 hours in the past — simulating an application that was down. Run one poll. Assert **the correct rung fires immediately** and nothing was lost.

Add a comment on Test C explaining the contrast: an in-memory `ScheduledExecutorService` would have silently dropped that escalation on restart, and nobody would have noticed until a customer escalated. **Absolute deadlines in an indexed column are what make this recoverable.**

**⛓️ Dependencies**
Tasks 28, 29.

**✅ Expected Output**
All three green. Test C in particular — it is the clearest demonstration of why the polling design was chosen, and the one to walk an interviewer through.

**⏱️ Estimated Time**
2 hours.

---

## TASK 35 — Clean up, verify and tag Phase 5

**📝 Description**
Remove every stub and TODO left from 5A. Run a full clean verification from empty:
```bash
docker compose down -v && docker compose up -d && mvn clean verify
```

Update the README with an "SLA engine" section covering business-hours arithmetic, the append-only clock, absolute deadlines and polling, and exactly-once escalation. **Include the restart-recovery property explicitly** — it is the most interesting thing in the phase.

Extend the Postman collection with the Tickets and SLA folders, including a request that fast-forwards a clock for demo purposes.

Record the first real numbers for the benchmark table in [07](07-DEV-PHASES.md): concurrent assignments survived, load-balance skew, poller batch latency.

Commit and tag `phase-5-complete`.

**⛓️ Dependencies**
Task 34.

**✅ Expected Output**
Clean verify from an empty database. CI green. Three benchmark placeholders replaced with measured numbers. Tag pushed.

**⏱️ Estimated Time**
1.5 hours.

---

## 📅 Suggested Daily Schedule

### Phase 5A — Ticketing (10 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 4.0 | T1 Entities (2.5) · T2 Reference generator (2.0) *— overrun into Day 2* |
| 2 | 3.5 | T2 finish · T3 Repositories (1.5) · T4 State machine (2.0) |
| 3 | 3.0 | T5 State machine tests (1.5) · T6 Event recorder (1.5) |
| 4 | 3.0 | T7 Idempotency interceptor (3.0) |
| 5 | 4.0 | T8 POST /tickets (2.0) · T9 Cursor pagination (2.0) |
| 6 | 3.0 | T10 GET /tickets (3.0) |
| 7 | 4.0 | T11 GET /tickets/{id} (2.5) · T12 ETag (1.5) |
| 8 | 4.0 | T13 PATCH (1.5) · T14 Messages (2.5) |
| 9 | 4.0 | T15 Assign (2.0) · T16 Status/resolve/reopen (2.0) |
| 10 | 3.0 | T17 Attachments (3.0) *— **cut this day if behind*** |

**→ 5A checkpoint.** Take stock here before continuing.

### Phase 5B — SLA Engine (7 days)

| Day | Hours | Tasks |
|---|---|---|
| 11 | 4.0 | T18 SLA entities (2.0) · T19 Repositories + poller query (2.0) |
| 12 | 2.5 | T20 Property suite, failing (2.5) |
| 13 | 3.5 | T21 `add()` (3.5) |
| 14 | 4.0 | T22 `elapsed()` (2.5) · T23 Policy resolution (1.5) |
| 15 | 2.5 | T24 `start()` (2.5) |
| 16 | 3.0 | T25 pause/resume (3.0) ⚠️ |
| 17 | 4.0 | T26 Elapsed + GET /sla (2.0) · T27 Wire into lifecycle (2.0) |
| 18 | 3.5 | T28 Deadline poller (3.5) |
| 19 | 4.5 | T29 Escalation ladder (2.5) · T30 Breach prediction (2.5) *— split across 19/20* |
| 20 | 2.0 | T30 finish · T31 /sla/at-risk (2.0) |

*Days 12–14 are three consecutive days on one pure function with no visible product progress. That is correct and expected. **Do not skip ahead** — every SLA feature built on incorrect business-hours arithmetic has to be re-verified afterwards.*

### Phase 5C — Tests & close (2–3 days)

| Day | Hours | Tasks |
|---|---|---|
| 21 | 4.0 | T32 Concurrent assignment (2.0) · T33 Reply-vs-breach (2.5) *— split* |
| 22 | 3.5 | T33 finish · T34 Exactly-once + restart (2.0) |
| 23 | 1.5 | T35 Clean up, verify, tag (1.5) |

**Day 24 — Buffer / Catch-up.** Reserved, and it will be used. Most likely by T21 (`add()` has more edge cases than it looks), T25 (the Hibernate flush-ordering trap), or T28 (getting the two-transaction poller right). If none bite, use it to build [06 §11](06-UI-UX-DESIGN.md) Tier 2 — the Agent Queue now has real endpoints behind it.

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 35   (5A: 17 · 5B: 14 · 5C: 4)
Total Estimate : 62–70 hours
Suggested Days : 23 working days + 1 buffer
                 (NOT the 8–11 days in doc 07 — see the correction
                  at the top of this document)

Hardest Task   : Task 21 — BusinessHours.add()
                 Three compounding difficulties: DST handled by doing
                 arithmetic on LocalDateTime and resolving to the zone
                 only at the end; budgets spanning many working days;
                 and boundary conventions at dayStart/dayEnd that must
                 match elapsed() exactly or the round-trip property
                 stays red and you will not know which method is wrong.

Runner-up      : Task 25 — pause/resume.
                 Not conceptually hard, but the Hibernate flush-ordering
                 trap produces a uq_segment_open violation whose error
                 message points somewhere else entirely. Budget the full
                 3 hours even though the logic looks like 45 minutes.

Most Skipped   : Task 20 — writing the property suite BEFORE the
                 implementation. Writing five failing tests against a
                 stub feels like procrastination, so the instinct is to
                 "just write add() and test it after". Every example-based
                 test you would write by hand covers a case you already
                 thought of. The properties cover the cases you did not —
                 which is precisely where the DST and boundary bugs live.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 5 exit checklist

Ten items. Tick all before starting Phase 6:

- [ ] `docker compose down -v && docker compose up -d && mvn clean verify` passes from empty
- [ ] Full lifecycle in Postman: create → assign → reply → pause → resume → resolve → reopen
- [ ] Every illegal transition returns `409` with `allowedTransitions`
- [ ] **All five `BusinessHours` properties green across three calendars including a DST zone and a non-Mon–Fri week**
- [ ] Clocks pause and resume correctly; **no `elapsed_minutes` column exists anywhere**
- [ ] The poller fires each rung exactly once and **recovers a 2-hour-old missed deadline on restart**
- [ ] 20 concurrent assignments → one `200`, nineteen `409`
- [ ] 50 tickets across 5 agents → load skew ≤ 1
- [ ] Reply-vs-breach race is consistent over 50 iterations
- [ ] Every new endpoint has a line in the Phase 4 cross-tenant `@MethodSource`

---

## Next Steps

> ✅ **Task list ready for Phase 5 — Ticketing & the SLA Engine!**
>
> **Three things the ordering depends on, in decreasing order of importance:**
>
> 1. **Write the property tests (Task 20) before implementing `BusinessHours`.** It is the one place in this project where TDD is genuinely not optional.
> 2. **Complete the 5A checkpoint before starting 5B.** A working ticket lifecycle with no SLA is a real, demoable stopping point. A half-built SLA engine on top of half-built ticketing is not.
> 3. **Write the 5C concurrency tests now, not in Phase 9.** They are the highest-value artifacts in the project — the thing you actually demo — and they are far easier to write while the code is fresh in your head.
>
> **Carry into Phase 6:**
> - `POST /tickets` changes from `201` to `202` when triage becomes asynchronous. Keep that as a separate, well-messaged commit.
> - `slaService.start()` moves out of ticket creation and into the triage transaction, because clocks should start when priority is known.
> - The `IN_FLIGHT` reaper listed under Phase 5 in [07](07-DEV-PHASES.md) belongs to the **outbox**, not to `sla_record`. It has been moved to Phase 6 — the SLA poller needs no reaper, because it holds no lock between transactions.
>
> **When Phase 5 is complete:**
> Run the `task-breakdown` skill on **Phase 6 — Async Pipeline & AI Triage**, and expect its 6–8 day estimate to land nearer 12–14 once broken down.
