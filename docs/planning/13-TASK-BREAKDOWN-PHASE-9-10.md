# 13 — Task Breakdown: Phases 9 + 10 (merged)

> Skill: `task-breakdown` · **The final breakdown.** Every phase is now planned at task level.

**Phases:** 9 — Testing, Evaluation & Hardening · 10 — Deployment, Observability & Documentation
**Goal:** A tested, hardened, deployed, observable, measured and documented system — with a live URL and a README that makes the engineering visible to someone who will never run the code.
**Tech stack:** Docker · GitHub Actions · Railway · Cloudflare R2 · Micrometer/Prometheus/Grafana · k6 · ArchUnit · gitleaks

---

## Why these are merged

**Most of Phase 9 is already done.** Written inline across Phases 4–8:

| Phase 9 item ([07](07-DEV-PHASES.md)) | Status |
|---|---|
| Concurrency test suite | ✅ Written in 5C, 6D, 8E — needs tagging only |
| `BusinessHours` property suite | ✅ 5B Tasks 20–22, including DST and holidays |
| Cross-tenant test across every endpoint | ✅ 4 Task 19, appended in every phase since |
| Idempotency tests | ✅ 5A Task 7 |
| State-machine test | ✅ 5A Task 5 (64-case cross product) |
| Eval suites + CI gate | ⚠️ Classification only (6D Task 34); retrieval/grounding/refusal belonged to the **cut** Phase 7 |
| Role-matrix test | ❌ Not written |
| Security pass | ❌ Not done |
| Nightly operational jobs | ❌ Not done |
| Chaos pass | ❌ Not done |

What remains of Phase 9 is roughly a third of what [07](07-DEV-PHASES.md) listed, and it interleaves naturally with productionisation. Running them as one block avoids deploying twice.

---

## ⚠️ Final estimate, and the complete picture

**Phases 9+10 combined: 59–63 hours ≈ 17 days.** ([07](07-DEV-PHASES.md) said 9–11 combined. Fifth consecutive miss, ~1.7× — the smallest factor yet, because so much was already written.)

**Plus a 3-day early-deploy slice that happens right after Phase 6A** — see below.

### The whole project, now planned at task level

| Phase | Days | Source |
|---|---|---|
| 1 — Setup | 4–5 | estimated |
| 2 — Design | *done* | this folder |
| 3 — Boilerplate | 7 | [08](08-TASK-BREAKDOWN-PHASE-3.md) |
| 4 — Auth | 11 | [09](09-TASK-BREAKDOWN-PHASE-4.md) |
| 5 — Ticketing + SLA *(lean)* | 23 | [10](10-TASK-BREAKDOWN-PHASE-5.md) |
| 6 — Async + AI triage *(lean)* | 18 | [11](11-TASK-BREAKDOWN-PHASE-6.md) |
| **★ Early deploy** *(after 6A)* | **3** | **this doc** |
| 7 — Knowledge & drafting | **CUT** | — |
| 8 — Incident correlation | 20 | [12](12-TASK-BREAKDOWN-PHASE-8.md) |
| 9+10 — Hardening, deploy, docs | 17 | this doc |
| **TOTAL** | **~103 days ≈ 21–22 weeks** | |

**~96 of those 103 days are now planned at task level**, so unlike the original 46–60 this number is one you can actually hold someone to. It has not moved because the estimates got worse — it moved because they got honest.

### The framing that makes 21 weeks workable

From [12 §The reckoning](12-TASK-BREAKDOWN-PHASE-8.md), and it is the single most important thing in this folder:

> **You do not need a finished project to apply. You need a strong project on the day you apply.**

| Milestone | Week | Resume bullets |
|---|---|---|
| 5C | 9 | 2 — SLA engine + concurrency. **Already beats the e-commerce project.** |
| **★ Early deploy** | **11** | **2 + a live URL and a written architecture story** |
| 6D | 15 | 4 |
| 8 | 20 | 5 |
| 9+10 | 22 | 5, benchmarked and polished |

### If you have less time than that

| You have | Ship |
|---|---|
| **9 weeks** | Phases 1–5C + the early deploy slice. Ticketing, a real SLA engine, concurrency tests, live. **Two strong bullets.** |
| **12 weeks** | + Phase 6A–6C. Async pipeline, AI triage, deterministic policy. **Four bullets.** |
| **16 weeks** | + Phase 8 lean (skip 8C's resolve endpoint and 8D's delivery-status UI). **Five bullets.** |
| **22 weeks** | Everything here. |

---

# ★ EARLY DEPLOY SLICE — run immediately after Phase 6A

Five tasks, ~3 days. Do not defer these to the end.

**Why:** from week 11 onward you always have a live URL and a written architecture story — which is what a reviewer actually looks at — and you build everything afterwards against a deployed instance rather than discovering deployment problems in week 20. The pgvector-on-managed-Postgres question in particular must be answered early; if the extension is unavailable, the entire retrieval and correlation design needs rethinking and you want to know in week 11, not week 19.

## TASK E1 — Write the multi-stage Dockerfile

