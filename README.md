# ResolveAI

**AI-Assisted Helpdesk & Incident Triage Platform**

Java 21 · Spring Boot · Spring AI · PostgreSQL + pgvector · Redis · Docker

> A helpdesk backend where **the AI never decides anything.** The model emits structured
> signals; versioned deterministic policy functions compute every priority, route,
> escalation and SLA breach.

🚧 **Under active development — Phase 1 of 10.** [Build status below.](#build-status)

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
| PostgreSQL | `localhost:5432` | `resolveai` / `resolveai_local` |
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

**Next: Phase 2 — migrations, structural guarantees, OpenAPI, Postman** (9 days).

Phases 2–10 are planned at task level: **242 tasks, ~135 working days.**
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
