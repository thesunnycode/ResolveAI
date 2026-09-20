# 03 — System Architecture (HLD)

> Skill: `system-architect` · **MODE A — Real Project Architecture**
> Input: [02 — Project Plan](02-PROJECT-PLAN.md) · Output: the reference document for Phases 6–8

---

## 1. REQUIREMENTS CLARIFICATION

### Functional requirements

1. Users can raise a support ticket in free-form natural language and receive an immediate acknowledgement.
2. The system must classify every ticket into a category, extract impact signals, and produce a suggested team — **asynchronously**, without blocking ticket creation.
3. The system must compute a priority **deterministically** from AI signals plus plan tier, incident linkage and reopen count, and must be able to explain that priority in human-readable form.
4. The system must route a ticket to a team, then to the least-loaded available agent on shift, without two concurrent tickets landing on the same agent through a race.
5. Users can reply to a ticket; agents can add internal notes invisible to the customer.
6. The system must track first-response and resolution SLAs in **business hours** against a per-tenant calendar, pausing while awaiting the customer.
7. The system must fire a four-rung escalation ladder (50 / 75 / 90 / 100% of budget) **exactly once per rung**, and must not lose escalations across an application restart.
8. The system must predict which open tickets are at risk of breaching before they breach.
9. Agents can request a resolution draft grounded in the knowledge base, where every claim carries a citation and unsupported claims are removed rather than flagged.
10. The system must refuse to show a draft when retrieved evidence is insufficient, and escalate instead.
11. The system must detect bursts of semantically related tickets, propose a single incident for human confirmation, and fan one status update out to every linked ticket.
12. Administrators can manage the knowledge base, set SLA and AI policy, and view evaluation accuracy and token spend.
13. Every state transition must be recorded in an append-only audit trail.
14. No tenant's data may ever be visible to another tenant.
15. No personally identifiable information may leave the system to a third-party model without redaction, and a tenant must be able to forbid external model use entirely.

### Non-functional requirements

| Property | Target | Reasoning |
|---|---|---|
| **Availability** | 99.5% for the ticketing core (≈ 3.6 h/month) | Single-instance portfolio deployment. Honest number — do not claim 99.99% on one container. |
| **Availability (AI features)** | Best-effort, explicitly degradable | AI is never on a critical path except triage, and triage has a manual fallback |
| **Latency — synchronous API** | p95 < 200 ms, p99 < 500 ms | Standard CRUD reads and writes. Achievable easily; the point is to *measure* it. |
| **Latency — ticket creation** | p95 < 150 ms | It only writes two rows and returns `202`. If this is slow, something is wrong. |
| **Latency — triage completion** | p95 < 15 s end-to-end from enqueue | Dominated by the LLM call (2–8 s). The user is not waiting, but the agent queue should feel live. |
| **Latency — draft generation** | p95 < 25 s | Retrieval + generation + per-claim verification. The agent *is* waiting, so this is shown with a progress state. |
| **Latency — incident detection** | < 90 s from the burst crossing threshold | 60 s sweep interval + processing. This is a headline metric. |
| **Consistency — ticketing, SLA, assignment** | **Strong.** Read-committed with explicit row locks. | An SLA breach, an assignment or a consumed agent slot must never be wrong. |
| **Consistency — AI analysis, incident links** | **Eventual**, seconds | The whole point of the async design |
| **Durability** | Zero tolerance for losing a ticket, a message or an SLA record | Everything committed before the `202` returns. Outbox is in the same transaction. |
| **Durability — AI analysis** | Recomputable | If lost, re-drive the event. It is derived data. |
| **Read/write ratio** | ≈ **4 : 1**, read-heavy | Agents refresh queues constantly; writes are tickets and messages |
| **Hidden load characteristic** | **Poller traffic dominates at low tenant count** | See §2 — this is the non-obvious finding |

---

## 2. CAPACITY ESTIMATION

Two sets of numbers: what the portfolio deployment actually sees, and what a plausible real deployment would see. Design to the second; deploy the first.

### Users

| | Portfolio demo | Plausible real deployment |
|---|---|---|
| Tenants | 3 (seeded) | 50 |
| Agents (DAU) | 5 | 200 |
| Customers (DAU) | 20 | 2,000 |
| **Total DAU** | **25** | **2,200** |
| MAU | ~50 | ~12,000 |

### Traffic

Real-deployment arithmetic, all figures per day across all tenants:

```
Tickets created           1,500
Messages posted           4,500     (~3 per ticket)
Agent queue reads         60,000    (200 agents × 300 reads)
Customer reads            10,000    (2,000 customers × 5)
────────────────────────────────
Reads      70,000 / 86,400  =  0.81 reads/sec
Writes     18,000 / 86,400  =  0.21 writes/sec
             (1,500 tickets + 4,500 messages + ~3,000 SLA segment ops
              + ~9,000 outbox rows)

Peak multiplier 5× (Monday 10:00 IST and post-deploy spikes)
  Peak reads   ≈ 4 req/sec
  Peak writes  ≈ 1 req/sec
```

**The non-obvious finding, and the most useful number in this document:**

```
Application traffic at peak        ≈    5 queries/sec
Worker poll traffic (6 types × 1/s) ≈    6 queries/sec
SLA poller                          ≈    1 query/sec
────────────────────────────────────────────────────
Polling is MORE than half of all database load,
and it is completely independent of user count.
```

Two consequences that shape the design:

1. **Poll intervals are a real tuning parameter, not a detail.** Dropping from 1 s to 250 ms quadruples baseline database load for latency the users cannot perceive. Keep triage and draft workers at 1 s; the SLA poller can run at 10 s because a deadline is a wall-clock instant and ten seconds of lag is irrelevant; the correlation sweep runs at 60 s.
2. **Every poll query must be an index-only range scan with a `LIMIT`.** A sequential scan running once per second is what actually kills this system, long before user traffic does.

### Storage