**📝 Description**
Stage 1 `maven:3.9-eclipse-temurin-21` runs `mvn -B package -DskipTests` with a separate `dependency:go-offline` layer so dependency downloads cache. Stage 2 `eclipse-temurin:21-jre-alpine`, **non-root user**, `COPY --from=build`, `ENTRYPOINT`.

Add `.dockerignore` (target, node_modules, .git, .env). Target under 250 MB.

Set container-aware JVM flags: `-XX:MaxRAMPercentage=75 -XX:+UseG1GC`. Without `MaxRAMPercentage` the JVM sizes its heap from the *host's* memory and gets OOM-killed by the container limit — a first-deploy classic.

**⛓️ Dependencies** Phase 6A.
**✅ Expected Output** `docker build` produces an image under 250 MB that runs as non-root and starts against the Compose stack.
**⏱️** 2 hours.

---

## TASK E2 — Provision managed Postgres and Redis, verify pgvector

**📝 Description**
Provision Railway Postgres and Redis. **Connect with psql and run `CREATE EXTENSION IF NOT EXISTS vector;` before anything else.** Also verify `pg_trgm`.

Check the connection limit on your tier and confirm `HikariCP max-pool-size × instances` stays comfortably under it.

Note the Postgres version — HNSW indexes need pgvector 0.5+. If Railway ships an older pgvector, `ivfflat` is the fallback and you need to know now.

**⛓️ Dependencies** E1.
**✅ Expected Output** Both extensions created on the managed instance. Connection limit recorded in the README. Migrations apply cleanly from empty.
**⏱️** 2 hours.

---

## TASK E3 — First deploy, seed and smoke test

**📝 Description**
Set every environment variable from [02 §11](02-PROJECT-PLAN.md). Deploy the image with `SPRING_PROFILES_ACTIVE=prod`. Watch Flyway apply all migrations. Confirm `/actuator/health` is `UP` with db, redis and outboxLag green.

Run the seed script against production. Smoke-test the Postman Auth and Tickets folders against the live URL.

**Expect this to fail two or three times.** The usual causes: a missing env var, `DATABASE_URL` in a format the driver does not accept, or the container OOM-killing on startup. Budget for it.

**⛓️ Dependencies** E2.
**✅ Expected Output** Live URL serving authenticated requests against seeded data.
**⏱️** 2.5 hours.

---

## TASK E4 — Add image build to CI

**📝 Description**
Extend the Phase 3 workflow: after `mvn verify`, build the Docker image and push to GHCR on `main`. Tag with the short SHA and `latest`. Add the image-size check as a soft warning.

**⛓️ Dependencies** E1, Phase 3 Task 14.
**✅ Expected Output** A push to `main` produces a tagged image in GHCR. CI stays under 10 minutes.
**⏱️** 1 hour.

---

## TASK E5 — Write README v0

**📝 Description**
Not the final README — the version that makes the project reviewable *now*:
1. The problem, in one paragraph, with the evidence it is real
2. The architecture diagram from [03](03-SYSTEM-ARCHITECTURE.md)
3. **The live URL and demo credentials**
4. What is built so far, and what is coming — stated plainly
5. One-command local run

**Point 4 matters.** A README that describes the finished system when half of it does not exist reads as dishonest the moment someone clicks. *"Built: ticketing, SLA engine, async pipeline. In progress: AI triage, incident correlation."* reads as a project under active development, which is exactly what it is.

**⛓️ Dependencies** E3.
**✅ Expected Output** Someone who has never seen the repo can understand the problem, see the architecture, and click into a working demo within two minutes.
**⏱️** 2 hours.

---

# PHASE 9A — Test Consolidation & Coverage

## TASK 1 — Tag and consolidate the concurrency suite

**📝 Description**
Every concurrency test written in 5C, 6D and 8E gets `@Tag("concurrency")`. Add a Maven profile `-Pconcurrency` that runs only those, and include the tag in the main CI run.

Write `ConcurrencyTestSuite` as a JUnit 5 `@Suite` listing all of them with a one-line comment each. **This class is the index you open in an interview** — "here are the eleven races this system handles, and here is the test for each."

Run the tagged suite 20 times consecutively. **Any test that flakes even once gets fixed, not retried** — a flaky concurrency test gets `@Disabled` within a month and then protects nothing.

**⛓️ Dependencies** Phases 5C, 6D, 8E.
**✅ Expected Output** 20 consecutive green runs. The suite class lists every race with its mechanism.
**⏱️** 1.5 hours.

---

## TASK 2 — Audit cross-tenant coverage against the endpoint inventory

**📝 Description**
Extract every `@RequestMapping` in the codebase via reflection or ArchUnit, and **assert the Phase 4 `@MethodSource` covers all of them.** A missing endpoint fails the build.

That test is the real deliverable: without it, the cross-tenant suite silently stops covering new endpoints, which is exactly how the hole reopens.

Then fill any gaps found, and add a second assertion for **native queries specifically** — `@TenantId` does not apply to native SQL, and the queue query, hybrid search and poller all use it.

**⛓️ Dependencies** Phase 4 Task 19.
**✅ Expected Output** The coverage-of-coverage test passes. Every endpoint is exercised cross-tenant. A deliberately added unguarded endpoint fails the build.
**⏱️** 2 hours.

