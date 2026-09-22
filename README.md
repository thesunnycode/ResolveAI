# ResolveAI

**AI-Assisted Helpdesk & Incident Triage Platform**

Java 21 · Spring Boot · Spring AI · PostgreSQL + pgvector · Redis · Docker

> A helpdesk backend where **the AI never decides anything.** The model emits structured
> signals; versioned deterministic policy functions compute every priority, route,
> escalation and SLA breach.

[![CI](https://github.com/sunnykrsingh/resolveai/actions/workflows/ci.yml/badge.svg)](https://github.com/sunnykrsingh/resolveai/actions/workflows/ci.yml)

🚧 **Under active development — Phase 4 of 10 complete.** [Build status below.](#build-status)

---

## The problem

A support inbox is a queue of unstructured text that has to become structured work.
Three things go wrong, and all three cost money.

**Triage is manual and it is the bottleneck.** Someone reads every incoming ticket and
decides what it is about, how urgent it is, and who should handle it. On a 200-ticket day
that is hours of skilled time spent classifying rather than resolving — and the decision
is inconsistent, because the same ticket gets P2 on Monday and P1 on Friday depending on
who read it.

**SLA tracking is either wrong or absent.** "Respond within 4 hours" means four *business*
hours, against a calendar, and the clock must stop while you are waiting for the customer
to send a screenshot. Most systems either track wall-clock time and breach constantly
overnight, or track nothing and discover the breach when the customer escalates.

**And most expensively, correlated tickets are handled independently.** A payment gateway
fails at 14:02. By 14:20 forty customers have written in — *"card declined"*, *"money
deducted no order"*, *"UPI not going through"*. A conventional helpdesk creates forty
tickets, assigns forty agents, starts forty SLA clocks, and forty people independently
investigate the same outage.

**Why software has not solved it:** triage is the one point in the workflow where an LLM
is genuinely better than a rule engine, because the input is natural language written by
someone who does not know your product's vocabulary. Everything *downstream* of triage —
priority, routing, SLA, escalation — is deterministic business logic that must be
explainable and correct, and must absolutely not be delegated to a model.

Drawing that line precisely, and enforcing it, is what this project is about.

---

## The three mechanisms that make it not a chatbot

**1. The SLA engine.** Business-hours arithmetic against per-tenant calendars. Clocks that
pause while waiting on the customer, stored as append-only segments so elapsed time is
always derived and never a mutable counter. Absolute deadlines polled with
`SELECT … FOR UPDATE SKIP LOCKED`, so the poller survives restarts and runs safely on
multiple instances. An escalation ladder made **exactly-once** under an at-least-once
poller by a partial unique constraint.

**2. Incident correlation.** Forty tickets about one outage collapse into a single
human-confirmed incident — but only behind a **deterministic statistical gate**: cluster
size ≥ K *and* arrival rate > 3× a per-weekday-per-hour baseline. The model writes the
title. A rate check decides the incident exists. This is the one place embeddings do
something no rule could: *"card declined"* and *"UPI not going through"* share almost no
vocabulary.

**3. Citation-enforced drafting.** Resolution drafts are generated as individually-cited
claims, not prose. Any claim containing a number absent from its cited span is dropped by
a free deterministic filter; the rest face an entailment check. Below 0.8 coverage the
draft is **suppressed entirely** rather than hedged — a confidently wrong answer is worse
than no answer.

---

## Architecture

```
                         ┌───────────────────────┐
                         │   React SPA (CDN)     │
                         └───────────┬───────────┘
                                     │  REST + JWT
 ┌───────────────────────────────────▼────────────────────────────┐
 │           ResolveAI  —  Spring Boot 3.x / Java 21              │
 │                                                                │
 │  ┌─────────────── HTTP layer ───────────────┐                  │
 │  │ filters: rate-limit · JWT · tenant bind  │                  │
 │  │ controllers → services → policy engines  │                  │
 │  └──────────────────┬───────────────────────┘                  │
 │                     │ domain row + outbox row, ONE transaction │
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
  │ PostgreSQL 16│ │ Redis 7│ │  MinIO   │ │ LLM: primary     │
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

Full design: [docs/planning/03-SYSTEM-ARCHITECTURE.md](docs/planning/03-SYSTEM-ARCHITECTURE.md).

---

## The data is synthetic

**Stated up front rather than buried.** ResolveAI has no real users, so the corpus is
generated — ~2,000 tickets, a knowledge base, and three planted incident storms, all
about a fictional product called **Ledgerly** ([docs/fictional-product.md](docs/fictional-product.md)).

The generator is committed ([seed/generator/](seed/generator/)) and so is its output, so
the corpus is deterministic and reviewable. It is **label-first**: category, priority,
team, persona, entities and timings are decided in code *before* the model is asked for
anything, so the labels are ground truth by construction rather than inferred from the
text.

The evaluation sets are labelled **independently**, by reading each ticket — reusing the
generator's labels would measure only whether the classifier agrees with the process that
wrote the text. The disagreement rate between the two is reported.

What was hand-verified, and by whom, is recorded in `seed/CORPUS.md`.

---

## Running the infrastructure

**Prerequisites:** JDK 21, Maven 3.9+, Docker Desktop (≥6 GB allocated), Node 20+.

```bash
cp .env.example .env      # then fill in the LLM keys
docker compose up -d
bash ops/verify-stack.sh
```

| Service | URL | Credentials |
|---|---|---|
| PostgreSQL | `localhost:55432` | `resolveai` / `resolveai_local` |
| Redis | `localhost:6379` | — |
| MinIO console | http://localhost:9001 | `resolveai` / `resolveai_local` |
| Mailhog | http://localhost:8025 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | anonymous admin |
| Ollama | `localhost:11434` | — |

Tear down completely — **this is the real test**, and CI runs it:

```bash
docker compose down -v && docker compose up -d && bash ops/verify-stack.sh
```

> **Postgres is on 55432, not 5432.** A native PostgreSQL service and Docker's port proxy
> can both bind 5432, and the client then reaches whichever bound first — which presents as
> `password authentication failed`, from a server that has simply never heard of the
> `resolveai` role. Moving the host-side port sidesteps it without touching anything else
> installed on the machine.

---

## Running the application

```bash
docker compose up -d                      # datastores first
./mvnw spring-boot:run                    # defaults to the local profile
curl -s localhost:8080/actuator/health | jq
```

`.env` is read by Spring itself, through `spring.config.import`, so there is nothing to
source into the shell — which also sidesteps the CRLF problem, where sourcing a Windows
`.env` in bash appends a carriage return to every value and the database rejects a password
that is visibly correct.

What responds today:

| | |
|---|---|
| `POST /api/v1/auth/register` · `login` · `refresh` | public |
| `POST /api/v1/auth/logout` · `GET /auth/me` | authenticated |
| `PUT /api/v1/agents/me/availability` | AGENT+ |
| `PUT /api/v1/admin/agents/{userId}/capacity` | ADMIN |
| `GET /actuator/health` | `UP`, with `db`, `redis` and `outboxLag` |
| `GET /actuator/health/readiness` | `db` and `redis` only — readiness gates traffic |
| `GET /actuator/prometheus` | metrics, open to a scraper |
| anything else under `/api/v1/**` | `404` as RFC 7807, with an `errorCode` and a `traceId` |

```bash
curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"acme","email":"arjun@acme.com","password":"resolveai-local-2026"}'
```

**Verification, all four of which run in CI:**

```bash
bash ops/verify-stack.sh         # 7 containers, pgvector, HNSW
bash ops/verify-migrations.sh    # schema from empty + 10 structural guarantees
bash ops/verify-openapi.sh       # Redocly + 64 operations + 12 with examples
node ops/verify-postman.mjs      # collection matches the contract, both directions
./mvnw clean verify              # 5 integration tests on real containers
```

---

## Authentication

Two tokens, and they are different kinds of thing on purpose.

| | Access token | Refresh token |
|---|---|---|
| Form | JWT, HS256 | 256 bits of `SecureRandom`, base64url |
| Lifetime | 15 minutes | 7 days |
| Stored server-side | no | `SHA-256` of it, never the value |
| Carries claims | `sub`, `tenantId`, `tenantSlug`, `role`, `jti`, `iss`, `aud` | none |
| Revocable | **no** — see below | yes, individually and by family |

**The tenant comes from the token and from nowhere else.** Not a header, not a path
variable, not a request body. Any of those would let an authenticated user read another
tenant's data by editing a parameter.

### Rotation and reuse detection

Every refresh token issued from one sign-in shares a `familyId`. A refresh token is
**single use**: exchanging it marks it used and mints a successor in the same family.

Presenting a token whose `used_at` is already set means the value leaked — either an
attacker replayed a stolen token, or the real client replayed its own. **The system cannot
tell which of the two is the attacker**, so it revokes the whole family and forces a fresh
sign-in. Logging both out is the only choice that is safe under either reading.

Rotation without detection is half a defence, and the detection has two properties that are
easy to get wrong and are both tested:

- the exchange takes a `SELECT … FOR UPDATE`, so eight concurrent refreshes produce exactly
  one success — without it the single-use property that detection rests on is a race, and
  it measurably was: three of eight succeeded
- the revocation **commits**, rather than being rolled back by the exception that reports it

### Login does not leak which half was wrong

An unknown tenant, an unknown address and a wrong password all return the same
`401 INVALID_CREDENTIALS` with the same body. A BCrypt comparison runs even when the user
does not exist, against a dummy hash, so the response time does not say what the status code
will not: measured **248.9ms vs 245.5ms**.

### What logout does not do

**Logging out does not invalidate the access token.** It is stateless and stays valid until
it expires — at most 15 minutes. The fix is a Redis deny-list keyed on `jti`, and it is
deliberately deferred: it adds a Redis read in front of *every* request to close a window
that is already short.

That is a trade-off rather than an oversight, and it is written here because "JWTs cannot be
revoked instantly" is a fair question to be asked about this design.

### Tenant isolation

`@TenantId` on every tenant-scoped entity, with a `CurrentTenantIdentifierResolver` reading
a `ThreadLocal` the JWT filter populates. Hibernate then appends the discriminator to every
query **and** sets it on insert, so a repository cannot read another tenant's rows and a
service cannot write into the wrong one — `AppUser` has no setter for `tenantId` at all.

Two things worth knowing:

- **An unset context means no rows, never all rows.** The resolver substitutes a sentinel
  that matches nothing, so a mis-wired path returns an empty result rather than everybody's
  data.
- **Hibernate resolves the tenant when the session opens, not per statement.** So
  `@Transactional` on a method that sets the tenant *inside* binds the session first and
  every write lands under the sentinel. `TenantScope` exists to encode the correct order:
  set the tenant, then open the transaction.

**Native SQL bypasses all of this**, and Phases 6–8 use native queries for the outbox claim
and hybrid retrieval. Those carry an explicit `AND tenant_id = :tenantId`, and
`CrossTenantAccessTest` is what notices when one does not.

### Local seed logins

`docker compose up -d && ./mvnw spring-boot:run` seeds 3 tenants, 12 teams and 87 users, and
prints a login table at startup. Password for every seeded account:

```
resolveai-local-2026
```

| tenantSlug | role | email |
|---|---|---|
| `acme` (ENTERPRISE) | ADMIN | admin@acme.com |
| `acme` | TEAM_LEAD | sana@acme.com |
| `acme` | AGENT | arjun@acme.com |
| `acme` | CUSTOMER | customer1@example.com |
| `bluestone` (PRO), `chai` (FREE) | same four roles | `@bluestone.in`, `@chaicorner.in` |

⚠️ **A single shared password across every seeded account is fine for a machine on your desk
and catastrophic anywhere else.** The seeder is `@Profile("local")` and guarded by
`resolveai.seed.enabled`; it cannot run under `prod`.

---

## The SLA engine

A promise like "four business hours to resolve a P2" is three separate problems wearing one
sentence: what a business hour is, what stops the clock, and who finds out before the
promise is broken. Each is solved by something checkable rather than by a comment.

### Business-hours arithmetic is a property test, not an example test

`BusinessHours.add(from, minutes, calendar)` walks working days in the tenant's own zone.
The suite states five properties and lets jqwik search for counterexamples across three
calendars — including a DST zone and a **Sunday-to-Thursday** week, because a support desk
in Dubai is not a bug report:

* adding zero is identity
* adding is monotonic in the minutes
* `elapsed(from, add(from, n)) == n` — the two functions are inverses, which is the property
  that catches an off-by-one in the boundary convention
* no result ever lands outside working hours
* a holiday is never counted

`day_start` is inclusive and `day_end` exclusive, in both functions. Stating the convention
is not pedantry: if `add` and `elapsed` disagree by one minute at the boundary, every clock
drifts by a minute per day and nothing fails loudly.

### The clock is append-only. There is no `elapsed_minutes` column

Elapsed time is a **sum over `sla_clock_segment` rows**, derived on every read. Pausing
closes the open segment and inserts a paused one; resuming does the reverse and recomputes
the deadline from *remaining budget*, not from the original start — a ticket that spent two
days waiting on the customer has burned none of its target.

A mutable counter would be a lost update under concurrency, and a wrong one is
undetectable after the fact. A segment history can always be re-derived, and the
`GET /tickets/{id}/sla` response publishes the segments for exactly that reason. The
database enforces the shape rather than trusting the code to: `uq_segment_open` allows one
open segment per record, and a trigger refuses to reopen a closed one.

The order of statements in `pause()` is load-bearing, and it is written out explicitly
instead of going through a cascading collection — Hibernate does not flush in source order,
and letting it put the INSERT before the UPDATE trips `uq_segment_open` with an error that
points at the wrong statement entirely.

### Deadlines are absolute instants in an indexed column, and a poller reads them

**This is the part worth arguing about.** The obvious design is a
`ScheduledExecutorService` task per deadline: simpler, no polling latency, and it
**silently loses every pending escalation on restart** — including an ordinary deploy.
Nothing logs it, because from the new process's point of view those timers never existed.
The first anyone hears is a customer escalating weeks later about a breach that was never
flagged.

An absolute deadline in `sla_record.next_deadline_at` has no such failure mode. The
application is stateless with respect to it, and `SlaPollerTest` says so in a test:

> **Restart recovery** — a deadline two hours in the past, as left behind by an application
> that was down for two hours. One poll finds it and fires the correct rung immediately.
> Nothing is lost and nothing is skipped.

`idx_sla_poller` is partial on `state = 'RUNNING'`, so the cost of a poll is proportional
to the number of *due* clocks, not to the number of open tickets. A paused clock has a null
deadline and leaves the index entirely.

### Escalation is exactly-once because of one line of DDL

The poller is **at-least-once by construction**: it claims ids in one short transaction
(`FOR UPDATE SKIP LOCKED`) and processes each in its own, so two runs — or two application
instances — can pick the same record. Holding those locks for a whole batch would block an
agent replying to any of two hundred tickets behind a background job, so the locks are
deliberately short and the duplication is deliberately allowed.

It is safe because of `uq_escalation_rung UNIQUE (sla_record_id, rung)`. The escalation row
is inserted **before** the notification is sent; a violation means the rung has already
fired and the method returns having sent nothing. An at-least-once trigger becomes an
exactly-once effect with no leader election, no distributed lock and no coordination. The
constraint name is checked before the violation is swallowed, so a genuine bug in the
notification write is not hidden for ever behind "already fired".

### What the concurrency tests actually pin down

| Test | Invariant |
|---|---|
| 20 agents assign one ticket | exactly one `200`, nineteen `409`, `open_count` moves by one |
| 50 tickets, 5 agents | load skew ≤ 1 |
| reply vs. breach poller, ×50 | the clock is `MET` with no breach row, or `BREACHED` — never both, never neither |
| two concurrent pauses | both `200`, exactly one open segment, exactly two rows |
| pause vs. resume | one open segment, and its state agrees with the record's |
| poller run repeatedly | four rungs, four notifications — not twelve |
| two pollers, one record | one escalation row |
| deadline two hours stale | the right rung fires on the first poll after recovery |

Every one of them is released by a `CountDownLatch`, never a `Thread.sleep`. A sleep-based
concurrency test is flaky, gets `@Disabled` within a week, and then protects nothing.

---

## The async pipeline & AI triage

### The dual-write problem, and why a broker does not fix it

```java
ticketRepository.save(ticket);      // committed
eventPublisher.publish(event);      // network call — what if this fails?
// → the ticket exists, is never triaged, silently, forever.

// and the reverse:
eventPublisher.publish(event);      // succeeded
ticketRepository.save(ticket);      // transaction rolls back
// → a worker processes a ticket that does not exist.
```

Two systems, no shared transaction. A message broker does not close this gap — it
**is** this gap, with a different vendor name on it. The fix is to make the event a row
in the same database, written in the same transaction as the ticket, and dispatched
afterwards: the transactional outbox. `TicketService.create()` inserts the ticket and
publishes `TICKET_CREATED` in one `@Transactional` method; either both commit or neither
does.

### Why there is no Kafka, no RabbitMQ

Once the outbox exists, the honest question is what a broker still adds on top of it:

| Requirement | Outbox + `SKIP LOCKED` | + a broker |
|---|---|---|
| Durable queueing, at-least-once delivery | ✅ | ✅ |
| Retry with backoff, dead-letter queue | ✅ `attempts`, `next_attempt_at`, `status=DEAD` | ✅ |
| Visibility timeout, multi-consumer partitioning | ✅ `locked_until`, `SKIP LOCKED` | ✅ |
| **Atomic with the domain write** | ✅ | ❌ — still needs the outbox |
| Operational surface | zero — already have Postgres | one more container, one more failure mode |
| Fan-out to independent consumer groups, offset replay, cross-service decoupling | ❌ | ✅ |

Peak load here is roughly one ticket a second. None of the three things only a broker
gives you — independent consumer groups, replay from an arbitrary offset,
cross-service decoupling — apply at one service and one write per second. A Kafka
container in `docker-compose.yml` would be an extra failure mode bought for nothing;
this table is the argument for leaving it out, and it is true regardless of scale until
the day one of those three rows actually matters.

### Three phases, and the one rule that makes virtual threads safe

```
tx1  short read    the ticket's text, the active prompt, the tenant's plan
---  no tx         2–8 seconds: redact → embed → classify
tx2  short write    analysis, decision, priority, routing, assignment, SLA
```

A worker must never hold a database connection across the network call. The tempting
version — one `@Transactional` around the whole method — reads perfectly well and is
the single most damaging line a worker can contain: the pool caps at ten, ten
concurrent triages hold all ten connections for the seconds each classification takes,
and the entire application stops serving HTTP while every unrelated request queues on
`getConnection()`. The logs show pool exhaustion, which points at Hikari settings; the
actual cause is a network call sitting inside a transaction, three layers away.

**Virtual threads make this easier to hit, not harder.** A platform-thread pool of eight
used to cap concurrency at eight almost by accident; virtual threads remove that
accidental ceiling, so the first real load test after adopting them is the one that
discovers the transaction was never safe. `TriageWorker` is structured as
short-read → no-transaction → short-write for exactly this reason, and
`holdsNoConnectionDuringTheCall` proves it by measuring the **mean**, not the peak,
active pool connections during five concurrent classifications — the peak is bounded by
batch size either way and cannot tell a correct implementation from a broken one; the
mean can, because a held connection stays checked out for the whole simulated network
delay and a released one does not.

### Signals, never decisions — the whole argument in one sentence

`TriageSignals` has no `priority` field, and a comment in the class, a banner in the
migration that seeds the prompt, and a table-driven test all say why. The model reports
what a ticket *says*: its category, whether it claims an outage, whether money is
involved, how the message is worded. `PriorityPolicy` — a pure function with no Spring,
no JPA, and no network call anywhere in its dependency graph — turns those observations
into a priority through eight named, ordered, versioned rules, and returns the full
trace of what matched and what did not.

That split is what makes `GET /tickets/{id}/priority-rationale` answer a question no
`"priority": "P2"` field could ever answer: when an agent overrides a decision, did the
**model misread the ticket**, or is the **policy wrong**? Two different bugs, fixed two
different ways, and the endpoint's `inputs.fromModel` / `inputs.fromSystem` split is
built to make the distinction visible rather than argued over. 45 policy tests run in
under 50ms with no model, no Spring context, and no bill — the cheapest, most exhaustive
proof in the codebase that this system's most consequential number is not an LLM's
opinion.

### PII redaction: deterministic, and cached on the redacted text

Every ticket body is redacted before it leaves for a model: card numbers (Luhn-checked),
Aadhaar and PAN numbers, phone numbers, email addresses, IP addresses and known names all
become stable placeholders — `«CARD_1»`, `«PERSON_2»` — with the mapping encrypted
(AES-GCM) and stored per-ticket, so an authorised human can rehydrate the original text
but nothing else can. Redaction has to be **deterministic**: the embedding cache key is
a hash of the *redacted* text, and an unstable placeholder means a new cache key on every
run, a cache that never hits, and an embedding bill quietly double what it should be with
nothing anywhere reporting the difference.

### What is real and what is a fixture

Every number below marked **measured** came from this machine's test suite against the
stubbed provider; none of it is a live-model benchmark, and none of it is presented as
one.

| | |
|---|---|
| Agent load distribution, 50 tickets / 5 agents, real triage pipeline | **measured** — `[10, 10, 10, 10, 10]`, skew **0** |
| `SKIP LOCKED` vs. plain `FOR UPDATE`, 8 routers / 10 agents, lock held under simulated work | **measured** — 267 ms, loads `[1,1,1,1,1,1,1,1,0,0]` vs. 907 ms, loads `[8,0,0,0,0,0,0,0,0,0]` |
| Mean / peak Hikari connections during 5 concurrent classifications | **measured** — mean **0.97**, peak **5** (pool max 10) |
| Cost of one classification call | **measured, against the stub's fixed token counts** — 744 micros (≈ $0.00074), from published `gpt-4.1-mini` list pricing applied to the stub's `1187`/`168` token response |
| Classification eval suite, 60 cases, stubbed provider | **measured, and honestly not meaningful as an accuracy number** — accuracy 1.0, macroF1 1.0, because the stub is told the right answer for a "correct" run by construction |
| Whole suite from an empty database | **measured** — 475 tests, 0 failures, ~14 minutes |
| Triage latency, cost per ticket against a live model, classification accuracy against real tickets | **not measured** — the provider here is a WireMock stub with fixed delays and fixed token counts; a number from it would describe the fixture, not the system |

The eval suite's own honesty is worth stating plainly, because it is easy to
over-claim: `ClassificationEvalTest` proves the harness works end to end — the policy
gate, redaction, cost accounting, scoring, persistence and the gate itself all run for
real, and one test deliberately degrades the classifier and watches the gate fail. What
none of it proves is that the prompt is good. That number comes only from a run
against a live model, and `docs/planning` records this as the honest boundary rather
than a promise about model quality this repository cannot back up with a stub.

---

## Build status

**Phase 1 — Environment Setup · ✅ complete** (`phase-1-complete`)

| Task | |
|---|---|
| 1 Toolchain verified | ✅ Java 21.0.11, Maven→JDK 21, Docker 29.4 (7.9 GB), Node 24 |
| 2 Repository & hygiene | ✅ `.gitignore` in commit 1 |
| 3 Compose stack | ✅ 7 services + auto bucket + auto extensions |
| 4 Stack verified from empty volumes | ✅ **pgvector 0.8.6, HNSW proven by `EXPLAIN`** |
| 5 LLM credentials | ✅ OpenAI `gpt-4.1-mini`; embeddings at 768-d |
| 6 Local Ollama model | ✅ `llama3.2:3b`, parseable JSON confirmed |
| 7 Fictional product & vocabulary | ✅ Ledgerly, 6 domain files |
| 8 Category taxonomy | ✅ 8 categories, 4 teams, deliberate PAYMENT/BILLING overlap |
| 9 Corpus generator | ✅ label-first, deterministic |
| 10 Starter corpus | ✅ 200 tickets, **30 hand-verified** → [seed/VERIFICATION.md](seed/VERIFICATION.md) |
| 11 README v-1 | ✅ this file |

**Phase 2 — Design Closure & Schema · ✅ complete** (`phase-2-complete`)

| Task | |
|---|---|
| 1 End-to-end design trace | ✅ **backward trace found 6 gaps** → [docs/design-trace.md](docs/design-trace.md) |
| 2 Schema and contract gaps closed | ✅ +11 endpoints, 2 tables folded into V1/V2 |
| 3 Migration roadmap | ✅ V1–V14 reserved → [docs/migrations.md](docs/migrations.md) |
| 4–10 V1–V7 written | ✅ extracted from doc 04, **applied first try** |
| 11 Schema verified | ✅ 39 tables · 125 indexes · 10 triggers · 73 FKs |
| 12 Structural guarantees | ✅ **all 10 hold** — 6 negatives, 4 positives |
| 13 Migration repeatability | ✅ `ops/verify-migrations.sh`, green from empty |
| 14 OpenAPI 3.1 | ✅ 64 operations, 12 with real examples, Redocly clean |
| 15 Postman collection | ✅ 77 requests, self-authenticating, no secrets |
| 16 Handoff | ✅ [docs/phase-2-handoff.md](docs/phase-2-handoff.md) |

**Phase 3 — Project Setup & Boilerplate · ✅ complete** (`phase-3-complete`)

| Task | |
|---|---|
| 1–2 Skeleton and dependencies | ✅ **Spring Boot 4.1.1 + Spring AI 2.0.1** (see below) |
| 3 Module packages | ✅ 10 packages, each with its dependency rule in `package-info.java` |
| 4 Config profiles | ✅ `local` / `test` / `prod`; prod has no defaults, by design |
| 5 Flyway wired | ✅ `ddl-auto: validate`, "Schema public is up to date" |
| 6 Redis | ✅ `redis: UP`, 7.4.11 |
| 7–8 Error model and advice | ✅ 48 error codes, RFC 7807 on every path |
| 9 Structured logging | ✅ MDC `traceId`, `X-Request-Id`, JSON under prod |
| 10 Actuator | ✅ health / readiness / prometheus, plus `outboxLag` |
| 11–12 Testcontainers suite | ✅ 5 integration tests, **14.7s** |
| 13 GitHub Actions | ✅ `.github/workflows/ci.yml` — two jobs |
| 14 Cleanup and tag | ✅ this section |

> **Why Boot 4 and not the 3.3.x the plan specified.** The plan was written before the
> build started. start.spring.io now offers no 3.x at all, and both 3.3 and 3.5 are past
> OSS end of life — shipping an unsupported framework line is a worse answer in an
> interview than explaining a migration. The cost was real and is documented in the commit
> history: Boot 4 renames the starters, Spring AI 2 renames the model starters,
> Testcontainers 2 renames every module, Jackson 3 moves `WRITE_DATES_AS_TIMESTAMPS`,
> `TestRestTemplate` moves package *and* needs an annotation, and OkHttp 5 keeps its JVM
> classes in a separate artifact. Each was found by running, not by reading.

**Phase 4 — Authentication, Tenancy & User Management · ✅ complete** (`phase-4-complete`)

| Task | |
|---|---|
| 1–2 Entities and repositories | ✅ 5 entities, 5 repositories, `ddl-auto: validate` passes |
| 3 Entity–schema mapping test | ✅ enum-as-string, `text[]`, soft delete, `@Version` |
| 4–5 TenantContext and `@TenantId` | ✅ discriminator multi-tenancy, sentinel on unset |
| 6 Tenant isolation test | ✅ list, direct lookup, insert, and the unset-context case |
| 7–10 JWT, filter, user details, SecurityConfig | ✅ HS256 pinned, iss/aud verified |
| 11–15 register / login / refresh / logout / me | ✅ contract from doc 05 §3.1 |
| 16 Method security | ✅ `@IsAdmin`, `@IsTeamLeadOrAbove`, `@IsAgentOrAbove`, all used |
| 17 Seed loader | ✅ 3 tenants, 12 teams, 87 users, idempotent |
| 18 Refresh rotation and reuse test | ✅ incl. **exactly 1 of 8 concurrent refreshes** |
| 19 Cross-tenant access test | ✅ 10 assertions; **verified by breaking `@TenantId`** |
| 20 Cleanup and tag | ✅ this section |

> **Three controls that looked correct and were not**, all found by running the tests rather
> than reading the code: single use was a race (3 of 8 concurrent refreshes succeeded); the
> family revocation on reuse was rolled back by the exception reporting it, so the successor
> token kept working while the response claimed otherwise; and `@Transactional` outside
> `TenantContext.runAs` bound the Hibernate session to the no-tenant sentinel, because the
> resolver is consulted when the session opens rather than per statement. Details in the
> commit history.

**Phase 5 — Ticketing & the SLA Engine · ✅ complete** (`phase-5-complete`)

| Task | |
|---|---|
| 1–3 Entities, reference generator, repositories | ✅ `TKT-1000` per tenant, gapless under concurrency |
| 4–5 State machine | ✅ pure component, **73 table-driven cases**, `allowedTransitions` on every 409 |
| 6–7 Event recorder, `Idempotency-Key` | ✅ replay returns the first response, byte for byte |
| 8–13 Create, list, detail, ETag, PATCH | ✅ cursor pagination, role-scoped DTOs, `If-Match` required |
| 14–16 Messages, assign, status/resolve/reopen | ✅ conditional update with an affected-row check |
| 17 Attachments | ⏭️ deferred to Phase 8 with the storage work |
| 18–19 SLA entities and repositories | ✅ 6 entities; the claim query returns `(id, tenant_id)` |
| 20–22 `BusinessHours` | ✅ **5 properties × 3 calendars**, incl. DST and a Sun–Thu week |
| 23–27 Policy resolution, clocks, lifecycle wiring | ✅ effective-dated, append-only, driven from `sideEffectOf` |
| 28–31 Poller, escalation ladder, prediction, at-risk | ✅ `SKIP LOCKED`, exactly-once by constraint |
| 32–34 Concurrency tests | ✅ **95 runs across three suites**, all latch-released |
| 35 Cleanup, README, Postman, tag | ✅ this section |

**Measured, on this machine** (Testcontainers, Docker Desktop, 8-core laptop):

| | |
|---|---|
| 20 concurrent assignments | 1 × `200`, 19 × `409`, `open_count` +1 — over 10 repeats |
| 50 tickets, 5 agents, concurrent | load skew **1** |
| reply vs. breach poller | **50/50 runs consistent** — never both, never neither |
| poller batch | 50 due clocks, one pass, **1.8–2.0 s (37–41 ms/record)** |
| whole suite (at Phase 5) | **334 tests** from an empty database |

> The ~40 ms per record is a *per-record transaction*, not a row read: claim, re-lock,
> recompute elapsed from the segment history, insert the escalation, write the
> notification and the ticket event, commit. The number worth defending is not that it is
> fast but that it is `O(due)` — `idx_sla_poller` is partial on `state = 'RUNNING'`, so a
> quiet poll with ten thousand open tickets costs one index probe.

> **Seven bugs the tests found, none of which the code looked wrong for.** A `@Lock`
> annotation on a native query meant **no escalation would ever have fired** — it threw on
> every record, and the poller logged and carried on. The tenant was set *inside* the
> poller's transaction, but Hibernate reads it when the session opens, so every ticket
> load failed on a row that plainly existed (the same trap as Phase 4, in a new place).
> `hibernate.jdbc.time_zone: UTC` is correct for `TIMESTAMP` and silently corrupts `TIME`:
> every tenant's working day was shifted by the server's offset, and because the day was
> still nine hours long, every duration property still passed. The next rung's deadline
> was computed from its full budget instead of the remaining one, so the ladder stopped
> climbing on exactly the tickets it exists for. `start()` asked "does this ticket have
> any clock?" rather than "of this kind", so a **reopened ticket got no resolution clock
> at all** and would never breach again. Pausing an already-paused clock appended a third
> segment and moved the pause's start time. And deadlines written from the JVM's clock
> were claimed by a query comparing against the database's, so a due rung went unclaimed
> whenever the container drifted — now every timestamp comes from `DatabaseClock`.

**Phase 6 — Async Pipeline & AI Triage · ✅ complete** (`phase-6-complete`)

| Task | |
|---|---|
| 1–6 Outbox, worker runtime, retry, reaper, metrics | ✅ `FOR UPDATE SKIP LOCKED` claim-and-return in one statement |
| 7 Admin DLQ endpoints | ⏭️ deferred to Phase 10 with the rest of the ops surface |
| 8–10 Worker tests, `202` on create, `/analysis` | ✅ the ticket and its triage job commit together |
| 11–14 Tenant AI policy, PII redaction, rehydration, prompt versions | ✅ fail-closed policy, AES-GCM, `triage@1` seeded as a migration |
| 15–20 `TriageSignals`, router, breaker, budgets, embeddings, WireMock | ✅ **no `priority` field, and a comment at each end saying why** |
| 21 `TriageWorker` | ✅ short read → no transaction → short write |
| 22–23 `PriorityPolicy` | ✅ pure function, **45 cases in 49 ms**, no Spring context |
| — Cross-tenant table extended | ✅ 23 endpoints × 2 roles, **verified by breaking `@TenantId`** |
| 24–25 Routing and `claimLeastLoadedAgent` | ✅ GIN skill overlap, most-specific team, `SKIP LOCKED` claim |
| 26–27 `/priority-rationale`, `/priority-override` | ✅ inputs split `fromModel` / `fromSystem`; override retargets the clocks |
| 28 SLA start moved into triage | ✅ **plus the fallback sweeper for the hole that opened** |
| 29 `/retriage` | ✅ new attempt, old analysis kept, `409` while one is queued |
| 30–31 Duplicate-claim, crash-redelivery | ✅ one analysis, one model call, no partial state under any crash point |
| 32 Load-distribution test | ✅ skew **0** over the fixed run — `[10,10,10,10,10]`; `@Disabled` negative control measures `SKIP LOCKED` against plain `FOR UPDATE` |
| 33 Failure-mode tests | ✅ **a full ticket lifecycle completes with the provider 503-ing on every call** |
| 34 Classification eval harness | ✅ 60 hand-labelled cases, accuracy + macro-F1, committed baseline gates `mvn verify`, watched failing on purpose |
| 35 Cleanup, README, Postman, tag | ✅ this section |

**The argument, in one endpoint.** `GET /tickets/{id}/priority-rationale` returns the
model's observations and the system's facts as two separate objects, then the eight
policy rules in evaluation order with `matched` on each — including the ones that did
*not* fire, because "PLAN_TIER_BUMP, matched: false, ENTERPRISE would" is what an agent
needs to decide whether to override. A design where the model returns
`"priority": "P2"` cannot produce any of it, and cannot tell you afterwards whether the
model misread the ticket or the policy is wrong.

> **The gap that only appears when a call moves between transactions.** 6A took
> `sla.start()` out of ticket creation, because a clock measures a promise and the
> promise is not known until the priority is. 6C put it at the end of the triage
> transaction. Both steps are right; together they mean **a ticket whose triage never
> succeeds has no SLA at all** — no clocks, nothing in the at-risk list, no escalation,
> ever. Completely silent: the ticket looks ordinary and the dashboard looks *greener*,
> because untracked tickets cannot breach. A two-hour provider outage would make every
> ticket raised in it permanently invisible to the SLA engine. `SlaFallbackSweeper`
> closes it — P3 defaults after a ten-minute grace, with an audit event saying the
> system defaulted it rather than implying somebody decided.

> **And one the test was wrong about, not the code.** The obvious "no connection is held
> across the model call" assertion is on the *peak* active pool connections. It fails at
> five for five concurrent triages — and so would a correct implementation, because five
> workers starting together genuinely overlap their short read transactions. Peak cannot
> distinguish the two; it is bounded by the batch size either way. Duration can: with
> ~600 ms of network per event and single-digit milliseconds of transaction, the **mean**
> active count is a fraction of one, where a `@Transactional` around `process()` would
> hold all five for the whole call and sit just under five. The assertion is on the mean.

> **A security test that was passing for the wrong reason.** The cross-tenant table was
> extended with the ticket, SLA and triage endpoints, and then — as Phase 4 did — the
> `@TenantId` annotation was removed from `Ticket` to check the suite would notice. **It
> did not.** Every row stayed green, because an alpha *agent* is refused a beta ticket by
> `isVisibleTo`, the role-and-team predicate, long before tenancy is consulted. The file
> was proving team scoping and reporting it as tenant isolation. `isVisibleTo` returns
> `true` unconditionally for `ADMIN`, so the whole table now runs a second time with an
> admin token — where the discriminator is the only control left — and that run fails
> immediately when `@TenantId` is removed. Generally: a negative security test is only as
> strong as the weakest control that can satisfy it. The native reads behind `/analysis`
> and `/priority-rationale` also grew their own `AND tenant_id = ?`, because
> `@TenantId` does not reach native SQL and "safe because every caller checks first" holds
> only until the first caller that does not.

> **One flake, and the fix that actually mattered.** The load-distribution test lost
> tickets to "no agent available" once, in a long full-suite run, never in isolation —
> five workers claiming five agents at once is the tightest possible ratio for
> `SKIP LOCKED`, and `AgentAssigner`'s row lock was held through several *more* writes
> (SLA records, event rows) after the claim before the transaction committed. Moving the
> claim to be the last write before commit shrinks that window to acquire → increment →
> commit; held clean across five stress runs and a full verify afterwards. Worth stating
> plainly: this was found by a test failing once in fourteen minutes and not chased away
> by re-running it until it passed.

**Next: Phase 7 — knowledge base, retrieval and grounded draft replies.**

Phases 4–10 are planned at task level: **241 tasks, ~135 working days.**
See [docs/planning/](docs/planning/) — start with
[00-README](docs/planning/00-README.md).

---

## Deliberately not used

| | Why |
|---|---|
| **Kafka / RabbitMQ** | `persist + publish` is a dual write; you need a transactional outbox regardless. Once you have it, Postgres `SKIP LOCKED` already gives durable queueing, at-least-once delivery, retries and a DLQ — atomically with the domain write, which a broker cannot. At ~1 write/sec none of Kafka's advantages apply. |
| **Microservices** | No independent scaling or deployment driver. `triage` is the clean seam if that changes. |
| **Kubernetes** | Two runtime services. Compose is the honest answer. |
| **A dedicated vector DB** | ~50k vectors. pgvector keeps them transactional with domain data. |
| **Agent frameworks** | The pipeline has a fixed, known step count. A fixed pipeline is cheaper, deterministic and testable. |
| **Fine-tuning** | Few-shot exemplars solve this at a fraction of the cost. |

---

## Licence

MIT © 2026 Sunny Kr Singh