```
Per-ticket footprint:
  ticket row (subject + body)            ~2.0 KB
  3 × ticket_message                     ~3.0 KB
  ticket_event × ~8 transitions          ~1.0 KB
  ai_analysis (signals + raw JSONB)      ~4.0 KB
  ticket embedding  768 × 4 bytes        ~3.1 KB
  sla_record × 2 + segments              ~0.5 KB
  outbox rows (before pruning)           ~2.0 KB
  ─────────────────────────────────────────────
  Total                                 ~15.6 KB

1,500 tickets/day × 15.6 KB   =   23 MB/day
                                  8.5 GB/year
  + 20% index and TOAST overhead = 10.2 GB/year
  5-year estimate                ≈ 51 GB

Knowledge base:
  50,000 chunks × (1 KB text + 3.1 KB vector + index) ≈ 250 MB   (static)

Attachments (MinIO/R2, not Postgres):
  ~15% of tickets × ~400 KB  =  90 MB/day  ≈  33 GB/year
```

**Conclusion:** 51 GB in five years sits comfortably on a single managed PostgreSQL instance. There is no sharding story here and there should not be one. The only table with unbounded growth that needs active management is `outbox_event`, which is why §10 has a pruning job.

### Bandwidth

```
Ingress:  1 write/sec  × ~3 KB avg request     ≈   3 KB/s
Egress:   4 reads/sec  × ~20 KB avg response   ≈  80 KB/s
Attachments: ~2 uploads/min × 400 KB           ≈  13 KB/s
────────────────────────────────────────────────────────
Total ≈ 96 KB/s ≈ 0.8 Mbps. Entirely irrelevant.
```

### LLM cost — the constraint that actually matters

```
Triage:    1,500/day × (1,200 in + 200 out) tokens
Drafting:  ~600/day (40% of tickets) × (4,000 in + 400 out)
Verify:    ~1,800 claim checks/day × (300 in + 10 out)
Embedding: 1,500 ticket + ~200 chunk embeddings/day

Daily token volume ≈ 5.5M input + 0.6M output

With a cheap model as default and escalation only on validator failure,
and content-hash caching removing repeat work, the target is:
  cost per ticket  →  [benchmark after implementation]
  monthly spend    →  [benchmark after implementation]

For the PORTFOLIO deployment (~10 tickets/day, CI using recorded
fixtures), total build-and-demo spend is budgeted under ₹2,000.
```

This is the number the per-tenant budget cap exists to protect, and it is the reason CI uses WireMock fixtures rather than live calls.

---

## 3. HIGH-LEVEL ARCHITECTURE

| Component | Technology | What it does here | What breaks without it |
|---|---|---|---|
| **Client** | React 18 + Tailwind SPA | Agent console, incident board, admin panel, customer portal | No UI; the API still works and is demoable via Postman |
| **CDN** | Vercel/Netlify edge (static assets only) | Serves the built SPA | Nothing structural — the SPA would just be served from one origin, slower |
| **Load Balancer** | **Not present in MVP.** Railway's ingress terminates TLS and routes to one instance. | — | Nothing yet. Added at scale step 1 (§10), at which point health checks and stateless JWT make it a drop-in. |
| **API Gateway** | **Not present.** Rate limiting, auth and request logging are Spring filters inside the app. | — | A separate gateway product would be résumé padding at one service. |
| **Application** | Spring Boot 3.x on Java 21, modular monolith | All HTTP handling, domain logic, the policy engines, and the worker runtime in the same process | Everything |
| **Worker runtime** | Virtual-thread executors inside the app, polling Postgres with `SKIP LOCKED` | Six worker types: triage, draft, index, sla-poll, correlate, fanout | Tickets are created but never triaged; SLAs never escalate; incidents are never detected |
| **Primary datastore** | PostgreSQL 16 + pgvector + tsvector | Domain data, the outbox queue, vectors, full-text index, audit trail | Everything. This is the single point of failure and the design accepts that. |
| **Cache / coordination** | Redis 7 | Idempotency records, sliding-window rate limits, embedding & retrieval & entailment caches, the correlation-sweep distributed lock | **Degraded, not down.** Caches miss through to Postgres or recompute; rate limiting fails *open*; the sweep lock fails *closed* (sweep is skipped rather than run twice) |
| **Object storage** | MinIO locally / Cloudflare R2 in prod (S3 API) | Ticket attachments, accessed via short-TTL presigned URLs | Attachments unavailable; tickets and messages unaffected |
| **LLM providers** | Primary API + fallback API + local Ollama | Triage signals, incident titles, draft claims, entailment verdicts, embeddings | AI features degrade to manual; the ticketing core is untouched |
| **Notification** | In-app `notification` table; Mailhog locally | SLA escalations, assignment alerts, incident updates | Escalations are recorded but not pushed |
| **Monitoring** | Micrometer → Prometheus → Grafana; OpenTelemetry traces | Every metric in the README | No numbers, which for this project means no resume bullets |

### Topology

```
                         ┌───────────────────────┐
                         │   React SPA (CDN)     │
                         └───────────┬───────────┘
                                     │  REST + JWT (HTTPS)
                                     ▼
 ┌────────────────────────────────────────────────────────────────┐
 │           ResolveAI  —  Spring Boot 3.x / Java 21              │
 │                                                                │
 │  ┌─────────────── HTTP layer ───────────────┐                  │
 │  │ filters: rate-limit · JWT · tenant bind  │                  │
 │  │ controllers → services → domain          │                  │
 │  └──────────────────┬───────────────────────┘                  │
 │                     │ writes domain row + outbox row           │
 │                     │ IN ONE TRANSACTION                       │
 │  ┌──────────────────▼───────────────────────┐                  │
 │  │  Worker runtime (virtual threads)        │                  │
 │  │  claim: FOR UPDATE SKIP LOCKED           │                  │
 │  │  triage │ draft │ index                  │                  │
 │  │  sla-poll │ correlate │ fanout           │                  │
 │  └──────────────────┬───────────────────────┘                  │
 └────────┬────────────┼────────────┬─────────────┬───────────────┘
          │            │            │             │
     JDBC │      Redis │     S3 API │       HTTPS │
          ▼            ▼            ▼             ▼
  ┌──────────────┐ ┌────────┐ ┌──────────┐ ┌──────────────────┐
  │ PostgreSQL 16│ │ Redis 7│ │ MinIO/R2 │ │ LLM: primary     │
  │  + pgvector  │ │        │ │          │ │      fallback    │
  │  + tsvector  │ │        │ │          │ │      local       │
  │  + outbox    │ │        │ │          │ └──────────────────┘
  └──────┬───────┘ └────────┘ └──────────┘
         │ /actuator/prometheus
         ▼
  ┌────────────┐   ┌─────────┐
  │ Prometheus │──▶│ Grafana │
  └────────────┘   └─────────┘
```