---

## TASK 3 — Write the role-matrix test

**📝 Description**
The complement of the cross-tenant suite: that one asks *"can A see B's data"*; this asks *"can this role perform this action"*.

A `@ParameterizedTest` over **(endpoint, role) → expected status** for all four roles across every endpoint — roughly 200 cases from a declarative table:

```java
row("POST", "/api/v1/tickets/{id}/assign", CUSTOMER,   403),
row("POST", "/api/v1/tickets/{id}/assign", AGENT,      200),
row("POST", "/api/v1/incidents/{id}/confirm", AGENT,   403),
row("POST", "/api/v1/incidents/{id}/confirm", TEAM_LEAD, 200),
```

**Write the table by hand from [05](05-API-CONTRACT.md), not by reading the annotations.** Generating expectations from the code under test proves only that the code agrees with itself. The table is the specification; the test checks the code against it.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** ~200 cases green. Removing a `@PreAuthorize` fails the build.
**⏱️** 3 hours.

---

## TASK 4 — Consolidate the eval suites for Path C scope

**📝 Description**
With Phase 7 cut, three of the five planned suites do not apply. What remains:

| Suite | Status | Action |
|---|---|---|
| `CLASSIFICATION` | Built (6D T34) | Expand 60 → 100 cases; add macro-F1 per category |
| `INCIDENT` | Partial (8E T25) | Formalise as an eval suite: the three negative scenarios plus the positive, with precision/recall |
| `RETRIEVAL` / `GROUNDING` / `REFUSAL` | Phase 7 | **Remove the enum values and the stubs.** Dead scaffolding for features that do not exist reads worse than never having planned them. |

Update `GET /admin/eval/runs` to show the two live suites. Update the README to state which suites exist and why the others do not — *"citation-enforced drafting was descoped; the retrieval and grounding suites went with it"* is a fine sentence.

**⛓️ Dependencies** Phases 6D, 8E.
**✅ Expected Output** Two suites running in CI with committed baselines. No dead enum values or stub classes.
**⏱️** 2 hours.

---

## TASK 5 — Coverage gap review

**📝 Description**
Run JaCoCo. **Do not chase a percentage** — instead, review the report for *categories* of untested code:

- Every `catch` block: is the recovery path tested, or only the happy path?
- Every `@Scheduled` method: is it invoked by a test?
- Every custom exception: is it thrown somewhere a test asserts on?
- Every `if (x == null)` guard: does a test pass null?

Write tests for the gaps that represent real failure modes. Accept low coverage on DTOs, mappers and config.

Set a **line-coverage floor of 60% on service packages only**, and say why in the README: *"I gated coverage on the service layer, where the logic is, rather than a global number that DTOs would inflate."* A global 80% target is mostly a measure of how many getters you have.

**⛓️ Dependencies** Tasks 1–4.
**✅ Expected Output** JaCoCo in CI with the service-package floor. Every catch block in the worker and SLA packages has a test.
**⏱️** 2.5 hours.

---

# PHASE 9B — Security Hardening

## TASK 6 — Input boundary audit

**📝 Description**
Walk every endpoint in [05](05-API-CONTRACT.md) and verify, with a test each:

| Control | Check |
|---|---|
| Ticket/message body cap | 20,001 chars → `400`, not a 20,001-char LLM call |
| Pagination `size` | `size=100000` → **clamped to 100**, not rejected and not honoured |
| Enum params | `status=DROP TABLE` → `400` |
| Search input | `plainto_tsquery` everywhere; no concatenation in any native query |
| Idempotency-Key | Charset and length constrained — it becomes a Redis key |
| `incidentId`, `teamId` etc. | Non-numeric → `400`, not `500` |
| Date params | Unparseable → `400` with a useful message |

The body cap is the one with teeth: **it is a cost control, not a style rule.** Without it one pasted log file is an expensive LLM call, and an adversary can burn your monthly budget deliberately.

**⛓️ Dependencies** Phase 8.
**✅ Expected Output** A `InputBoundaryTest` covering all seven categories. No `500` reachable from malformed input.
**⏱️** 2 hours.

---

## TASK 7 — Auth, JWT and CORS audit

**📝 Description**
Verify with tests:
- **Algorithm pinned** — a token with `"alg":"none"` is rejected; a token signed with a different algorithm is rejected
- Issuer and audience validated
- Expired access token → `401`; refresh reuse → family revoked
- **CORS has no wildcard when credentials are allowed**; an origin not in the list is rejected
- Tokens are never accepted as query parameters
- `/actuator/prometheus` and `/actuator/env` are not publicly reachable in `prod`
- BCrypt cost is 12, not the default 10

Add an ArchUnit rule: **no method may be annotated `@PreAuthorize("permitAll()")`** outside the auth package. It is the annotation someone adds while debugging and forgets to remove.

**⛓️ Dependencies** Phase 4.
**✅ Expected Output** All seven verified by test. The ArchUnit rule is green.
**⏱️** 2 hours.

---

## TASK 8 — Log and error redaction audit

**📝 Description**
**Write a test, do not do this by inspection.**

