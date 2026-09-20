# 07 — Development Phases

> Skill: `dev-phases` · Input: [02 — Project Plan](02-PROJECT-PLAN.md) §10 (expanded, not repeated)
> Output: the build roadmap you execute day by day

**Assumed working rate: 3–4 focused hours per day**, alongside MCA coursework. All estimates are in working days at that rate, not calendar days.

---

## PHASE 1 — Requirements Finalization & Environment Setup

**🎯 Goal**
Every tool installed, the full Docker stack running, the repository initialised, and a committed synthetic corpus that makes every later feature demoable from day one.

**📋 Tasks**
1. Install and verify: JDK 21 (`java -version` must say 21), Maven 3.9+, Docker Desktop, Node 20+, IntelliJ IDEA, DBeaver or pgAdmin, Postman.
2. Create the GitHub repository with a `.gitignore` covering `target/`, `node_modules/`, `.env`, and `*.log`.
3. Write `docker-compose.yml` with **eight** services: `postgres` (use `pgvector/pgvector:pg16`, **not** plain `postgres`), `redis`, `minio`, `mailhog`, `prometheus`, `grafana`, `ollama`, and a placeholder `app`.
4. Start the stack and **verify `CREATE EXTENSION vector;` succeeds** in psql. Do this before anything else.
5. Verify Redis with `redis-cli PING`, and MinIO by creating a bucket through its console.
6. Obtain an LLM API key (primary) and a second provider key (fallback). Store both in `.env`, which is git-ignored, and commit a `.env.example` with the key *names* only.
7. Pull a small local model into Ollama (e.g. `llama3.2:3b`) for the `external_model_allowed=false` path.
8. Write the **seed generator** as a standalone script: ~2,000 tickets across 8 categories with realistic resolutions, ~60 KB articles, ~15 runbooks, a 4-week arrival pattern with a realistic diurnal shape, and **three planted incident storms**.
9. Hand-verify a 30-ticket sample from the generated corpus and fix the generator until the output is plausible.
10. **Commit the generated JSON**, not just the generator, so the corpus is deterministic and reviewable.
11. Write the first draft of `README.md`: problem statement, the one-paragraph evidence it is real, and the architecture diagram from [03](03-SYSTEM-ARCHITECTURE.md).

**✅ Deliverables**
- `docker compose up` brings all eight containers to healthy
- `CREATE EXTENSION vector;` confirmed working in the project database
- `seed/tickets.json`, `seed/knowledge.json`, `seed/incidents.json` committed
- `.env.example` committed; real `.env` ignored
- Both LLM providers answer a curl smoke test
- README exists with problem, evidence and architecture diagram

**⛓️ Dependencies**
None — this is the starting point.

**⏱️ Estimated Effort**
**2–3 days.** Add 1 day if Docker is new to you — the pgvector image and MinIO bucket setup each have a fiddly first-time step.

**⚠️ Watch Out For**
1. **Using the plain `postgres:16` image.** It does not ship pgvector, and you will not find out until Week 4 when embeddings fail. Use `pgvector/pgvector:pg16` from the first line of the compose file.
2. **Under-investing in the seed generator.** It feels like setup, but it is the thing that makes retrieval, drafting and incident correlation demoable at all. A thin corpus means your best three features show empty boxes. Budget a full day and hand-check the output.
3. **Committing a real API key.** Add `.env` to `.gitignore` *before* the first commit, not after — a key in git history is still a leaked key even after you delete the file.

---

## PHASE 2 — System & Database Design

**🎯 Goal**
Every schema decision, endpoint contract, screen and architectural trade-off finalised and written down, so that no design question blocks you once coding starts.

**📋 Tasks**
1. Run the **`db-schema-designer` skill** → produces [04 — Database Schema](04-DATABASE-SCHEMA.md).
2. Run the **`api-designer` skill** → produces [05 — API Contract](05-API-CONTRACT.md).
3. Run the **`ui-ux-designer` skill** → produces [06 — UI/UX Design](06-UI-UX-DESIGN.md).
4. Run the **`system-architect` skill** → produces [03 — System Architecture](03-SYSTEM-ARCHITECTURE.md). Required for this project: it has async workers, a queue, an AI subsystem and multiple failure modes.
5. Convert the SQL from [04 §Step 6](04-DATABASE-SCHEMA.md) into Flyway migrations `V1__` through `V7__`.
6. Apply the migrations against the running Postgres container and verify every table, index and trigger is created.
7. **Hand-test the three structural guarantees in psql** — this is the highest-value 15 minutes in the whole phase:
   - Insert two `sla_clock_segment` rows with `ended_at IS NULL` for the same record → the second must fail on `uq_segment_open`
   - Insert the same `(sla_record_id, rung)` twice → the second must fail on `uq_escalation_rung`
   - Insert two live `incident_ticket` rows for the same ticket → the second must fail on `uq_incident_ticket_live`
8. Build the Postman collection from [05](05-API-CONTRACT.md) with folders per group. It becomes your Phase 10 smoke-test suite.
9. Review the whole design once, end to end, looking for a feature with no endpoint or a table with no writer.

**✅ Deliverables**
- Documents 03–06 complete in `ResolveAI/`
- `V1__` … `V7__` migrations apply cleanly from an empty database
- All three partial-unique-index guarantees verified by hand
- Postman collection committed
- Every MVP feature traced to endpoints, tables and screens

**⛓️ Dependencies**
Phase 1 (Postgres running with pgvector available).