---

## 4. COMPONENT BREAKDOWN

### 4.1 Spring Boot Application (HTTP layer)

- **Technology:** Spring Boot 3.x, Java 21, embedded Tomcat with virtual threads enabled
- **Responsibility:** authentication, authorization, tenant binding, request validation, orchestration of domain services, DTO mapping. **Owns no business rules** — those live in the domain modules.
- **Interfaces:** REST/JSON in from the SPA; JDBC to Postgres; Lettuce to Redis; S3 SDK to MinIO
- **Scaling:** stateless (JWT, no server-side session). Horizontal: add instances behind a load balancer, no code change required.
- **SPOF?** Yes in MVP — one instance. Mitigation is deferred, and deliberately so: adding a second instance requires only that the poller already uses `SKIP LOCKED`, which it does. The design is multi-instance-ready without being multi-instance-deployed.

### 4.2 Worker runtime

- **Technology:** `ScheduledExecutorService` per worker type, dispatching onto `Executors.newVirtualThreadPerTaskExecutor()`, inside the same JVM as the API
- **Responsibility:** claiming outbox events and SLA records, executing the stage, committing results, handling retry/backoff/DLQ
- **Interfaces:** `SELECT … FOR UPDATE SKIP LOCKED` against Postgres; HTTPS to LLM providers; writes back through the same domain services the HTTP layer uses
- **Scaling:** two axes. Within one instance, the virtual-thread pool width (`WORKER_POOL_SIZE`) — cheap, because the work is I/O-bound and virtual threads cost almost nothing while blocked. Across instances, `SKIP LOCKED` already partitions work correctly with zero coordination.
- **SPOF?** No, by construction. If the process dies mid-stage, `locked_until` expires and another poll cycle re-claims the event. The content-hash cache means a redelivered triage does not pay for the LLM call twice.
- **The design decision worth defending:** workers run **in-process**, not as a separate deployable. At this scale a separate worker service would mean two deploy pipelines, two sets of configuration and a shared database anyway — all cost, no benefit. The seam exists (`WORKER_ENABLED=false` would let you run API-only and worker-only instances from the same image) but is not exercised.

### 4.3 PostgreSQL

- **Technology:** PostgreSQL 16, extensions `vector` and `pg_trgm`, built-in `tsvector`
- **Responsibility:** four distinct jobs that would otherwise be four systems — **the relational datastore**, **the job queue** (`outbox_event` + `SKIP LOCKED`), **the vector index** (pgvector HNSW), and **the full-text index** (GIN on `tsvector`)
- **Interfaces:** JDBC via HikariCP, pool capped at 10
- **Scaling:** vertical first (it is nowhere near a limit at 51 GB / 5 years); then a read replica for analytics and the agent queue; sharding is never appropriate here and you should say so
- **SPOF?** **Yes, and this is the one that matters.** If Postgres is down, ResolveAI is down. Mitigation in MVP is the managed provider's backups and point-in-time recovery. This is the honest single point of failure and pretending otherwise would be worse than admitting it.

### 4.4 Redis

- **Technology:** Redis 7, Lettuce client
- **Responsibility:** four jobs, each with a distinct failure posture —

  | Job | Key pattern | If Redis is down |
  |---|---|---|
  | Idempotency records | `idem:{tenant}:{key}` | **Fail closed.** Reject the request with `503` rather than risk a duplicate ticket or a double-sent customer message. |
  | Rate limiting | `rl:{tenant}:{window}` | **Fail open.** Availability beats throttling for a portfolio system. |
  | Caches (embedding, retrieval, entailment) | `emb:{sha256}`, `ret:{qhash}:{kbver}`, `ent:{sha256}` | **Fail through** to recompute. Slower and more expensive; correct. |
  | Correlation sweep lock | `lock:correlate` | **Fail closed.** Skip the sweep. Running it twice would propose duplicate incidents. |

- **Scaling:** single instance is ample; Sentinel for HA, Cluster only at absurd scale
- **SPOF?** No — every dependent path has a defined degraded behaviour, listed above. That table is the answer to "what happens when Redis goes down?", and having a per-use-case answer rather than one blanket answer is the point.

### 4.5 LLM provider layer

- **Technology:** Spring AI `ChatClient` behind an internal `ModelRouter` abstraction, with three configured targets: cheap primary, strong escalation, local Ollama
- **Responsibility:** PII redaction on the way out, schema-constrained requests, token and cost accounting, retry with jitter, provider failover, budget enforcement
- **Interfaces:** HTTPS to external providers; HTTP to Ollama in-Compose
- **Scaling:** provider-side. Local concern is the per-tenant budget and the concurrency cap on in-flight calls.
- **SPOF?** No. Three fallbacks, then graceful degradation to `UNAVAILABLE` with a manual path. **No LLM call is on a synchronous user path.**

### 4.6 The policy engines — the components with no technology

Worth listing explicitly *because* they are just Java classes, and that is the architectural point.

| Engine | Input | Output | Property |
|---|---|---|---|
| `PriorityPolicy` | `TriageSignals` + plan tier + incident link + reopen count | `Priority` + `Rationale` | Pure function. Versioned. Table-driven tests, zero LLM calls. |
| `RoutingPolicy` | category + team skills + on-call schedule + agent load | team + agent | Pure except for the agent-load read, which takes a row lock |
| `BusinessHours` | instant + business minutes + calendar | instant | Pure function. Property-tested. |
| `ToleranceEvaluator` | elapsed + policy | rung crossings | Pure function |
| `CorrelationGate` | cluster size + arrival rate + baseline | propose / don't | Pure function. **The model has no vote here.** |