Add a capturing Logback appender in a test. Run a full ticket lifecycle — create with PII-rich text, triage, draft, resolve — then assert **no log line and no error response body contains** any of: a raw email, a phone number, a card number, a JWT, an API key, or more than 200 characters of ticket body.

Fix what it finds. The usual offenders: `log.error("Failed to process {}", ticket)` with a `toString()` that includes the body; the RFC 7807 `rejectedValue` field; and exception messages from the LLM client echoing the request.

Also verify the `detail` on a `500` is generic and the real cause goes to the log under the same `traceId`.

**This is the test that catches what an eyeball audit will not** — the leak is usually in a code path you did not think to look at.

**⛓️ Dependencies** Phase 6 Task 12.
**✅ Expected Output** `LogRedactionTest` green. A deliberately added `log.info(ticket.getBody())` fails it.
**⏱️** 2.5 hours.

---

## TASK 9 — Complete per-tenant rate limiting

**📝 Description**
Finish the table in [05 §4](05-API-CONTRACT.md): register 5/hour per IP; login 10/min per IP and 5/min per email; ticket creation 20/min per user, 200/min per tenant; drafts 10/min; reindex 2/hour; everything else authenticated 300/min per user.

Sliding window in Redis. **Fails open** — availability beats throttling for a support tool, and the alternative is an outage in your outage-management system. This is the deliberate opposite of the idempotency posture, and [03 §4.4](03-SYSTEM-ARCHITECTURE.md) has the per-use-case table; make the code match.

Emit `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` on every response, and `Retry-After` on a `429`, so a client can self-throttle instead of discovering the limit by hitting it.

**⛓️ Dependencies** Phase 5 Task 7.
**✅ Expected Output** Each limit verified by test. Stopping Redis mid-test lets requests through rather than failing them.
**⏱️** 2 hours.

---

## TASK 10 — Add secret and dependency scanning to CI

**📝 Description**
Add `gitleaks` to the workflow, scanning the full history — a key deleted in a later commit is still a leaked key. Fix anything it finds by rotating the credential, not by deleting the commit.

Add `mvn dependency-check:check` (OWASP) with a CVSS 7 failure threshold, plus a suppression file with a written justification for each entry. Add Dependabot for weekly updates.

Keep the dependency scan on a separate weekly job, not the main build — the NVD download makes it slow and its failures are rarely urgent.

**⛓️ Dependencies** Task E4.
**✅ Expected Output** gitleaks green on full history. The dependency job runs weekly with a clean or justified report.
**⏱️** 1.5 hours.

---

# PHASE 9C — Operational Jobs & Chaos

## TASK 11 — Nightly outbox prune

**📝 Description**
`@Scheduled(cron = "0 30 2 * * *")`, deleting `DONE` events older than 7 days **in batches of 10,000** with a brief pause between batches, so it never holds a long transaction against the table six workers are polling.

Log the count deleted and the remaining table size. Emit both as metrics.

**This is the first thing that breaks in production** and it is ten lines. Six workers polling once a second is ~518,000 queries a day against a table that only grows; at 2M retained rows the index bloats and the claim query — the highest-frequency query in the system — degrades.

**⛓️ Dependencies** Phase 6A.
**✅ Expected Output** A test seeds 50,000 old `DONE` rows, runs the job, asserts they are gone in batches and that `PENDING` rows are untouched.
**⏱️** 1.5 hours.

---

## TASK 12 — Nightly `open_count` reconciliation

**📝 Description**
```sql
UPDATE agent_profile ap
   SET open_count = c.cnt
  FROM (SELECT assignee_id, count(*) cnt FROM ticket
         WHERE status NOT IN ('RESOLVED','CLOSED') AND assignee_id IS NOT NULL
         GROUP BY 1) c
 WHERE ap.user_id = c.assignee_id AND ap.open_count <> c.cnt
```
Plus zeroing agents with no open tickets.

**Log every correction at WARN with the delta, and emit a `agent.open_count.drift` counter.** The job exists because `open_count` is a deliberate denormalisation ([04 §Step 5](04-DATABASE-SCHEMA.md)) — but **a non-zero drift means a bug in the assign or resolve path**, and this counter is the only thing that would tell you. A reconciliation job that silently fixes drift hides the bug it should be surfacing.

**⛓️ Dependencies** Phase 6 Task 25.
**✅ Expected Output** A test introduces drift, runs the job, asserts it is corrected and the counter incremented. Steady-state drift is zero.
**⏱️** 1.5 hours.

---

## TASK 13 — Transaction boundary audit

**📝 Description**
Write **ArchUnit rules**, not a manual review:

1. **No `@Transactional` method may transitively call `ModelRouter` or `EmbeddingService`.** This is the connection-pool rule from [11 Task 3](11-TASK-BREAKDOWN-PHASE-6.md) made permanently enforceable — violate it and ten concurrent triages hold every connection for eight seconds.
2. No `@Transactional` on private or package-private methods — Spring's proxy silently does nothing, and it looks like it works.
3. No self-invocation of a `@Transactional` method from within the same class — same trap.
4. Repository read methods used only for reads are `@Transactional(readOnly = true)`.

Then manually verify the three locked sections — PO-style consumption, agent claim, SLA pause — hold no network call and no more than a handful of statements.