**⏱️ Estimated Effort**
**3–4 days.** *(Already complete — the documents in this folder are this phase's output.)*

**⚠️ Watch Out For**
1. **Skipping the hand-test in step 7.** Those three constraints carry the system's most important correctness guarantees. Discovering in Week 5 that you wrote `UNIQUE (sla_record_id, ended_at)` instead of a partial index costs a day of confused debugging.
2. **Treating this phase as optional because you are keen to code.** Every hour here saves three later. The specific failure mode is starting Phase 5 without a finalised SLA schema and then migrating a table you already have code against.
3. **Letting Hibernate create the schema.** Set `ddl-auto=validate` in Phase 3 and never change it. `update` cannot express partial indexes, CHECK constraints, HNSW indexes or triggers — it would silently build a weaker schema than the one you designed.

---

## PHASE 3 — Project Setup & Boilerplate

**🎯 Goal**
A Spring Boot application that starts, connects to Postgres and Redis, passes Flyway validation, exposes a health endpoint, and has a working Testcontainers integration test — with nothing resembling a feature in it yet.

**📋 Tasks**
1. Generate the project with Spring Initializr: Java 21, Maven, Spring Boot 3.3.x. Dependencies: Web, Data JPA, Security, Validation, Actuator, PostgreSQL Driver, Flyway, Data Redis, Lombok.
2. Add the remaining dependencies manually: `spring-ai-openai-spring-boot-starter` (or Anthropic), `pgvector` JDBC support, `minio`, `micrometer-registry-prometheus`, `resilience4j-spring-boot3`, `testcontainers` (junit-jupiter, postgresql), `wiremock`, `jqwik`.
3. Create the package structure from [03 §10.1](03-SYSTEM-ARCHITECTURE.md): `iam`, `ticketing`, `sla`, `triage`, `knowledge`, `incidents`, `drafting`, `eval`, `platform`.
4. Write `application.yml` with profiles `local`, `test`, `prod`. Set `spring.jpa.hibernate.ddl-auto=validate` and `spring.flyway.enabled=true`.
5. Configure HikariCP: `maximum-pool-size=10`, `leak-detection-threshold=30000`.
6. Copy the Flyway migrations from Phase 2 into `src/main/resources/db/migration/` and confirm the app starts and validates against them.
7. Configure the Redis connection and write a trivial set/get to prove it works.
8. Write the global `@RestControllerAdvice` producing **RFC 7807 `application/problem+json`** from day one, with handlers for `MethodArgumentNotValidException`, `EntityNotFoundException`, `OptimisticLockingFailureException`, `AccessDeniedException` and a catch-all.
9. Define the `ApiProblem` response type and the `errorCode` enum.
10. Add the base Testcontainers test class with a shared `@Container PostgreSQLContainer` and Redis container, using `@ServiceConnection`.
11. Write one integration test that starts the context, applies migrations against the container, and asserts `/actuator/health` returns `UP`.
12. Configure Actuator: expose `health`, `info`, `prometheus`; add a custom health indicator stub for outbox lag.
13. Set up structured JSON logging with MDC placeholders for `traceId`, `tenantId`, `ticketId`.
14. Create the GitHub Actions workflow: checkout, JDK 21, `mvn verify`, cache `~/.m2`.

**✅ Deliverables**
- `mvn spring-boot:run` starts cleanly and connects to Postgres and Redis
- Flyway applies all migrations; `ddl-auto=validate` passes
- `GET /actuator/health` returns `UP` with database and Redis indicators
- One green Testcontainers integration test
- `@RestControllerAdvice` returns RFC 7807 for a deliberately triggered validation error
- CI runs green on push

**⛓️ Dependencies**
Phase 2 (migrations exist and are verified).

**⏱️ Estimated Effort**
**3–4 days.** Add 1 day if Testcontainers is new — the first container start is slow and the `@ServiceConnection` wiring has a learning curve.

**⚠️ Watch Out For**
1. **Skipping the error handler until "later".** Build `@RestControllerAdvice` now, in Phase 3, and every endpoint you write afterwards returns correct errors from its first line. Retrofitting it across 50 endpoints in Phase 9 is miserable and you will do it badly.
2. **Testcontainers starting a fresh container per test class.** Use a static container with `@ServiceConnection`, or your test suite goes from 40 seconds to 8 minutes and you will stop running it.
3. **Forgetting `ddl-auto=validate`.** The default in some setups is `none`, which silently lets your entities drift from the schema. `validate` fails fast at startup, which is exactly what you want.

---

## PHASE 4 — Core Authentication & User Management

**🎯 Goal**
All four roles working end to end with JWT access tokens and rotating refresh tokens, tenant isolation enforced below the repository layer, and a test suite that proves a tenant cannot read another tenant's data.

**📋 Tasks**
1. Create entities: `Tenant`, `AppUser`, `Team`, `AgentProfile`, `RefreshToken`, following the mapping notes in [04 §Step 8](04-DATABASE-SCHEMA.md).
2. Create the corresponding repositories.
3. Implement `UserDetailsService` loading by `(tenantSlug, email)`.
4. Write the JWT utility: sign HS256, verify, extract claims (`sub`, `tenantId`, `role`, `jti`), with issuer and audience validation.
5. Write the JWT authentication filter and register it before `UsernamePasswordAuthenticationFilter`.
6. Write `SecurityConfig`: stateless sessions, public matchers for `/auth/register`, `/auth/login`, `/auth/refresh` and `/actuator/health`, everything else authenticated, method security enabled.
7. Implement `POST /auth/register` with BCrypt cost 12 and the validation rules from [05](05-API-CONTRACT.md).
8. Implement `POST /auth/login` — including the **constant-time response**: always run a BCrypt comparison, against a dummy hash when the user does not exist.
9. Implement `POST /auth/refresh` with single-use rotation and **family revocation on reuse detection**.
10. Implement `POST /auth/logout` and `GET /auth/me` (returning the `permissions` array).
11. Implement the **tenant isolation mechanism**: a Hibernate `@Filter` on every tenant-scoped entity, enabled per request from the authenticated principal.
12. Implement role-based method security with `@PreAuthorize` on service methods, not only controllers.
13. Seed tenants, teams and users of all four roles from the Phase 1 corpus.
14. Write the **parameterised cross-tenant test**: for every endpoint, call it with Tenant A's token against Tenant B's resource ID and assert `404` or `403`.
15. Write the refresh-token reuse test: use a token twice, assert the whole family is revoked.

**✅ Deliverables**
- Register → login → authenticated request works in Postman for all four roles
- Refresh rotates the token; replaying a used token revokes the family
- An `AGENT` calling an `ADMIN` endpoint gets `403`; an unauthenticated call gets `401`
- **The cross-tenant test suite passes for every endpoint that exists so far**
- `GET /auth/me` returns the correct `permissions` array per role

**⛓️ Dependencies**
Phase 3 (app runs, security dependency present, error handler exists).

**⏱️ Estimated Effort**
**4–5 days**, of which roughly 30% is porting JWT and RBAC code from Hyperlocal rather than writing it fresh. The genuinely new work is the Hibernate tenant filter and refresh-token rotation — budget 2 days for those two alone.

**⚠️ Watch Out For**
1. **Relying on `AND tenant_id = :tenantId` in every query.** It works until the one method where you forget, and that one method is a cross-tenant data breach. The Hibernate filter is the mechanism; the parameterised test is the evidence. Build both.
2. **Putting `@PreAuthorize` only on controllers.** A service method called from a worker or another service then has no check at all. Annotate the service layer.
3. **Refresh-token rotation without reuse detection.** Rotation alone is half the defence. The detection — a used token being presented again revokes the family — is the part that actually stops a stolen token.

---

## PHASE 5 — Core MVP: Ticketing & the SLA Engine

**🎯 Goal**
The complete ticket lifecycle with an enforced state machine and audit trail, plus the entire SLA engine — business-hours arithmetic, pausable append-only clocks, the deadline poller, and an escalation ladder that fires exactly once per rung.

**This is the longest phase and it contains no AI at all. That is deliberate** — see the ordering note in the summary.

**📋 Tasks**

*Ticketing (days 1–4)*
1. Create entities: `Ticket`, `TicketMessage`, `TicketEvent`, `Attachment`.
2. Implement the reference generator (`TKT-10428`) using a per-tenant sequence.
3. Implement `TicketStateMachine` as a pure component holding the legal transition table from [05 §3.2](05-API-CONTRACT.md), returning allowed targets for any state.
4. Implement `TicketService`: create, get, list (cursor-paginated, role-scoped), patch.
5. Implement the audit writer — every transition appends a `TicketEvent` in the same transaction as the change.
6. Implement `POST /tickets/{id}/messages`, including setting `first_responded_at` **in the same transaction** as the first public agent message.
7. Implement `POST /tickets/{id}/assign` with the **conditional update and affected-row check**, returning `409 ALREADY_ASSIGNED` on a loss.
8. Implement `POST /tickets/{id}/status`, `/resolve`, `/reopen`, with illegal transitions returning `409` plus the allowed set.
9. Implement cursor pagination: encode/decode the `(created_at, id)` cursor.
10. Implement `ETag` / `If-Match` on ticket mutations via `@Version`.
11. Implement attachment upload to MinIO with presigned URLs and magic-byte type verification.

*SLA engine (days 5–9)*
12. Create entities: `BusinessCalendar`, `BusinessHoliday`, `SlaPolicy`, `SlaRecord`, `SlaClockSegment`, `SlaEscalation`.
13. **Write `BusinessHours` as a pure class** with two methods: `add(ZonedDateTime, minutes, Calendar) → ZonedDateTime` and `elapsedBusinessMinutes(from, to, Calendar) → long`.
14. **Write the jqwik property tests for `BusinessHours` before the implementation is finished**: adding zero is identity; the result always lands inside working hours; `add(add(t,a),b) == add(t,a+b)`; monotonicity; and the round-trip `elapsed(t, add(t,n)) == n`.
15. Implement `SlaService.start(ticket)` — resolve the effective-dated policy, create two `SlaRecord`s, snapshot `target_minutes` and `policy_version`, open the first `RUNNING` segment, compute `next_deadline_at`.
16. Implement pause and resume: close the open segment, open a new one, recompute or null `next_deadline_at`. **Insert and update the rows explicitly, in order** — do not mutate a cascading collection (see [04 §Step 8 trap 3](04-DATABASE-SCHEMA.md)).
17. Implement elapsed-time derivation as a `SUM()` over `RUNNING` segments. Confirm no `elapsed_minutes` column exists anywhere.
18. Implement the **deadline poller**: the `SKIP LOCKED` query, rung determination, escalation insert, notification, `next_deadline_at` recomputation, breach handling.
19. **Catch `DataIntegrityViolationException` on the escalation insert, check the constraint name is `uq_escalation_rung`, and swallow it.** That exception is the exactly-once mechanism working.
20. Implement the reaper for `IN_FLIGHT` rows past `locked_until`.
21. Implement breach prediction: a p75 window function over the last 90 days grouped by `(category, priority, team)`, exposed at `GET /sla/at-risk`.
22. Implement `GET /tickets/{id}/sla` returning clocks, segments and prediction.

*Concurrency tests (day 10)*
23. **Test: 20 threads call `/assign` on one unassigned ticket → exactly one `200`, nineteen `409`.**
24. **Test: an agent replies at the same instant the poller would breach → assert no breach is recorded.**
25. **Test: two concurrent pause requests → one succeeds, one is a no-op replay; exactly one open segment exists.**
26. **Test: fast-forward a clock past all four rungs, run the poller twice → exactly four escalation rows.**

**✅ Deliverables**
- Full ticket lifecycle works in Postman: create → assign → reply → pause → resume → resolve → reopen
- Illegal transitions return `409` with the allowed set
- `BusinessHours` passes all five property tests, including across a DST boundary and a holiday
- SLA clocks pause and resume correctly; elapsed time is derived, never stored
- The poller fires each rung exactly once and survives an application restart with no lost escalations
- All four concurrency tests green
- `GET /sla/at-risk` returns ranked predictions with a stated basis

**⛓️ Dependencies**
Phase 4 (auth, roles, tenant filter, seeded users and teams).

**⏱️ Estimated Effort**
**8–11 days.** The single largest phase. Budget 4 days for ticketing, 5 for the SLA engine, 1–2 for the concurrency tests. Add 2 days if timezone arithmetic is new to you — it always takes longer than expected.

**⚠️ Watch Out For**
1. **Mapping `sla_clock_segment` as a cascading `@OneToMany` you mutate.** Closing one segment and opening another through a managed collection invites Hibernate to reorder the statements and trip `uq_segment_open`. **This is the single most likely source of a mysterious constraint violation in this phase.** Insert and update explicitly.
2. **Treating business hours as "just add four hours".** Friday 16:30 + 4 business hours, with an 18:00 close, a weekend, and a Monday holiday, lands on Tuesday 11:30. Write the property tests first; they will find the bug you were going to ship.
3. **Application clocks instead of `NOW()`.** Every timestamp must come from Postgres. With two instances polling the same deadlines, clock skew makes "is this due?" answerable differently on different instances.
4. **Writing the concurrency tests last, or not at all.** They are the highest-value artifact in the project — the thing you demo in an interview. Write them in this phase while the code is fresh.

---

## PHASE 6 — Async Pipeline & AI Triage

**🎯 Goal**
Ticket creation returns `202` immediately; a transactional outbox and `SKIP LOCKED` workers drive triage asynchronously; the LLM emits structured signals and a versioned deterministic policy computes priority and routing.

**📋 Tasks**
1. Create the `OutboxEvent` entity and repository with a **native** claim query (`FOR UPDATE SKIP LOCKED`) — not JPA-managed.
2. Implement `OutboxPublisher`: write the event in the same transaction as the domain change.
3. Implement the generic `WorkerRuntime`: scheduled poll, claim batch, dispatch onto virtual threads, mark `DONE` / retry with capped exponential backoff and jitter / `DEAD` after max attempts.
4. Implement the outbox reaper for expired `locked_until`.
5. Change `POST /tickets` to write ticket + outbox event in one transaction and return `202` with `analysisStatus: PROCESSING`.
6. Implement `GET /tickets/{id}/analysis` returning `PROCESSING` / `READY` / `UNAVAILABLE`.
7. Implement **PII redaction**: regex plus entity detection for email, phone, card, government-ID-shaped strings, IP and order references; write the encrypted placeholder map; rehydrate on read.
8. Create `PromptVersion` entity; seed the `triage@1` prompt with its JSON output schema.
9. Define the `TriageSignals` record and wire Spring AI `ChatClient` with `BeanOutputConverter`, temperature 0.
10. Implement `TriageWorker`: budget check → policy check → redact → embed (cached by content hash) → LLM call → persist `AiAnalysis`.
11. Implement `ModelRouter`: cheap model default, escalation on parse failure, fallback provider on 5xx, local Ollama when `external_model_allowed=false`.
12. Wrap provider calls in a Resilience4j circuit breaker.
13. Implement per-tenant budget enforcement → `BUDGET_HELD` status and an admin notification, never silent overspend.
14. **Implement `PriorityPolicy` as a pure function** with a versioned rule list, returning priority plus rationale. Table-driven unit tests, zero LLM calls.
15. Implement `RoutingPolicy`: team by skills overlap, then `claimLeastLoadedAgent` with `FOR UPDATE SKIP LOCKED` on `agent_profile`.
16. Persist `PriorityDecision` with all inputs and the rationale; implement `GET /tickets/{id}/priority-rationale`.
17. Implement `POST /tickets/{id}/priority-override` writing a labelled `PriorityOverride`.
18. Wire SLA start into the triage transaction, so clocks begin when priority is known.
19. Implement `POST /tickets/{id}/retriage`.
20. Set up WireMock fixtures so every AI test is deterministic, free and offline.
21. **Test: 50 tickets, 5 agents, assert max−min agent load ≤ 1** — the least-loaded-assignment race.
22. **Test: two workers claim the same event → one `AiAnalysis` row, one LLM call.**
23. **Test: kill a worker mid-stage → the event is redelivered and produces no duplicate side effects.**

**✅ Deliverables**
- `POST /tickets` returns `202` in under 150 ms; analysis appears within ~15 s
- Outbox events retry with backoff and land in the DLQ after max attempts
- PII is redacted before any external call; placeholders rehydrate correctly
- Priority is computed by the policy, is explainable, and is unit-tested without any model call
- Provider failure degrades to `UNAVAILABLE` with the ticket still fully workable
- Budget exhaustion produces `BUDGET_HELD`, not overspend
- All three concurrency/reliability tests green

**⛓️ Dependencies**
Phase 5 (tickets exist, SLA engine can be started from the triage transaction).

**⏱️ Estimated Effort**
**6–8 days.** Budget 2 days for the outbox and worker runtime, 2 for triage and the model router, 2 for the policies, 1–2 for PII and tests.

**⚠️ Watch Out For**
1. **Claiming outbox rows through JPA.** Hibernate's dirty checking and first-level cache actively fight a queue claim. Use `JdbcTemplate` or a native query, and keep the claiming transaction to a few statements.
2. **Letting the LLM return a priority.** The moment `TriageSignals` gains a `priority` field, the entire architectural argument of this project collapses. Signals describe observations; the policy makes decisions. Guard this boundary in code review with yourself.
3. **Live LLM calls in tests.** They make CI slow, flaky and expensive. Record fixtures with WireMock in this phase, not later — retrofitting means re-recording every fixture.
4. **Forgetting the `attempt` field in the `ai_analysis` unique constraint.** A retry after a parse failure legitimately needs a second row; without `attempt` in the key it collides with the first.

---

## PHASE 7 — Knowledge, Retrieval & Citation-Enforced Drafting

**🎯 Goal**
A knowledge base with hybrid retrieval, and resolution drafts decomposed into individually-verified claims where unsupported claims are dropped and weak drafts are suppressed entirely.

**📋 Tasks**
1. Create `KnowledgeDocument` and `KnowledgeChunk` entities with `vector(768)` and `tsvector` columns.
2. Implement `POST /knowledge/documents` → `202`, plus an indexing outbox event.
3. Implement `IndexWorker`: chunk (target ~400 tokens with ~50 overlap, splitting on headings then sentences), compute `char_start`/`char_end`, embed with caching by content hash, insert chunks, set `indexed_at`.
4. Implement `content_sha256`-based skip so reindexing only touches changed documents.
5. **Implement hybrid retrieval as a single SQL statement**: a CTE with the `tsvector` ranking, a CTE with the pgvector cosine ranking, fused with Reciprocal Rank Fusion, tenant pre-filtered **before** both scans, with a source-tier boost.
6. Implement `GET /knowledge/search?explain=true` returning the per-result score breakdown — this is your retrieval debugger.
7. Build the **retrieval eval suite**: 60–80 labelled `(query, relevant chunk ids)` cases; compute Recall@5, Precision@5, MRR and nDCG@10.
8. Tune `hnsw.ef_search` **against the eval suite**, not by guessing. Record the before/after numbers.
9. Seed `draft@1` and `entailment@1` prompt versions with their schemas.
10. Implement `POST /tickets/{id}/drafts` → `202` + outbox event.
11. Implement `DraftWorker`: retrieve top-k → build context → **generate claims, not prose** (`{claims: [{text, citations[]}]}`).
12. **Implement the deterministic numeric pre-filter**: extract every numeral, currency amount, date, order ID and error code from a claim; if any is absent from its cited span, mark `FAILED_NUMERIC_CHECK` and drop it. **Zero LLM cost, catches most fabrication.**
13. Implement the entailment verifier: a cheap model given only the claim and its cited span, returning `SUPPORTED` / `PARTIAL` / `NOT_SUPPORTED`. Cache verdicts by `sha256(claim + span)`.
14. Implement coverage computation and the suppression rule: below 0.8, set `SUPPRESSED_LOW_COVERAGE`, return `assembledText: null` and `recommendation: ESCALATE_TO_HUMAN`.
15. Implement `unresolvedAspects` extraction and persistence.
16. Implement `GET /drafts/{id}` returning every claim including dropped ones with their rejection reasons.
17. Implement `POST /drafts/{id}/action` with **server-computed** Levenshtein edit distance.
18. Build the **grounding eval suite**: ~150 hand-labelled claims; compute **claim-level** groundedness and citation precision, and report response-level alongside it.
19. **Build the refusal suite**: 20 tickets with no knowledge-base coverage where the correct behaviour is suppression. The gate requires 100%.
20. Wire all eval suites into `mvn verify` so CI fails on regression against a committed baseline.

**✅ Deliverables**
- Documents index asynchronously; reindex skips unchanged documents and reports the saving
- Hybrid search returns results with a visible lexical/vector/RRF score breakdown
- Retrieval eval reports Recall@5 and MRR; `ef_search` tuned with before/after numbers recorded
- Drafts return per-claim verdicts; fabricated numbers are caught by the free pre-filter
- Low-coverage drafts are suppressed with `assembledText: null`
- Claim-level groundedness measured and reported next to response-level
- **The refusal suite passes at 100% and the CI gate enforces it**

**⛓️ Dependencies**
Phase 6 (outbox, workers, model router, prompt versioning, PII redaction).

**⏱️ Estimated Effort**
**6–8 days.** Budget 2 days for ingestion and chunking, 2 for hybrid retrieval and its eval, 3 for drafting and verification, 1 for the CI gate. **Add a day for building the labelled sets** — it is tedious and easy to underestimate.

**⚠️ Watch Out For**
1. **Post-filtering by tenant after the vector scan.** If the `WHERE tenant_id` runs after the top-k, your effective `k` silently shrinks and recall drops in a way that is invisible without an eval suite. Pre-filter.
2. **Measuring groundedness per response instead of per claim.** Response-level averaging hides unsupported claims inside otherwise-good answers — the documented case where a metric read 0.92 while a claim-level audit found 30% hallucination. Report both; the gap is the finding.
3. **Building the labelled sets "later".** Without them you cannot tune `ef_search`, cannot gate CI, and cannot answer *"how do you know your AI works?"* — which is the question that decides whether the AI half of this project counts.
4. **Skipping the numeric pre-filter because the entailment check exists.** The pre-filter is free, deterministic and catches the most damaging failure mode (invented amounts and dates). Run it first.

---

## PHASE 8 — Incident Correlation

**🎯 Goal**
Bursts of semantically related tickets are detected behind a deterministic statistical gate, proposed for human confirmation, and resolved with one action that fans out idempotently to every linked ticket.

**📋 Tasks**
1. Create `Incident`, `IncidentTicket`, `IncidentUpdate`, `IncidentUpdateDelivery` entities.
2. Implement deterministic entity extraction from ticket text: error codes, service names, regions, payment methods, app versions.
3. Implement the clustering pass: over a 30-minute window of open tickets, cluster by embedding cosine ≥ τ, boosted by shared entities.
4. Implement the **arrival-rate baseline**: mean tickets for the same weekday and hour over the previous 4 weeks.
5. **Implement `CorrelationGate` as a pure function** — propose only when `clusterSize ≥ K` **and** `arrivalRate > M × baseline` **and** `window ≤ 30 min`. Unit-test the boundaries.
6. Implement the correlation sweep worker on a 60-second schedule, guarded by a Redis lock that **fails closed**.
7. Implement incident title and summary generation with the LLM, with a **template fallback** when it is unavailable, recording `generated_by_model` (null when templated).
8. Persist the gate's evidence — `cluster_size_at_detection`, `arrival_rate_multiple`, `first_ticket_at`, `detected_at`.
9. Implement `GET /incidents` and `GET /incidents/{id}` including the `detection` object and `timeToDetectSeconds`.
10. Implement `POST /incidents/{id}/confirm`: link tickets, **pause resolution clocks, leave first-response clocks running**, write an eval label.
11. Implement `POST /incidents/{id}/reject` with a mandatory reason, also written as an eval label.
12. Implement manual link and detach; detaching restores the ticket's own clock — trivially correct because segments are append-only.
13. Implement `POST /incidents/{id}/updates`: write the update plus N delivery rows plus N outbox events in one transaction.
14. Implement `FanoutWorker`: per-ticket delivery, idempotent on `uq_delivery`, individually retryable.
15. Implement `GET /incidents/{id}/updates/{uid}/deliveries` with the summary and failure list.
16. Implement `POST /incidents/{id}/resolve` resolving the incident and all linked tickets.
17. **Write `demo/storm.sh`** — posts 38 correlated tickets over 20 seconds with realistic varied wording.
18. **Test: a ticket cannot join two live incidents** (`uq_incident_ticket_live`).
19. **Test: a ticket is merged while an agent is editing it → `409`.**
20. **Test: one delivery fails → it retries, the other 37 are unaffected and not re-sent.**
21. **Measure and record time-to-detect** on the seeded storm.

**✅ Deliverables**
- `./demo/storm.sh` produces a proposed incident within 90 seconds, with the gate evidence visible
- A borderline burst that fails the rate gate produces **no** incident — tested explicitly
- Confirm links all tickets and pauses the right clocks
- One update fans out to N tickets with per-ticket delivery status
- Detach restores the ticket's own SLA correctly
- All three tests green
- Time-to-detect recorded

**⛓️ Dependencies**
Phase 6 (ticket embeddings exist from triage), Phase 7 (embedding infrastructure and caching).

**⏱️ Estimated Effort**
**5–6 days.** Budget 2 days for clustering and the gate, 1 for the incident lifecycle, 1.5 for fan-out, 1 for the demo script and tests.

**⚠️ Watch Out For**
1. **Letting the LLM decide that an incident exists.** It writes the title. The gate decides. If you find yourself prompting *"is this an incident?"*, stop — you have just thrown away the feature's central argument.
2. **Tuning τ and K by intuition.** Use the three planted storms in the seed corpus plus deliberately *uncorrelated* bursts as a test set, and tune against measured precision and recall. An incident feature with false positives is worse than none.
3. **Fan-out as one transaction over N tickets.** A single failure then rolls back all N and re-sends to everyone on retry. N independent delivery rows and N independent events is the entire point.
4. **Forgetting the baseline is per weekday and hour.** A global average makes Monday 10:00 look like a permanent incident and Sunday 03:00 never trigger.

---

## PHASE 9 — Testing, Evaluation & Hardening

**🎯 Goal**
A test suite that proves the hard claims, evaluation gates wired into CI, and the security controls from [02 §13](02-PROJECT-PLAN.md) verified rather than assumed.

**📋 Tasks**
1. Consolidate every concurrency test from Phases 5, 6 and 8 into one tagged suite that runs in CI.
2. Complete the jqwik property suite for `BusinessHours`, including DST transition and holiday-spanning cases.
3. Complete the parameterised cross-tenant test across **every** endpoint that now exists.
4. Write the role-matrix test: every endpoint × every role → expected status.
5. Write the idempotency tests: same key + same body → identical response; same key + different body → `422`.
6. Write the state-machine test: every illegal transition returns `409`.
7. Complete and baseline all five eval suites — classification, retrieval, grounding, refusal, incident.
8. Wire the eval gate into `mvn verify` and the CI workflow, failing on regression against the committed baseline.
9. Security pass: verify the body-length cap, the `size` clamp, magic-byte attachment verification, the CORS allow-list with no wildcard, algorithm pinning on JWT, and that no ticket body or PII appears in any log.
10. Add `gitleaks` to CI.
11. Add per-tenant rate limiting to the endpoints listed in [05 §4](05-API-CONTRACT.md).
12. Implement the nightly prune job for `DONE` outbox rows older than 7 days.
13. Implement the nightly reconciliation of `agent_profile.open_count` against the actual count.
14. Review every `@Transactional` boundary — no LLM call inside a transaction, no lock held across a network call.
15. Run a manual chaos pass: kill Redis, kill the LLM provider, kill a worker mid-stage; verify the documented degraded behaviour in each case.

**✅ Deliverables**
- Full test suite green in CI, including all concurrency tests
- All five eval suites baselined; the refusal suite at 100%
- CI fails on a deliberately introduced accuracy regression — **verify this by actually breaking a prompt**
- Cross-tenant and role-matrix suites cover every endpoint
- `gitleaks` green
- Chaos pass documented with observed behaviour per failure

**⛓️ Dependencies**
Phase 8 (all features exist).

**⏱️ Estimated Effort**
**5–6 days.**

**⚠️ Watch Out For**
1. **Not verifying the CI gate actually fails.** A gate that has never failed is a gate you do not know works. Deliberately degrade a prompt, watch the build go red, then revert.
2. **Flaky concurrency tests.** Use `CountDownLatch` to release all threads simultaneously rather than `Thread.sleep`. A flaky test gets `@Disabled` within a week and then it protects nothing.
3. **Skipping the chaos pass because everything "should" work.** This is where you find out that the Redis failure posture you documented is not the one you implemented.

---

## PHASE 10 — Deployment, Observability & Documentation

**🎯 Goal**
A deployed, seeded, observable instance with a README that makes the engineering visible to someone who will never run the code.

**📋 Tasks**
1. Write the multi-stage Dockerfile: Maven build stage, JRE-slim runtime, non-root user, target under 250 MB.
2. Finalise `docker-compose.yml` for the complete stack.
3. Extend the CI workflow: build → unit → Testcontainers → **eval gate** → Docker image build.
4. Provision Railway Postgres and Redis; **verify `CREATE EXTENSION vector` on the managed instance**.
5. Create the R2 bucket and credentials.
6. Configure every environment variable from [02 §11](02-PROJECT-PLAN.md).
7. Deploy; watch Flyway apply cleanly; confirm `/actuator/health` is `UP` with the outbox-lag indicator.
8. Run the seed script against production.
9. Deploy the frontend; update `CORS_ALLOWED_ORIGINS`; redeploy.
10. Smoke-test every Postman folder against the live URL.
11. Configure Micrometer metrics: stage latency histograms, queue depth, oldest pending event age, tokens and cost per tenant, cache hit rates, STP rate, lock wait time.
12. Build the Grafana dashboard and **screenshot it** — do not pay to host it.
13. Run a k6 load test: sustained tickets/minute, p95 stage latency, queue depth under burst.
14. **Record the three demo GIFs**: the storm collapsing 38 tickets into 1; an SLA escalation ladder firing on a fast-forwarded clock; a draft with a claim dropped by the verifier.
15. **Fill in every `[benchmark after implementation]`** in [09 — Numbers](#) and in the resume bullets, from real measurements.
16. Write the final README per the structure in `RESOLVEAI-ENHANCED-PLAN.md` §8 Week 8 — including "why there's no Kafka", "why I didn't semantically cache generations", and "the decision I'd change".
17. Write the architecture decision records for the five choices you will be asked about.

**✅ Deliverables**
- Live deployed instance with the seeded demo, reachable by a URL in the README
- CI green end to end including the eval gate
- Grafana dashboard screenshotted with real data
- Load test results recorded
- Three demo GIFs in the README
- **Every benchmark placeholder replaced with a measured number**
- README complete

**⛓️ Dependencies**
Phase 9 (everything tested).

**⏱️ Estimated Effort**
**4–5 days.** Add 1 day if this is your first non-Heroku deployment.

**⚠️ Watch Out For**
1. **Discovering pgvector is unavailable on the managed instance.** Verify it in Phase 1 *and* again here, before deploying anything else.
2. **Treating the README as an afterthought.** It is worth as much as Phase 7. Most reviewers will read it and never run the code; it is the only artifact that reaches everyone.
3. **Inventing numbers because a benchmark is inconvenient.** Every placeholder either gets a real measurement or stays a placeholder. A fabricated metric is the one mistake in this project that cannot be recovered from in an interview.

---

## Project Summary

| Phase | Name | Effort | Depends On |
|---|---|---|---|
| 1A–1E | Setup, domain definition & starter corpus | **6–7 days** ⚠ | None |
| ★ 1F | Full corpus, storms, eval labels *(run before Phase 8)* | **3–4 days** | Phase 1E |
| 2 | System & Database Design *(docs done; migrations + contracts outstanding)* | **9 days** ⚠ | Phase 1 |
| 3 | Project Setup & Boilerplate | 3–4 days | Phase 2 |
| 4 | Authentication & User Management | **10–11 days** ⚠ | Phase 3 |
| 5A | **Ticketing** | **10 days** ⚠ | Phase 4 |
| 5B | **SLA Engine** | **7 days** ⚠ | Phase 5A |
| 5C | **Concurrency tests & close** | **2–3 days** ⚠ | Phase 5B |
| 6A | Async pipeline infrastructure | **6 days** ⚠ | Phase 5C |
| 6B | AI safety, governance & LLM plumbing | **7 days** ⚠ | Phase 6A |
| 6C | Triage worker & policy engines | **5 days** ⚠ | Phase 6B |
| 6D | Tests & close | **3 days** ⚠ | Phase 6C |
| 7A+7B | Knowledge ingestion & hybrid retrieval | **9 days** ⚠ | Phase 6 |
| 7C–7E | Citation-enforced drafting & eval | **11 days** ⚠ | Phase 7B |
| 8A–8E | **Incident Correlation** | **19–20 days** ⚠ | Phase 6 *(not 7 — see [12](12-TASK-BREAKDOWN-PHASE-8.md))* |
| ★ | **Early deploy slice** *(run right after 6A)* | **3 days** | Phase 6A |
| 9+10 | Testing, Hardening, Deployment & Docs | **19–20 days** ⚠ *(merged — most tests written inline in 4–8)* | Phase 8 |
| | *(frontend, interleaved from Phase 5)* | *+6–8 days* | |
| **TOTAL (full scope)** | | **86–100 working days** | |
| **TOTAL — FULL SCOPE (chosen)** | | **~135 working days ≈ 27–28 weeks** | |

*Contingency totals, kept only as a pre-agreed cut order if you fall behind: dropping Phase 7C–7E → ~124 days; dropping all of Phase 7 → ~115 days.*

> ⚠ **Phases 4, 5, 6 and 8 were each re-estimated after being broken into tasks** ([09](09-TASK-BREAKDOWN-PHASE-4.md), [10](10-TASK-BREAKDOWN-PHASE-5.md), [11](11-TASK-BREAKDOWN-PHASE-6.md), [12](12-TASK-BREAKDOWN-PHASE-8.md)).
>
> | Phase | Original | Broken down | Factor |
> |---|---|---|---|
> | 4 | 4–5 d | 10–11 | 2.2× |
> | 5 | 8–11 d | 19–23 | 2.2× |
> | 6 | 6–8 d | 21 | 2.8× |
> | 8 | 5–6 d | 19–20 | 3.0× |
> | 9+10 | 9–11 d | 19–20 | 1.7× |
> | 1 | 2–3 d | 9–10 | 3.5× |
> | 7 | 6–8 d | 20 | 2.7× |
> | 2 | 3–4 d | 9 | ~2.5× *(docs done; migrations, contracts and design review were not)* |
>
> **Every phase is now broken down** ([08](08-TASK-BREAKDOWN-PHASE-3.md)–[16](16-TASK-BREAKDOWN-PHASE-2.md)): **241 tasks, 100% of the project planned at task level.** Eight consecutive misses is systematic, not noise: **the phase-level numbers counted features and not the tests, DTOs, error paths, governance and plumbing between them.** Nothing is left at estimate level.
>
> **Cutting Phase 7 saves only 14 days out of 110, so descoping is not the answer.** [12 §The reckoning](12-TASK-BREAKDOWN-PHASE-8.md) has the one that is: **ship incrementally.** Deploy a minimal version right after Phase 6A, and add a resume bullet at each checkpoint — two bullets by week 9, three by week 11, four by week 15, all five by week 19.

### Realistic total timeline

At 3–4 focused hours a day: **86–100 working days ≈ 18–21 calendar weeks at full scope**, or **~22–23 weeks on Path C**. Cutting Phase 7 saves only 14 days out of 110 — **it was never the answer**. See [12 §The reckoning](12-TASK-BREAKDOWN-PHASE-8.md) for the reframe that is: ship incrementally and update the resume at each checkpoint, deploying a minimal version after Phase 6A so there is always a live demo.

**This is not an 8-week project at 3–4 hours a day. Full scope is 18–21 weeks, and being honest about that now is better than discovering it in Week 7.** The 8-week figure in [01](01-IDEA-ENHANCER.md) assumes ~6 focused hours a day. Pick which one matches your actual availability and plan accordingly.

### Critical path

```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10
```

Every phase is on the critical path — there is no parallel track, because a solo developer has no parallelism. **The frontend is the only interleavable work**: start Tier 1–2 of [06 §11](06-UI-UX-DESIGN.md) once Phase 5 gives you real ticket endpoints, and use it as a change of pace when backend work stalls.

### Highest-risk phase

**Phase 5, without contest**, and for three compounding reasons:

1. **It is the longest** (8–11 days), so an overrun here cascades into everything.
2. **Business-hours arithmetic is deceptively hard.** DST, holidays, tenant-specific working weeks, and targets longer than a working day. Every one of those is a bug you will only find with property tests.
3. **The `sla_clock_segment` mapping trap** ([04 §Step 8 trap 3](04-DATABASE-SCHEMA.md)) produces a constraint violation whose cause is genuinely non-obvious, and it will cost a day if you hit it without knowing what to look for.

**Second-highest: Phase 7**, because the labelled evaluation sets are tedious, easy to defer, and without them the entire AI half of the project has no evidence behind it.

### Why Phase 5 comes before any AI

The most important scheduling decision in this document:

> The SLA engine is the hardest deterministic work and the most likely to slip. Building it first means that **if everything after it goes wrong, you still have a project with a genuinely hard backend core** — and a 45-minute technical conversation that works even with an interviewer who does not care about LLMs.
>
> Build AI first and the schedule risk lands on the one part you cannot cut.

### If you fall behind

Cut in this order:

1. **Cut Phase 7 entirely** (retrieval and drafting). Counter-intuitive, but correct — it is also the most common thing in everyone else's portfolio, so it is the cheapest thing to lose.
2. Cut the admin screens from [06 §11](06-UI-UX-DESIGN.md) Tier 5, except the Eval Dashboard.
3. Cut the customer portal; demo with an agent creating tickets via `onBehalfOf`.
4. **Never cut:** the concurrency tests, the eval gate, the README, or the deployed demo. Those four are what the project is *for*.

**Phases 1–6 + 8 + 9 + 10 is still a strong project** — async triage with deterministic priority, a real SLA engine, and incident correlation, all tested and deployed.

---

## Next Steps

> 📋 **Phase breakdown complete!**
>
> **Your immediate next step:**
> Phase 2 is already done — documents [03](03-SYSTEM-ARCHITECTURE.md), [04](04-DATABASE-SCHEMA.md), [05](05-API-CONTRACT.md) and [06](06-UI-UX-DESIGN.md) are its output. You are therefore ready to start **Phase 3 — Project Setup & Boilerplate**.
>
> Use the **task-breakdown skill** on Phase 3 to get your daily task list → [08 — Task Breakdown: Phase 3](08-TASK-BREAKDOWN-PHASE-3.md).
>
> **Re-run `task-breakdown` at the start of every subsequent phase.** One phase at a time — a task list for Phase 7 written today would be obsolete by the time you reach it.