These have no scaling story, no failure mode and no infrastructure. They are the part of the system that is *correct* rather than *available*, and concentrating every decision into them is what makes the AI safe to use.

---

## 5. DATA FLOW

### Flow A — Ticket creation and asynchronous triage (the primary path)

```
 1. Customer submits the form in the React SPA
 2. Browser: POST /api/v1/tickets
       headers: Authorization: Bearer <jwt>, Idempotency-Key: <uuid>
 3. Rate-limit filter → Redis INCR on rl:{tenant}:{minute}
       over limit → 429 with Retry-After, stop
 4. JWT filter → verify signature, expiry, issuer → bind tenantId + userId to context
 5. Idempotency filter → Redis GET idem:{tenant}:{key}
       hit → replay the stored response verbatim, stop
 6. Controller → @Valid on the DTO (subject 1–200, body 1–20000)
 7. ── BEGIN TRANSACTION ─────────────────────────────────────────
      INSERT ticket        (status=OPEN, priority=UNTRIAGED)
      INSERT ticket_event  (CREATED)
      INSERT outbox_event  (TICKET_CREATED, status=PENDING)
    ── COMMIT ──────────────────────────────────────────────────
       ↑ the domain write and the event are atomic.
         This is the dual-write problem, solved.
 8. Redis SETEX idem:{tenant}:{key} → stored response, 24h TTL
 9. 202 Accepted  { ticketId, reference: "TKT-10428", analysisStatus: "PROCESSING" }
10. SPA renders the ticket immediately and begins polling GET /tickets/{id}/analysis

    ─── asynchronously, within ~1 second ───

11. Triage worker: SELECT … FROM outbox_event
                    WHERE status='PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at LIMIT 50
                    FOR UPDATE SKIP LOCKED
                   → set status=IN_FLIGHT, locked_until = now() + 2 min
12. Check tenant_ai_policy → external_model_allowed? budget remaining?
       budget exhausted → status=BUDGET_HELD, notify admin, stop
13. PII redaction → "Hi, I'm «PERSON_1», order «ORDER_1» on card «CARD_1»…"
       placeholder map encrypted and stored tenant-side, never transmitted
14. Redis GET emb:{sha256(redacted)}  → miss → embedding API → SETEX
15. LLM call: schema-constrained, temperature 0, cheap model
       → TriageSignals { category, reportedImpact, paymentAffected,
                         serviceDownClaimed, linguisticUrgency, confidence }
16. ── BEGIN TRANSACTION ─────────────────────────────────────────
      INSERT ai_analysis          (signals, tokens, cost, latency)
      PriorityPolicy.evaluate(...)  ← pure function, no I/O
      INSERT priority_decision    (inputs, policy_version, result, rationale)
      RoutingPolicy: SELECT agent_profile … WHERE team=? AND available
                      ORDER BY open_count LIMIT 1 FOR UPDATE SKIP LOCKED
                     → increment open_count       ← the assignment race, closed
      UPDATE ticket   (priority, team_id, assignee_id, version+1)
      INSERT sla_record × 2  with next_deadline_at from BusinessHours
      INSERT sla_clock_segment × 2  (RUNNING, ended_at = NULL)
      INSERT ticket_event × 3  (TRIAGED, ASSIGNED, SLA_STARTED)
      UPDATE outbox_event  status=DONE
    ── COMMIT ──────────────────────────────────────────────────
17. SPA's next poll returns analysisStatus: "READY" with the priority rationale
```

**The two moments worth pointing at in an interview:** step 7, where the outbox makes the event atomic with the domain write; and step 16, where a *pure function* computes the priority inside the same transaction that applies it, so the decision and its inputs can never drift apart.

### Flow B — SLA escalation (the async path with no user in it)

```
 1. Every 10 s, the sla-poll worker runs:
      SELECT * FROM sla_record
       WHERE state='RUNNING' AND next_deadline_at <= now()
       ORDER BY next_deadline_at
       LIMIT 200
       FOR UPDATE SKIP LOCKED
    ↑ cost is proportional to DUE records, not OPEN ones.
      A million future deadlines cost nothing to skip.

 2. For each record:
      elapsed = Σ businessMinutes(segment.started, segment.ended)
                over RUNNING segments          ← derived, never stored
      pct     = elapsed / policy.target_minutes

 3. Determine the highest rung crossed (50 / 75 / 90 / 100)

 4. ── BEGIN TRANSACTION ────────────────────────────────────────
      INSERT INTO sla_escalation (sla_record_id, rung, fired_at)
        → UNIQUE (sla_record_id, rung) violation?  swallow, no-op.
          ↑ the poller is at-least-once; this makes the EFFECT exactly-once
      INSERT notification  (recipient depends on rung)
      rung = 100 → UPDATE sla_record state=BREACHED
                   INSERT ticket_event (SLA_BREACHED)
                   apply reassignment / priority-bump policy
      rung < 100 → recompute next_deadline_at for the NEXT rung
    ── COMMIT ─────────────────────────────────────────────────

 5. If the app was down for an hour, step 1 simply returns more rows
    on the next tick. Nothing was lost, because deadlines are ABSOLUTE.
    An in-memory ScheduledExecutorService would have dropped every one.
```

### Flow C — Incident correlation and fan-out