**Rule 1 is the valuable one.** It is a correctness property that cannot be caught by a unit test, only by load — and by then it is production.

**⛓️ Dependencies** Phase 8.
**✅ Expected Output** Four ArchUnit rules green. Deliberately adding an LLM call inside a transaction fails the build.
**⏱️** 2.5 hours.

---

## TASK 14 — Chaos pass

**📝 Description**
**Script it, do not do it by hand** — a manual chaos pass is done once and never repeated.

`ChaosTest` using Testcontainers' ability to pause and stop containers:

| Failure | Assert |
|---|---|
| Redis stopped | Idempotency → `503` (**closed**); rate limiting passes (**open**); caches recompute; correlation sweep skips |
| LLM provider 503 | Triage → `UNAVAILABLE`; **a full ticket lifecycle still completes**; circuit breaker opens |
| Worker killed mid-stage | Event reaped and redelivered; no duplicate side effects; no second LLM charge |
| Postgres paused 5s | Requests → `503` with `Retry-After`; **nothing silently dropped**; recovery is automatic |
| Budget exhausted | `BUDGET_HELD`; ticketing untouched |
| Disk full on MinIO | Attachment upload fails cleanly; tickets unaffected |

**Row 2 is the architectural claim of the entire project, made executable:** AI failure degrades the product, it never stops it. Row 1 proves the per-use-case failure postures in [03 §4.4](03-SYSTEM-ARCHITECTURE.md) are what you actually built rather than what you wrote down.

**⛓️ Dependencies** Phase 8.
**✅ Expected Output** Six scenarios green. Record the observed behaviour for each in the README — that table is one of the more unusual things a portfolio project can show.
**⏱️** 3 hours.

---

# PHASE 10A — Containerisation & CI

## TASK 15 — Finalise the Dockerfile and Compose stack

**📝 Description**
Refine E1 with what you have learned: health check, graceful shutdown (`spring.lifecycle.timeout-per-shutdown-phase`, so in-flight workers finish rather than being killed mid-stage), and a `JAVA_OPTS` passthrough.

Finalise `docker-compose.yml`: app, postgres (pgvector), redis, minio, mailhog, prometheus, grafana, ollama — with health checks and `depends_on: condition: service_healthy` so `docker compose up` works first time on a clean machine.

**Test it on a clean machine, or at minimum after `docker system prune -a`.** "Works on my machine because of a cached volume" is discovered by a reviewer, not by you.

**⛓️ Dependencies** E1.
**✅ Expected Output** `docker compose up` on a pruned Docker brings everything healthy and the app serves requests. Graceful shutdown lets an in-flight worker complete.
**⏱️** 2 hours.

---

## TASK 16 — Complete the CI pipeline

**📝 Description**
Final workflow: checkout → JDK 21 with Maven cache → `mvn -B verify` (unit + Testcontainers + **eval gate** + ArchUnit + JaCoCo floor) → gitleaks → build image → push to GHCR on `main`.

Add a separate weekly `security.yml` for the OWASP dependency check.

**Verify the eval gate actually fails.** Degrade the triage prompt, push to a branch, watch CI go red, revert. A gate that has never failed is a gate you do not know works — and this is the second time that instruction appears in this folder because it is the one people skip.

Add the badge to the README.

**⛓️ Dependencies** Tasks 4, 10, 13.
**✅ Expected Output** Green pipeline under 12 minutes. A deliberate prompt regression turns it red.
**⏱️** 2 hours.

---

# PHASE 10B — Deploy & Observability

## TASK 17 — Full production deploy

**📝 Description**
Redeploy with everything built since E3. Create the R2 bucket and credentials. Set the full environment-variable set.

Deploy the frontend to Vercel with the API URL baked in; update `CORS_ALLOWED_ORIGINS`; redeploy the backend.

**Smoke-test every Postman folder against the live URL** — auth, tickets, SLA, triage, incidents, admin. Then run `demo/storm.sh` and `demo/no-storm.sh` against production and confirm both behave.

Add a lightweight uptime pinger hitting `/actuator/health` every 10 minutes so the demo is not cold when someone clicks it during an interview.

**⛓️ Dependencies** Task 16.
**✅ Expected Output** Full live system. Both demo scripts behave correctly against production. Every Postman folder green.
**⏱️** 3 hours.

---

## TASK 18 — Complete Micrometer instrumentation

**📝 Description**
Audit against [03 §Observability](03-SYSTEM-ARCHITECTURE.md) and fill gaps:

**Pipeline:** stage latency histograms; `outbox.queue.depth`; **`outbox.oldest.pending.seconds`**; counters by event type and outcome.
**SLA:** `sla.poller.batch.size`; `sla.poller.duration`; escalations by rung; breaches; attainment rate.
**AI:** tokens and cost per tenant per prompt version; cache hit rates (embedding, retrieval, entailment); escalation rate; circuit-breaker state.
**Incidents:** proposed/confirmed/rejected; `incident.time_to_detect`; `incident.cluster_size`; sweep duration.
**Concurrency:** lock wait time; `assignment.conflict` counter; `agent.open_count.drift`.