```
 1. Every 60 s: acquire Redis lock:correlate (fail closed — skip if not acquired)
 2. SELECT tickets created in the last 30 min for this tenant, with embeddings
 3. Cluster: cosine ≥ τ, boosted by shared deterministic entities
      (error code, service name, region, payment method — regex-extracted)
 4. ── THE DETERMINISTIC GATE ─────────────────────────────────
      cluster_size ≥ K (5)                                    AND
      arrival_rate > 3 × baseline(same weekday+hour, 4 weeks)  AND
      window ≤ 30 min
    Fails any condition → no incident. The MODEL HAS NO VOTE HERE.
 5. Passes → LLM writes title + summary. That is all it does.
      LLM unavailable → template title. Degrades, does not stop.
 6. INSERT incident (status=PROPOSED) + incident_ticket links
      UNIQUE (ticket_id) WHERE detached_at IS NULL → a ticket joins one incident
 7. Team lead reviews → POST /incidents/{id}/confirm
      → status=CONFIRMED
      → the confirm/reject decision is written to eval_case as a LABEL
      → linked tickets: resolution clock PAUSES, first-response clock KEEPS RUNNING
        (policy choice: the customer still deserves an acknowledgement)
 8. Lead publishes one update → POST /incidents/{id}/updates
      ── BEGIN TRANSACTION ──
        INSERT incident_update
        INSERT incident_update_delivery × N  (status=PENDING)
        INSERT outbox_event × N              (one per ticket)
      ── COMMIT ──
 9. Fanout workers process the N events independently.
      UNIQUE (incident_update_id, ticket_id) → redelivery is a no-op
      Delivery 23 fails → 23 retries. The other 37 are unaffected.
      ↑ THIS is where a queue genuinely earns its place — not on ticket creation
```

---

## 6. DATABASE CHOICE

### Decision: SQL — PostgreSQL 16, single primary, no second datastore

| Criterion | Assessment |
|---|---|
| **Data structure** | Deeply relational. A ticket has messages, events, SLA records, clock segments, an analysis, a priority decision, and optionally an incident link. Roughly a dozen genuine foreign-key relationships. Modelling this as documents would mean either enormous nested objects that are rewritten on every message, or manual joins in application code. |
| **Query patterns** | JOIN-heavy and aggregate-heavy. "Tickets for my team, open, P1 or P2, with at-risk SLA, sorted by deadline" is a four-table join with a derived aggregate. This is what a relational engine is for. |
| **Consistency** | **Non-negotiably strong** for three operations: agent assignment (a slot may not be double-taken), SLA escalation (a rung may not fire twice), and PO-style consumption of finite capacity. Each needs real transactions and real row locks. |
| **Scale pattern** | 51 GB over five years. Vertical scaling is not remotely challenged. Horizontal sharding would be pure self-harm here. |

### Why NOT MongoDB — the answer to the interview question

The honest technical reasons, in order of weight:

1. **`SELECT … FOR UPDATE SKIP LOCKED`.** This one primitive gives you a durable job queue, a safe multi-instance SLA poller, and race-free agent assignment. Emulating it in MongoDB means `findAndModify` loops and a lease field — workable, but you have then hand-built a worse version of something Postgres ships.
2. **Partial unique indexes.** `UNIQUE (sla_record_id) WHERE ended_at IS NULL` makes "exactly one open clock segment" a *structural impossibility* rather than something application code must remember. Same for one live incident per ticket. Mongo's partial indexes are close but the transactional story around them is weaker.
3. **One datastore for four jobs.** Relational data, job queue, vector index and full-text index all live in the same database and — crucially — the same transaction. Mongo would mean Mongo plus a queue plus a vector store.
4. **JSONB gives the flexibility Mongo is chosen for anyway.** Raw LLM responses, triage signals and policy rationale are all schemaless blobs, stored in `JSONB`, queryable and indexable. You get document flexibility exactly where it is genuinely needed, without giving up relational guarantees everywhere else.

### Why NOT MySQL — the answer to "why did you switch?"

You have MySQL on your resume. Four capabilities this project uses that MySQL cannot match:

| Needed | PostgreSQL | MySQL 8 |
|---|---|---|
| `SKIP LOCKED` on the outbox/SLA queue | Yes, mature | Exists, but less battle-tested for queue workloads |
| **Partial unique indexes** | Yes | **No.** Requires generated-column workarounds. |
| **Vectors in the same database** | pgvector | No first-class equivalent |
| `JSONB` with GIN indexing | Yes | `JSON` type, weaker indexing |

That is a real, specific, defensible answer — not "Postgres is better."

### Read replica strategy

**Not in MVP.** The read/write ratio is ~4:1, which is not read-heavy enough to justify the eventual-consistency complexity at this traffic.

**When to add one:** when the agent queue and analytics dashboards start competing with the transactional workload — in practice, when read queries exceed ~80% of load or p95 on `GET /tickets` degrades past 200 ms under normal use.

**How, when the time comes:** route reads for the queue, dashboards and audit to the replica; keep every write, every `FOR UPDATE`, and **every SLA and assignment read** on the primary. That last constraint is the interesting one — you cannot serve a lock-taking read from a replica, and you cannot compute an SLA breach from data that might be 200 ms stale. Note it, because it is the kind of detail that separates a real answer from a memorised one.

---

## 7. CACHING STRATEGY

| What to cache | Cache key | TTL | Invalidation | Why | Expected hit rate |
|---|---|---|---|---|---|
| Text embeddings | `emb:{sha256(redacted_text)}` | 30 days | Never (pure function of input) | Re-embedding on reindex and on repeated ticket text is pure waste | High |
| KB chunk embeddings | `kbemb:{chunk_sha256}:{model}` | 90 days | On chunk edit | Chunks are immutable; reindexing should cost nothing | ~100% after first index |
| Retrieval results | `ret:{sha256(query)}:{kb_version}` | 1 h | **Automatic** — `kb_version` is in the key, so any KB change makes old entries unreachable | Common questions repeat constantly | Moderate |
| Entailment verdicts | `ent:{sha256(claim + span)}` | 7 days | Never (pure function of both inputs) | The same claim–span pair recurs across drafts; each verdict is an LLM call | High |
| Exact-text classification | `cls:{sha256(exact_text)}:{prompt_version}` | 7 days | On prompt version change (in the key) | Rare but free; duplicate submissions and retries | Low |
| Tenant AI policy | `policy:{tenantId}` | 5 min | On policy update | Read on every single LLM call | ~100% |
| Business calendar | `cal:{tenantId}` | 1 h | On calendar update | Read on every SLA computation, changes almost never | ~100% |
| JWT deny-list | `jwtblock:{jti}` | Token expiry | On logout | Avoids a DB hit per request for revocation checks | ~100% miss (that's the point) |

**Pattern:** cache-aside for all of the above.
```
GET key → hit? return
        → miss? compute → SETEX with TTL → return
```

### What is deliberately NOT cached, and why it matters

**Generated resolution drafts. Never. Not semantically, not at all.**

This is the most important caching decision in the system and the one to raise unprompted.

Two tickets can be semantically near-identical — *"my payment failed"* and *"payment not going through"* — and require **opposite** answers, because the correct answer depends on *that customer's* order state, plan tier, refund history, and whether they are linked to a live incident. Serving customer B a draft generated from customer A's context is not a stale-cache problem; it is a **cross-customer data leak wearing a performance-optimisation costume.**

The general rule this illustrates: **cache pure functions of their inputs; never cache anything whose correctness depends on state outside the cache key.** Every entry in the table above is a pure function of its key. A draft is not.

Also not cached: SLA remaining time (must be exact), agent load counters (the value being read is precisely the thing under contention), and anything on the audit path.

---

## 8. LOAD BALANCING

**MVP: no load balancer.** One application instance behind Railway's TLS-terminating ingress. Adding one now would be infrastructure theatre.

**The design is nonetheless LB-ready**, and the reasons are worth stating because they are design decisions made *now* that pay off later:

| Property | Status | Why it matters for horizontal scaling |
|---|---|---|
| Stateless auth | ✅ JWT, no server-side session | Any instance can serve any request — **no session stickiness required** |
| No in-memory scheduled state | ✅ Absolute deadlines in Postgres | Two instances polling do not duplicate work; an instance dying loses nothing |
| Worker coordination | ✅ `SKIP LOCKED` | N instances partition the queue automatically with zero coordination protocol |
| Singleton jobs | ✅ Redis lock on the correlation sweep | The one job that must not run twice is already protected |
| Health endpoint | ✅ `/actuator/health` with custom outbox-lag indicator | An LB can make a correct routing decision from day one |

**Strategy when added:** round-robin (requests are homogeneous and short; least-connections buys nothing). Health check on `/actuator/health/readiness` every 10 s, 3 consecutive failures to eject.

**Connection pooling — the part people forget:** HikariCP capped at 10 connections per instance. This is not a load-balancing concern, it is a *scaling ceiling*: managed Postgres tiers commonly cap total connections around 100, so `instances × pool_size` must stay under that. Three instances at 10 is fine; ten instances at 20 would exhaust the database before the application broke a sweat. **The database connection limit, not CPU, is what actually bounds horizontal scaling here** — and knowing that is a better answer than "add more instances."

---

## 9. ASYNC PROCESSING / MESSAGE QUEUES

### What must not happen in the request thread

| Operation | Why async | Pattern |
|---|---|---|
| **Triage (LLM call)** | 2–8 s of network wait. Blocking here caps throughput at pool-size concurrent tickets and makes ticket creation feel broken. | Outbox event → triage worker |
| **Draft generation** | Retrieval + generation + N claim verifications = 10–25 s | `202` + status resource → draft worker |
| **Knowledge base indexing** | Chunking and embedding a 40-page runbook is minutes | `202` + status → index worker |
| **SLA escalation** | Time-triggered, not request-triggered. Nothing to block. | Deadline poller |
| **Incident correlation** | Periodic sweep over a window | Locked sweep worker |
| **Incident update fan-out** | N tickets × notification; partial failure must be isolated | N independent outbox events |
| **Resolved-ticket indexing** | Should never slow down clicking "Resolve" | Outbox event → index worker |

### The technology decision: transactional outbox in PostgreSQL. No broker.

**The problem that forces the outbox, regardless of broker choice:**

```java
// WRONG — the dual write
ticketRepository.save(ticket);      // committed
eventPublisher.publish(event);      // network call — what if this fails?
// → ticket exists, is never triaged, silently, forever.
//
// And the reverse:
eventPublisher.publish(event);      // succeeded
ticketRepository.save(ticket);      // transaction rolls back
// → a worker processes a ticket that does not exist.
```

Two systems, no shared transaction. **A broker does not fix this — it causes it.** The outbox fixes it by making the event a row in the same database, written in the same transaction, and dispatched afterwards.

Once the outbox exists, the honest question is what a broker still adds:

| Requirement | Outbox + `SKIP LOCKED` | + RabbitMQ / Kafka |
|---|---|---|
| Durable queueing | ✅ | ✅ |
| At-least-once delivery | ✅ | ✅ |
| Retry with backoff | ✅ `attempts`, `next_attempt_at` | ✅ |
| Dead-letter queue | ✅ `status = DEAD` | ✅ |
| Visibility timeout | ✅ `locked_until` | ✅ |
| Multi-consumer partitioning | ✅ `SKIP LOCKED`, zero config | ✅ |
| **Atomic with the domain write** | ✅ | ❌ — still needs the outbox |
| Operational surface | **zero** — already have Postgres | one more container, one more failure mode |
| Fan-out to independent consumer groups | ❌ | ✅ |
| Replay from an arbitrary offset | ❌ | ✅ |
| Cross-service decoupling | ❌ | ✅ |
| Throughput beyond ~1k msg/s | ❌ | ✅ |

**You need none of the four things in the bottom block.** Peak load is ~1 write/sec.

**The interview line:** *"I used a transactional outbox with `SELECT … FOR UPDATE SKIP LOCKED` because the event had to be published atomically with the domain write, and a broker cannot give me that — it would sit alongside the outbox, not replace it. Kafka earns its place when you need independent consumer groups, offset replay or cross-service decoupling. At one service and one write per second I have none of those, so it would be an extra container and an extra failure mode for nothing."*

That is a stronger signal than a Kafka container in `docker-compose.yml`, and it is true.

### Worker mechanics

```
claim    : SELECT … WHERE status='PENDING' AND next_attempt_at <= now()
             ORDER BY next_attempt_at LIMIT {batch}
             FOR UPDATE SKIP LOCKED
           → status=IN_FLIGHT, locked_until = now() + visibility_timeout

execute  : run the stage (LLM call, retrieval, delivery…)

success  : status=DONE
failure  : attempts++
           attempts < max → status=PENDING,
                            next_attempt_at = now() + min(2^attempts, 300) s
                                              + random jitter
           attempts = max → status=DEAD, last_error recorded

reaper   : every 60 s — rows with status='IN_FLIGHT' AND locked_until < now()
           → status=PENDING     (the worker died mid-stage)
```

**Idempotency of stages** is what makes at-least-once safe:

| Stage | Redelivery protection |
|---|---|
| Triage | Content-hash cache → no second LLM charge; `UNIQUE (ticket_id, prompt_version_id)` on `ai_analysis` → no duplicate row |
| SLA escalation | `UNIQUE (sla_record_id, rung)` |
| Fan-out delivery | `UNIQUE (incident_update_id, ticket_id)` |
| KB indexing | `content_sha256` on the document |

Every one of those is a **database constraint**, not an application check. An application check is a race; a unique index is not.

---

## 10. SCALABILITY STRATEGY

| Component | MVP (now) | Scale step 1 | Scale step 2 |
|---|---|---|---|
| **Application** | 1 instance, workers in-process | 2–3 instances behind an LB; `SKIP LOCKED` already handles it | Split API and worker deployables from the same image via `WORKER_ENABLED` |
| **PostgreSQL** | Single managed primary | Vertical scale + read replica for queue/analytics reads | Partition `ticket_event` and `outbox_event` by month; archive closed tickets |
| **Outbox table** | Grows unbounded ⚠️ | **Nightly prune of `DONE` rows older than 7 days** | Monthly partitions with partition drop |
| **Redis** | Single instance | Sentinel for HA | Cluster (will never be needed) |
| **Vector index** | HNSW in Postgres | Tune `ef_search` against the eval set; narrow pre-filters | Dedicated vector DB — only past ~500k chunks |
| **Object storage** | MinIO / R2 | CDN in front for attachment reads | Lifecycle rules to cold storage |
| **LLM throughput** | Serial per worker, pool of N | Raise the virtual-thread pool; batch embeddings | Provider-side quota increase; self-hosted embeddings |

### The first thing that breaks, in order

**1. `outbox_event` table growth.** Six workers polling once per second is ~518k queries/day against a table that only ever grows. The index bloats and the range scan degrades.
*Threshold:* noticeable around 500k retained rows; painful past 2M.
*Fix:* nightly `DELETE FROM outbox_event WHERE status='DONE' AND created_at < now() - interval '7 days'`. Ten lines. Ship it in Phase 10.

**2. The agent queue query.** Filter on tenant + status + priority + team, sort by `created_at`, full-text search on subject. Without a composite index this is a sequential scan.
*Threshold:* 50k–100k tickets per tenant.
*Fix (do now, both are cheap):* the composite index `(tenant_id, status, priority, created_at DESC)`, and **cursor pagination instead of offset**. The second is not a performance fix — offset pagination is *incorrect* here, because tickets resolve out of the list while an agent pages through it and offset silently skips rows.

**3. Vector search recall and latency.** Retrieval sits on the critical path of draft generation, where an agent is actually waiting.
*Threshold:* past ~500k chunks — far beyond portfolio scale.
*Fix:* tune `hnsw.ef_search` against your own eval set rather than guessing; narrow the pre-filter to tenant + product area + `kb_version` so the scan covers fewer rows; cache on `(query_hash, kb_version)`.

**4. The one that does NOT break — and why that is the interesting answer.** The SLA poller is O(due records), not O(open records), because it is a range scan on `(state, next_deadline_at)` with a `LIMIT`. A million open tickets with future deadlines cost nothing to skip. That property is a *direct consequence* of choosing absolute deadlines in an indexed column over per-ticket in-memory timers — which is the same decision that makes it restart-safe and multi-instance-safe. One design choice, three benefits.

---

## 11. FAILURE HANDLING

| Component | Failure mode | Impact | Recovery |
|---|---|---|---|
| **Application instance** | Crash / OOM | All requests fail (single instance) | Platform restarts the container. In-flight outbox events have `locked_until` expire and are re-claimed. **No work is lost.** |
| **Worker (in-process)** | Dies mid-stage | That event stalls | Reaper resets `IN_FLIGHT` rows past `locked_until` to `PENDING` after 60 s. Content-hash cache prevents paying twice for the LLM call. |
| **PostgreSQL** | Down | **Total outage — the accepted SPOF** | Managed backups + PITR. Application returns `503` with `Retry-After`; nothing is silently dropped. Honest mitigation, honestly stated. |
| **PostgreSQL** | Connection pool exhausted | Requests queue then time out | Hikari `leak-detection-threshold`; hard cap of 10; alert on pool saturation. Most common real cause is a long transaction holding a lock — which is why every `FOR UPDATE` block is kept minimal. |
| **Redis** | Down | **Degraded, not down** — per-use-case posture (§4.4): idempotency fails *closed*, rate limiting fails *open*, caches fall through, sweep lock fails *closed* | Reconnect with backoff; alert |
| **LLM primary** | 429 / 5xx / timeout | Triage delayed | Retry ×3 with jitter → failover to secondary → local Ollama if policy allows → `status=DEAD`, `analysis=UNAVAILABLE`, ticket enters the manual queue. **Ticketing is unaffected throughout.** |
| **LLM** | Returns unparseable JSON | Triage would corrupt | One repair-prompt retry → escalate to the stronger model → manual. **Never partially accept.** |
| **LLM** | Budget exhausted | Cost runaway prevented | `status=BUDGET_HELD`, admin notified. **Fail closed on spend.** Tickets still created, still assignable, still SLA-tracked. |
| **Embedding provider** | Down | No new correlation, no new indexing | Retry; existing vectors unaffected; incidents can still be created manually |
| **MinIO / R2** | Down | Attachment upload/download fails | Ticket creation continues without the attachment; the user is told which part failed |
| **Outbox** | Poisoned event fails forever | One stuck item | Max attempts → `DEAD`; DLQ visible in admin; `POST /retry` re-drives after the underlying fix |
| **Outbox** | Lag grows (consumers slower than producers) | Triage falls behind | Custom Actuator health indicator on **oldest pending event age**; alert past 5 min; raise pool width |
| **SLA poller** | Missed ticks during downtime | **None** | Absolute deadlines ⇒ next tick catches up. This is the design's payoff. |
| **Correlation sweep** | Two instances race | Duplicate incidents | Redis lock, fail closed — skip rather than double-run |
| **Clock skew** between instances | Deadline evaluated slightly early/late | Seconds of drift, harmless | All timestamps from `now()` in **Postgres**, never from application clocks. Single source of time. |

### Circuit breaker

Wrap every LLM provider call in Resilience4j:
- **Open** after 5 failures in 30 s → stop calling for 30 s
- **Half-open** → 1 probe request
- **Closed** on success

Without it, a provider outage means every worker burns its full retry budget on every event, saturating the pool and turning a degraded AI feature into an application-wide slowdown. **The circuit breaker is what keeps an AI failure from becoming a ticketing failure**, which is the whole architectural premise.

### Critical path vs. degradable

| Must work or the product is down | Degrades gracefully |
|---|---|
| PostgreSQL | LLM providers (all three) |
| Spring Boot application | Embedding provider |
| JWT verification | Redis |
| Ticket CRUD + state machine | MinIO / attachments |
| SLA poller | Incident correlation |
| | Draft generation |
| | Notifications |

**Everything in the right-hand column can be entirely absent and ResolveAI is still a functioning helpdesk.** That is the sentence to lead with when asked about AI reliability.

---

## 12. TRADE-OFFS

| Decision | What we gained | What we sacrificed |
|---|---|---|
| **Modular monolith over microservices** | One deployment, one debugger, one transaction boundary, no distributed tracing required to understand a request | Cannot scale or deploy triage independently of the API. Mitigated: `triage` already communicates only via outbox events, so it is the clean seam if that day comes. |
| **Outbox + `SKIP LOCKED` over a broker** | Atomicity with the domain write, zero extra infrastructure, one fewer failure mode | No independent consumer groups, no offset replay, ceiling around ~1k msg/s. None of which is needed. |
| **Workers in-process over a separate service** | Shared code, shared config, one deploy | A worker OOM takes down the API too. Accepted at this scale; `WORKER_ENABLED` is the escape hatch. |
| **pgvector over a dedicated vector DB** | Vectors transactional with domain data, one datastore, no sync job | Weaker at very large scale; fewer tuning knobs than Qdrant. Irrelevant at 50k vectors. |
| **Single Postgres, no replica** | Simple queries, no replica lag, no read/write routing logic | **It is the single point of failure.** Accepted and stated openly rather than papered over. |
| **Strong consistency on assignment and SLA** | Correctness under concurrency, provable by test | Row locks add contention. Mitigated by keeping every locked section to a handful of statements. |
| **Absolute deadlines + polling over in-memory timers** | Restart-safe, multi-instance-safe, O(due) not O(open) | Up to one poll interval (10 s) of latency on an escalation. Completely acceptable for a business-hours SLA. |
| **AI signals → deterministic policy** | Explainable, testable, reproducible priority. Prompt injection cannot alter a decision path. | Less "magical". The model cannot express nuance the signal schema does not encode — so the schema becomes a design artifact that needs maintenance. |
| **Claim-level verification over trusting citations** | Real hallucination control, measurable groundedness | An extra LLM call per claim (~40% more cost per draft) and ~2–4 s added latency. Bought down by the entailment cache. |
| **Synthetic seed corpus** | A demoable system on day one; deterministic, committed, reviewable evaluation | It is not real traffic, and real tickets would be messier. **Stated openly in the README** — volunteering this reads as rigour; being caught hiding it reads as the opposite. |
| **Shared-schema multi-tenancy** | One schema, one migration, simple queries | A single missing `WHERE tenant_id` is a data breach. Mitigated by a Hibernate filter below the repository layer plus a parameterised cross-tenant test over every endpoint. |
| **No load balancer in MVP** | No infrastructure theatre | Single-instance availability. Every design precondition for adding one is already satisfied. |

> **For a solo-developer portfolio project built in eight weeks, these trade-offs are appropriate.** The pattern in all of them is the same: accept operational simplicity now, but make the design decisions that keep the door open — stateless auth, `SKIP LOCKED`, absolute deadlines, outbox events, and `WORKER_ENABLED`. Every one of those costs nothing today and is what a scaling step would otherwise require a rewrite to obtain.
>
> **What to revisit when scale demands it,** in strict order: (1) prune the outbox table; (2) cursor-paginate the agent queue; (3) add a read replica for analytics; (4) split the worker deployable; (5) partition `ticket_event`. Do not do any of them before the metric that justifies it appears on the Grafana dashboard.

---

## Visual architecture diagram

The colour-coded component diagram is rendered alongside this document in the session. Colour key:

| Colour | Tier |
|---|---|
| Gray | Client |
| Purple | Backend API |
| Blue | Backend services / policy engines |
| Teal | Data stores (PostgreSQL, Redis) |
| Amber | Async components (outbox queue, workers) |
| Coral | External services (LLM providers, object storage) |

---

## Next steps

> **Architecture complete ✅**
>
> **For the build:** this document is your reference for the entire project. When you start Phase 3, use the component list in §3 to know exactly what goes in `pom.xml`. When something feels wrong during Phases 6–8, come back to the data flows in §5 and check whether your implementation matches the design.
>
> **For interviews:** practise drawing the topology from §3 on a whiteboard in five minutes. Memorise five things and you cover 80% of what an interviewer wants: the requirements (§1), the capacity estimate — especially *"polling dominates database load"* (§2), the diagram (§3), Flow A (§5), and the trade-offs (§12).
>
> **Next in the design phase:** [04 — Database Schema](04-DATABASE-SCHEMA.md), then [05 — API Contract](05-API-CONTRACT.md), then [06 — UI/UX Design](06-UI-UX-DESIGN.md).