**Oldest-pending-age is the one that matters, not queue depth.** Depth 500 draining in ten seconds is healthy; depth 3 where the oldest is 20 minutes old means something is stuck in a retry loop, and depth alone would not tell you.

**⛓️ Dependencies** Task 17.
**✅ Expected Output** Every metric on `/actuator/prometheus` with sensible values under load.
**⏱️** 2.5 hours.

---

## TASK 19 — Build the Grafana dashboard

**📝 Description**
One dashboard, five rows: **Pipeline health** (queue depth, oldest pending, stage p95, DLQ count) · **SLA** (attainment, escalations by rung, at-risk count, poller duration) · **AI** (cost/day/tenant, tokens, cache hit rate, breaker state, classification accuracy) · **Incidents** (proposed vs confirmed, time-to-detect, cluster size) · **Concurrency** (lock wait p95, assignment conflicts, open_count drift).

Export the JSON to `ops/grafana-dashboard.json` and commit it — a committed dashboard is reproducible; a screenshot is not.

**Run the system under load first, then screenshot with real data.** A dashboard of flat zero lines is worse than no dashboard: it says you built the panels and never used them.

**Do not pay to host this.** Run it locally, capture the screenshots, put them in the README.

**⛓️ Dependencies** Task 18.
**✅ Expected Output** Dashboard JSON committed. Screenshots with real data under load. Every panel populated.
**⏱️** 2.5 hours.

---

## TASK 20 — k6 load test

**📝 Description**
Three scenarios in `ops/load/`:
1. **Sustained** — 5 tickets/sec for 10 minutes; measure p95 creation latency, triage lag, queue depth
2. **Burst** — 200 tickets in 30 seconds; measure recovery time to a drained queue
3. **Read-heavy** — 50 concurrent agents polling the queue for 5 minutes; measure p95 and connection-pool saturation

Run against the local Compose stack, **not production** — you do not want a load test burning your LLM budget. Stub the provider with WireMock for scenarios 1 and 2.

Record: p95/p99 per endpoint, sustained tickets/minute, peak queue depth and drain time, peak connection usage, and the breaking point if you find one.

**Look specifically for connection-pool saturation** under scenario 2. If Task 13's ArchUnit rule is correct, pool usage should stay low even under burst. If it spikes, there is a transaction holding a connection across a network call that the rule did not catch.

**⛓️ Dependencies** Task 18.
**✅ Expected Output** Three scripts committed with results in the README. Peak connection usage documented.
**⏱️** 3 hours.

---

# PHASE 10C — Benchmarks, Demos & Documentation

## TASK 21 — Run every benchmark and fill the placeholders

**📝 Description**
Work through every `[benchmark after implementation]` in [PROJECT-DECISION-2026.md](../PROJECT-DECISION-2026.md), [07](07-DEV-PHASES.md) and the resume bullets:

| Metric | Source |
|---|---|
| Sustained tickets/minute · p95 pipeline latency | Task 20 |
| Concurrent assignments with zero double-assignment | 5C Task 32 |
| Load-balance skew across agents | 6D Task 32 |
| Classification accuracy, macro-F1 | 9A Task 4 |
| Cost per ticket · cache hit rates | Task 18 |
| Incident time-to-detect p50/p95 · cluster precision | 8E Task 26 |
| Agent actions saved by fan-out | 8E Task 26 |
| SLA attainment on the seeded corpus | Task 19 |

**Every placeholder gets a measured number or stays a placeholder.** A fabricated metric is the one mistake in this project that cannot be recovered from in an interview, because the follow-up question is always *"how did you measure that?"* and there is no good answer.

Where a number is unflattering, **report it with context.** "Classification accuracy is 84%, and the confusion matrix shows BILLING and PAYMENT account for most errors — which is a taxonomy problem, not a model problem" is a far better answer than a rounder number you cannot defend.

**⛓️ Dependencies** Tasks 19, 20.
**✅ Expected Output** Zero placeholders remaining. A benchmarks table in the README with the measurement method for each.
**⏱️** 3 hours.

---

## TASK 22 — Record the three demo GIFs

**📝 Description**
Each under 30 seconds, under 5 MB, recorded against the deployed instance:

1. **The storm** — `storm.sh` runs, the incident board lights up with the gate evidence, the lead confirms, one update reaches 38 tickets, one action resolves them all. *Then immediately show `no-storm.sh` producing nothing.*
2. **SLA escalation** — a ticket on a fast-forwarded clock crossing 50 → 75 → 90 → 100%, with notifications firing and the breach recorded. Include the restart: kill the app mid-way, restart, watch the missed rung fire on the next poll.
3. **Priority rationale** — create a ticket, watch `analysisStatus` go `PROCESSING → READY`, open the `ⓘ` popover showing signals from the model and the deterministic rules that turned them into P2.

**GIF 3 is the one that makes the architecture visible.** It shows, in five seconds, that the model produced observations and code made the decision — which is the whole project's argument and the hardest thing to convey in prose.

**⛓️ Dependencies** Task 17.
**✅ Expected Output** Three GIFs embedded in the README, each readable at normal zoom.
**⏱️** 2 hours.

---

## TASK 23 — Write the architecture decision records

**📝 Description**
Five short ADRs in `docs/adr/`, each one page: **Context · Decision · Consequences · What I would change.**

1. **Modular monolith over microservices** — no independent scaling or deployment driver; `triage` is the clean seam if that changes
2. **Transactional outbox over Kafka** — the dual-write problem; what a broker would add and why none of it is needed at ~1 write/sec
3. **Hibernate `@TenantId` over a hand-rolled filter** — applies to queries *and* inserts; the known gap on native SQL and the test that covers it
4. **AI signals into a deterministic policy** — explainability, testability, and attributing an error to the model versus the policy
5. **Absolute deadlines with polling over in-memory timers** — restart-safe, multi-instance-safe, O(due) not O(open)

**The "what I would change" section is the one that matters.** Most portfolio documentation only defends decisions. Naming a real regret — *"I'd have made the priority policy data-driven rather than compiled, so tenants could tune it without a deploy"* — is a stronger signal than any defence, because it shows you kept thinking after you shipped.

**⛓️ Dependencies** Phase 8.
**✅ Expected Output** Five ADRs, each with a substantive fourth section.
**⏱️** 2.5 hours.

---

## TASK 24 — Write the final README

**📝 Description**
Replace README v0. Structure:

1. **One-line description** and the live URL with demo credentials
2. **The problem**, one paragraph, with evidence
3. **The three demo GIFs**
4. **Architecture diagram** and the component table
5. **"The decision I'm proudest of"** — absolute deadlines + `SKIP LOCKED`, and why an in-memory timer would have silently lost every escalation on restart
6. **"Why the LLM doesn't decide anything"** — signals versus policy
7. **"Why there's no Kafka"** — [03 §9](03-SYSTEM-ARCHITECTURE.md), verbatim
8. **"Where the data came from"** — the synthetic corpus, the generator, what you hand-verified. **Volunteer this.** Being caught hiding it reads far worse than stating it.
9. **The benchmarks table** with measurement methods
10. **The chaos table** — what happens when each dependency fails
11. **"What I'd change"** — links to the ADRs
12. **"What's not built and why"** — citation-enforced drafting was descoped; here is what it would have been
13. **Run it locally** — one command

**Section 12 is unusual and worth including.** Stating what you cut and why reads as judgement. Silence reads as an unfinished project.

**⛓️ Dependencies** Tasks 21, 22, 23.
**✅ Expected Output** A README someone can read in five minutes and come away understanding the problem, the architecture, the hard parts and the honest limitations.
**⏱️** 3 hours.

---

## TASK 25 — Update the resume

**📝 Description**
Apply [PROJECT-DECISION-2026.md §15](../PROJECT-DECISION-2026.md) with real numbers.

Replace the E-Commerce entry with the ResolveAI bullets — **pick 4**, adjusted for Path C scope. Drop the citation-verifier bullet; the four that remain are the SLA engine, incident correlation, the async pipeline with signals-not-decisions, and the CI evaluation gate.

Resume-wide changes:
- **Databases:** `PostgreSQL (pgvector), MySQL, Redis` — lead with Postgres
- **Backend:** add `Spring AI`
- **Tools:** add `Docker, Testcontainers, GitHub Actions, Prometheus/Grafana`
- **Concepts:** add `Transactional Outbox, Idempotency, Optimistic & Pessimistic Locking, Asynchronous Processing, LLM Evaluation`
- **Critically:** JWT/RBAC currently appears in all three entries. **Keep it in one.** That repetition is the structural problem [01](01-IDEA-ENHANCER.md) identified, and this is the moment to fix it.

Rehearse the 20-second opener from [PROJECT-DECISION-2026.md](../PROJECT-DECISION-2026.md) out loud until it is natural.

**⛓️ Dependencies** Task 21.
**✅ Expected Output** Updated `.tex` and PDF. JWT/RBAC appears exactly once. Every number is real.
**⏱️** 1.5 hours.

---

## TASK 26 — Final verification and tag

**📝 Description**
Full clean verification from nothing:
```bash
git clone <fresh> && cd resolveai
docker compose up -d && mvn clean verify
./demo/storm.sh && ./demo/no-storm.sh
```
**Do this from a fresh clone in a new directory**, not your working copy — the thing you are testing is whether a stranger can run it.

Final checks: every link in the README resolves; the live URL works from a logged-out browser; demo credentials work; CI is green; no secrets in history.

Tag `v1.0.0`, write release notes, and make the repository public.

**⛓️ Dependencies** Tasks 24, 25.
**✅ Expected Output** A fresh clone builds and runs. Everything green. Tagged and public.
**⏱️** 1.5 hours.

---

## 📅 Suggested Daily Schedule

### ★ Early deploy slice — after Phase 6A (3 days)

| Day | Hours | Tasks |
|---|---|---|
| E1 | 4.0 | E1 Dockerfile (2.0) · E2 Provision + verify pgvector (2.0) |
| E2 | 3.5 | E3 Deploy, seed, smoke test (2.5) · E4 CI image build (1.0) |
| E3 | 2.0 | E5 README v0 (2.0) |

### 9A — Test consolidation (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 3.5 | T1 Concurrency suite (1.5) · T2 Cross-tenant audit (2.0) |
| 2 | 3.0 | T3 Role-matrix test (3.0) |
| 3 | 2.0 | T4 Eval consolidation (2.0) |
| 4 | 2.5 | T5 Coverage gap review (2.5) |

### 9B — Security (3 days)

| Day | Hours | Tasks |
|---|---|---|
| 5 | 4.0 | T6 Input boundaries (2.0) · T7 Auth/JWT/CORS (2.0) |
| 6 | 2.5 | T8 Log redaction (2.5) |
| 7 | 3.5 | T9 Rate limiting (2.0) · T10 Scanning (1.5) |

### 9C — Ops & chaos (2.5 days)

| Day | Hours | Tasks |
|---|---|---|
| 8 | 3.0 | T11 Outbox prune (1.5) · T12 open_count reconciliation (1.5) |
| 9 | 2.5 | T13 Transaction audit (2.5) |
| 10 | 3.0 | T14 Chaos pass (3.0) |

### 10A–10B — Deploy & observability (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 11 | 4.0 | T15 Dockerfile/Compose (2.0) · T16 CI pipeline (2.0) |
| 12 | 3.0 | T17 Production deploy (3.0) |
| 13 | 2.5 | T18 Metrics (2.5) |
| 14 | 2.5 | T19 Grafana (2.5) |
| 15 | 3.0 | T20 k6 load test (3.0) |

### 10C — Benchmarks & docs (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 16 | 3.0 | T21 Benchmarks (3.0) |
| 17 | 4.5 | T22 Demo GIFs (2.0) · T23 ADRs (2.5) |
| 18 | 3.0 | T24 Final README (3.0) |
| 19 | 3.0 | T25 Resume (1.5) · T26 Final verify + tag (1.5) |

**Day 20 — Buffer.** Most likely consumed by T17 (a first full deploy always surfaces one environment problem) or T8 (log redaction usually finds three leaks you did not expect).

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 31   (early slice: 5 · 9A: 5 · 9B: 5 · 9C: 4
                       · 10A: 2 · 10B: 4 · 10C: 6)
Total Estimate : 59–63 hours  (+9.5 h for the early slice)
Suggested Days : 19 working days + 1 buffer, plus 3 early
                 (doc 07 said 9–11 combined — 1.7×, the smallest
                  factor of any phase, because most of Phase 9
                  was already written inline)

Hardest Task   : Task 13 — the transaction boundary audit.
                 Rule 1 (no LLM call inside @Transactional) encodes a
                 correctness property no unit test can catch — the
                 failure mode is connection-pool exhaustion under
                 concurrent load, which appears in production and
                 nowhere else. Writing it as ArchUnit rather than a
                 code review is what makes it permanent.

Runner-up      : Task 8 — log redaction.
                 Everyone believes they are not logging PII. The
                 capturing-appender test is what proves it, and it
                 usually finds three places you would never have
                 inspected.

Most Skipped   : Task 21 — replacing the benchmark placeholders.
                 It is late, it is tedious, and every number requires
                 actually running something. It is also the difference
                 between a project you can describe and a project you
                 can defend: every metric invites "how did you measure
                 that?", and a fabricated number is the one mistake
                 here that cannot be recovered from in an interview.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Final exit checklist

- [ ] A **fresh clone** builds and runs with `docker compose up -d && mvn clean verify`
- [ ] Live URL works from a logged-out browser; demo credentials in the README
- [ ] `storm.sh` proposes an incident against production; `no-storm.sh` does not
- [ ] CI green: unit + Testcontainers + eval gate + ArchUnit + JaCoCo + gitleaks
- [ ] **You have watched the eval gate fail on purpose and revert**
- [ ] Concurrency suite green 20 times consecutively
- [ ] Cross-tenant coverage test asserts it covers every endpoint
- [ ] Role matrix: ~200 cases green
- [ ] Log redaction test green; no PII in any log line
- [ ] Six chaos scenarios pass with documented behaviour
- [ ] **Zero `[benchmark after implementation]` placeholders remain**
- [ ] Three demo GIFs in the README
- [ ] Five ADRs, each with a real "what I'd change"
- [ ] Resume updated; **JWT/RBAC appears exactly once across all entries**
- [ ] Tagged `v1.0.0`, repository public

---

## Closing note

> ✅ **Every phase of ResolveAI is now planned at task level.** 13 documents, ~96 of 103 working days broken into 1–4 hour tasks with verifiable outputs.
>
> **The two things that matter most in this document:**
>
> 1. **Run the early-deploy slice after Phase 6A, not at the end.** From week 11 you have a live URL and a written architecture story, you build the rest against a deployed instance, and you find out about pgvector on managed Postgres in week 11 instead of week 19.
>
> 2. **Task 21 — real numbers only.** Every metric in this project is measurable, and every one of them invites "how did you measure that?". Leave a placeholder rather than invent a number.
>
> **And the thing to remember about the timeline:** the estimate moved from 8 weeks to 21 not because the project grew, but because the planning got honest. A 21-week number you can hold yourself to is worth more than an 8-week number that was never real. **Ship at every checkpoint, update the resume as each lands, and you are competitive from week 9 rather than week 21.**
