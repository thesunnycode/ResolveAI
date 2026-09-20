# 01 — Idea Evaluation & Sharpening

> Skill: `idea-enhancer` · Runs before `project-planner` · Output: a locked, sharpened concept

---

## Input

| | |
|---|---|
| **Rough idea** | An AI-powered helpdesk / ticketing system that triages incoming tickets, routes them to the right team, enforces SLAs, and suggests grounded resolutions |
| **Purpose** | Portfolio + job interviews — replacing the E-Commerce REST API project for Java Backend / SDE-1 applications |
| **Tech stack** | Java 21 + Spring Boot (already the strongest part of the resume) |
| **Timeline** | ~8 weeks, from late September 2026 |
| **Current level** | Intermediate. Two Spring Boot projects shipped (one multi-tenant with 43 endpoints, one with Stripe integration), one backend internship. Comfortable with JPA, Spring Security, Flyway. **No experience with:** async processing, concurrency control, testing, AI/LLM work, Docker, or observability. |

---

## STEP 2 — Honest Evaluation of the Raw Idea

### A. Common-ness check

**Verdict: the domain is common. The idea as stated is medium-common. The differentiator is entirely in the execution.**

Let me be precise, because "helpdesk system" and "AI helpdesk system" are two different levels of saturation.

- **Plain ticketing system** — extremely common. It sits alongside e-commerce, library management and blog APIs as a standard Java portfolio project. On its own it signals nothing.
- **Ticketing + "AI suggests a reply"** — increasingly common since 2024, and here is the problem: it reads as *a CRUD app with a chat box*. An interviewer pattern-matches it in about five seconds. The phrase "AI drafts a reply, human approves" is structurally one step from "chatbot with a human in the loop."

**But** there is a genuine point in this idea's favour that most AI-bolted-on projects don't have: **ticket triage is a real AI job.** Classification and routing are things a company actually pays a model to do, unlike "AI product recommendations" or "AI transaction categorisation", which are decorations. Apply the remove-the-LLM test and triage degrades meaningfully — you go back to a human reading every ticket and guessing.

So the raw idea is not dead on arrival. It is *undifferentiated*, which is a fixable problem, and the fix is not to change domains — it is to move the centre of gravity away from "AI drafts a reply."

### B. Complexity check

**Verdict: correctly sized for 8 weeks — but the raw version is complex in the wrong places.**

Too simple, as stated:
- "Tickets, agents, teams, statuses" is CRUD. You have already built CRUD three times (Redalis, Hyperlocal, E-Commerce). It adds nothing to the resume.
- "SLA basics" — one word doing enormous work. As written it means a `due_at` column and a cron job, which is ~4 hours of work and teaches nothing.

Too vague to be complex at all:
- "Escalation engine", "duplicate candidates", "priority prediction" all appear as single bullet points with no design behind them. These are the *interesting* parts and they're currently one-liners.

The honest read: **the raw idea has roughly three weeks of real engineering in it and five weeks of CRUD.** That ratio is backwards and it is what needs fixing.

### C. Learning value check

What the raw idea teaches you that you don't already have:

| Skill | Covered by raw idea? | Notes |
|---|---|---|
| Async / background processing | ✅ Yes | **The single biggest gap in your portfolio.** Everything you've built is synchronous request→response. |
| Concurrency & locking | ⚠️ Mentioned, not designed | "Optimistic locking" appears with no contention scenario attached |
| Testing | ❌ Absent | The word "test" appears nowhere on your current resume. This is a visible red flag for product companies. |
| PostgreSQL (beyond MySQL) | ✅ Yes | You used Postgres at Redalis but list only MySQL |
| Redis as *your* decision | ✅ Yes | Currently it only appears under someone else's internship |
| AI/LLM engineering | ⚠️ Shallow as stated | "Call an LLM, get a category" is a two-hour task with Spring AI |
| RAG / retrieval | ✅ Yes | Genuinely useful here |
| Docker / CI / observability | ⚠️ Listed in Phase 4, easy to skip | |

Interview-relevant concepts **missing** from the raw idea: idempotency, the dual-write problem, exactly-once side effects, business-calendar arithmetic, evaluation of a non-deterministic system, cost control, PII handling.

### D. Interview story check

**Can you talk about the raw version for five minutes and sound impressive? Partially — and it dies under follow-up.**

The dangerous moment is question two. You say *"I built an AI helpdesk that classifies tickets and suggests replies."* The interviewer asks:

> "How do you know the classification is right?"

If the answer is "it usually is", the conversation is over and everything after it is discounted. That question has no good answer in the raw idea, because there is no evaluation.

Second dangerous question:

> "So the LLM decides the priority?"

If yes, you have handed them an unexplainable, untestable, non-deterministic business rule and they will pull that thread for ten minutes.

**What the raw idea does give you:** a legitimate reason to discuss RAG, hybrid search and async processing. That's real. It just isn't enough on its own, because a lot of candidates will have that in 2026.

### Summary of the evaluation

The domain is right. The stack fit is excellent. The gap coverage is excellent. **The problem is that the raw idea's marquee feature is its weakest one**, and the genuinely hard engineering is buried in throwaway bullet points. The three versions below differ in exactly one way: *where the centre of gravity sits.*

---

## STEP 3 — Three Enhanced Versions

### 🔵 VERSION 1 — Safe & Solid

**"Async Helpdesk with AI Triage"**

- **What it is:** A multi-role ticketing backend where ticket triage (category, team, priority) happens asynchronously on a background worker instead of in the request thread, with a basic SLA timer and escalation.

- **The unique angle:** Not the AI — the **asynchronicity**. `POST /tickets` returns `202` immediately; the AI analysis appears later and the UI polls for it. That single architectural decision is the thing your portfolio is missing, and it is visible in the API contract itself.

- **Key features that add depth:**
  1. Transactional outbox so the ticket insert and the event publish are atomic (solves the dual-write problem)
  2. Background workers claiming jobs with `SELECT … FOR UPDATE SKIP LOCKED`, with retries, backoff and a dead-letter queue
  3. SLA deadlines stored as absolute timestamps, polled by a single worker — survives restarts and multiple instances
  4. Structured LLM output (JSON schema) for classification, never free text

- **What you'll learn:** transactional outbox pattern · job-queue semantics in Postgres · visibility timeouts and at-least-once delivery · Java 21 virtual threads on I/O-bound work · Spring AI structured output converters · Testcontainers integration testing

- **Realistic timeline:** 4–5 weeks

- **Interview talking points:**
  1. *"Why an outbox instead of publishing after commit?"* — the dual-write failure mode, explained with the exact scenario where a ticket is created and never triaged
  2. *"What happens if a worker dies mid-job?"* — visibility timeout, redelivery, and why the work is idempotent
  3. *"Why did `POST /tickets` return 202 instead of 201?"* — never block an HTTP thread on a 2–8 second LLM call

- **Best for:** Getting a solid, finishable project done with minimum risk. Fixes the biggest portfolio gap (async) and nothing else.

---

### 🟡 VERSION 2 — Stronger Angle

**"Helpdesk with a Real SLA Engine"**

Everything in Version 1, plus the SLA engine promoted from a footnote to the centre of the project.

- **What it is:** The same async helpdesk, but SLAs are computed in **business hours against a configurable calendar**, the clock **pauses** when a ticket is waiting on the customer, and a multi-rung escalation ladder fires **exactly once** per rung under an at-least-once poller.

- **The unique angle:** This is the part of every ticketing system that everyone lists and nobody builds. It is 100% deterministic, 100% testable, genuinely difficult, and — the key property — **it gives you a 45-minute technical conversation that works even if the interviewer doesn't care about LLMs at all.** That insurance is worth a great deal.

- **Key features that add depth:**
  1. Business-hours arithmetic as a pure function — "4 business hours from Friday 16:30 IST, working day ends 18:00, Monday is a holiday" — with property-based tests (additivity, monotonicity, round-trip)
  2. Append-only `sla_clock_segment` table with a partial unique index enforcing exactly one open segment; elapsed time is always `SUM()`, never a mutable counter
  3. Absolute `next_deadline_at` + one `SKIP LOCKED` poller — recovers automatically after downtime because deadlines don't drift
  4. Escalation rungs made exactly-once by `UNIQUE (sla_record_id, rung)` — a redelivery hits the constraint and no-ops instead of emailing the team lead twice
  5. Breach *prediction* from a p75 over historical resolution times — **deliberately not an LLM**

- **What you'll learn:** everything in V1 · timezone and DST arithmetic · property-based testing with jqwik · append-only modelling and derived state · idempotency with a real, non-contrived reason · why a SQL percentile beats a model

- **Realistic timeline:** 5–6 weeks

- **Interview talking points:**
  1. *"How do you schedule a million timers?"* — you don't; absolute deadlines + one indexed poller, cost proportional to *due* tickets not *open* tickets
  2. *"Your worker was down for an hour. What was lost?"* — nothing. An in-memory `ScheduledExecutorService` would have silently dropped every escalation.
  3. *"Why no `elapsed_minutes` column?"* — lost update under concurrency; derived state from an append-only log is reconstructible and auditable
  4. *"Why isn't breach prediction an LLM?"* — because a percentile over 90 days of history is faster, cheaper, more accurate and explainable, and knowing when *not* to use a model is the point

- **Best for:** Maximum backend-interview leverage. This is the version that survives an interviewer who is sceptical of AI projects.

---

### 🔴 VERSION 3 — Bold Choice

**"ResolveAI — AI-Assisted Helpdesk & Incident Triage Platform"**

Everything in Version 2, plus two mechanisms that change what category the project is in.

- **What it is:** An incident triage platform. On top of the async pipeline and the SLA engine, it (a) detects when many tickets are about the *same underlying outage* and collapses them into one incident with fan-out resolution, and (b) generates resolution drafts as individually-cited claims that are **verified and dropped** if unsupported.

- **The unique angle:** The governing principle inverts the usual AI-project shape — **the AI produces signals, deterministic code makes decisions.** The model never sets a priority, never routes a ticket, never declares that an incident exists, and never ships a customer-facing sentence that hasn't been checked against a retrieved span. That single sentence, said in the first twenty seconds, prevents the chatbot pattern-match from ever firing.

- **Key features that add depth:**
  1. **Incident correlation.** A sliding window clusters tickets by embedding similarity plus shared structural entities (error code, service, region). An incident is proposed only when `cluster_size ≥ K` **and** arrival rate exceeds a 4-week baseline by 3× — a statistical gate, not a model judgement. The LLM only writes the title. A human confirms. Then one status update fans out to all linked tickets with per-ticket delivery idempotency.
  2. **Citation-enforced drafting.** Generation returns `{claims: [{text, citations[]}]}`, not prose. A free deterministic pre-filter drops any claim containing a number or identifier absent from its cited span; an entailment check drops the rest. Below 0.8 coverage the draft is suppressed and the ticket escalates. Groundedness is measured **per claim**, not per response.
  3. **Signals, not decisions.** The LLM returns `TriageSignals` (impact, service-down claimed, payment affected, linguistic urgency); a versioned pure function computes priority from those plus plan tier, incident linkage and reopen count. Every priority is explainable to a customer and testable without a model call.
  4. **A CI evaluation gate.** Classification accuracy, Recall@5/MRR, claim-level groundedness, and a **refusal suite** where the correct answer is "I don't know, escalate" — which must pass 100% or the build fails.
  5. **PII redaction + per-tenant AI governance.** Deterministic redaction to placeholders before anything leaves for a third-party model, rehydrated on return; an `external_model_allowed` flag routes a tenant to a local model or disables AI entirely, with the ticket workflow fully functional either way.

- **What you'll learn:** everything in V2 · embeddings and hybrid retrieval (pgvector + tsvector + Reciprocal Rank Fusion) · entity resolution · evaluation of non-deterministic systems · hallucination control as a *mechanism* rather than a prompt instruction · layered caching with a correctness argument · model routing and cost control · read-only tool calling and why it makes prompt injection structurally irrelevant

- **Realistic timeline:** 7–8 weeks, built in the stated order. Honestly assessed, not padded.

- **Interview talking points:**
  1. *"Where is the LLM allowed to influence a decision?"* — nowhere. It can raise a flag through its signals; it can never clear one. Every tool is read-only, so there is nothing a prompt injection could make the agent *do*.
  2. *"How do you know your AI works?"* — a labelled golden set, claim-level groundedness, and a refusal suite gated in CI. Then the sharper version: *why* response-level groundedness is misleading and claim-level isn't.
  3. *"Two agents click Assign at the same instant."* — conditional update, affected-row count, 409. And then: *"the least-loaded-agent assignment in my earlier project has this bug; here's the fix and the test."*
  4. *"Why didn't you cache the generated drafts?"* — because two tickets can be semantically near-identical and need opposite answers, since the answer depends on that customer's order state. A semantic cache there is a data-leak-shaped bug wearing a performance costume.

- **⚠️ Watch out for:**
  - **Scope creep into the frontend.** Four plain screens, Tailwind, no animation. Every hour on the UI is an hour not spent on what you'll be interviewed about.
  - **The cold-start trap.** With zero historical tickets, retrieval shows nothing and your best feature demos as a blank box. Write and commit the seed generator in Week 1, not Week 6.
  - **Building AI first.** If you start with the LLM because it's the fun part, the SLA engine slips and you end up with the common half of the project and none of the rare half.
  - **Adding RabbitMQ/Kafka for the resume line.** You need a transactional outbox regardless; the broker then adds a container without adding a capability. Refusing it with reasons interviews better than using it.

---

## STEP 4 — Recommendation

**Build Version 3. Build it in Version 2's order.**

Reasoning, in priority order:

1. **Version 1 doesn't solve the actual problem.** Your portfolio's problem is not "no async" in isolation — it's that Redalis, Hyperlocal and E-Commerce all say *the same sentence*: "CRUD REST APIs secured with JWT and RBAC." Version 1 fixes one gap and still reads as a fourth CRUD app. Not worth 5 weeks.

2. **Version 2 is the floor, not the target.** It is genuinely strong and it is what you fall back to if time runs out. But it contains no AI engineering worth the name, and you specifically need an AI story for 2026 hiring.

3. **Version 3's extra cost is small and lands entirely in weeks 5–6**, on top of a project that is already complete and demonstrable at week 4. That is a good risk profile: the ambitious part is additive, not foundational.

4. **The incident correlation feature is the whole argument.** It is the one place where embeddings do something no rule could — "card declined", "money deducted no order" and "UPI not going through" share almost no tokens and cluster perfectly in embedding space. It also produces the best 60-second demo you will ever have: forty tickets in, one incident out, one agent action closes all forty. Without it you have a helpdesk. With it you have the platform the name claims.

5. **The 8-week timeline is real for you specifically** because roughly 30% of Week 1 is porting auth from Hyperlocal rather than writing it.

**The one condition:** build in the order given — deterministic backend first (Weeks 1–2), AI second (Weeks 3–6). If you invert it, the risk lands on the part you cannot cut.

**Fallback if you hit week 6 and you're behind:** ship Weeks 1–3 + 6. That is async triage, a real SLA engine, and incident correlation — and it is still a markedly better project than Version 2. Drop retrieval and drafting (Weeks 4–5). Counter-intuitive, but they are also the most common thing in everyone else's portfolio, so they are the cheapest thing to lose.

---

## STEP 5 — Final Sharpened Idea

> Confirmed. This is what gets handed to the `project-planner` skill.

```
PROJECT NAME: ResolveAI — AI-Assisted Helpdesk & Incident Triage Platform

CORE CONCEPT:
  A multi-tenant helpdesk backend where incoming tickets are triaged
  asynchronously, SLAs are enforced against real business calendars with
  pausable clocks, and bursts of related tickets are automatically collapsed
  into a single incident. An LLM reads tickets and produces structured
  signals and individually-cited resolution drafts; deterministic, versioned
  policy code makes every decision — priority, routing, escalation, breach.
  The model never decides anything and never ships an unverified claim.

TARGET USERS:
  Primary   — Support agents at a 50–500 person B2B SaaS company
  Secondary — Team leads (assignment, SLA policy, analytics)
  Tertiary  — Customers (raise and track their own tickets)
  Admin     — Knowledge base, AI governance, evaluation dashboards

KEY FEATURES (confirmed, MVP):
  - Ticket lifecycle with an explicit state machine and full audit trail
  - Four-role access control with tenant isolation
  - SLA engine: business-hours arithmetic, pausable append-only clocks,
    absolute deadlines, exactly-once escalation ladder, breach prediction
  - Async triage pipeline: transactional outbox + SKIP LOCKED workers on
    virtual threads, retries, backoff, DLQ
  - AI emits TriageSignals; a versioned pure function computes priority
    and a deterministic router assigns the team
  - Knowledge base with hybrid retrieval (pgvector + tsvector + RRF)
  - Citation-enforced resolution drafts with claim-level verification
    and a coverage threshold that suppresses weak drafts entirely
  - Incident correlation: embedding clustering behind a deterministic
    arrival-rate gate, human confirmation, idempotent fan-out
  - PII redaction and per-tenant external-model policy
  - CI evaluation gate including a refusal suite

TECH STACK:
  Java 21, Spring Boot, Spring Security, Spring AI, Spring Data JPA,
  PostgreSQL 16 + pgvector, Redis, Flyway, MinIO (S3), Docker Compose,
  Testcontainers, JUnit 5 + jqwik, GitHub Actions,
  Micrometer + Prometheus + Grafana, React + Tailwind (4 screens only)

  Deliberately refused: Kafka, RabbitMQ (MVP), Kubernetes, microservices,
  a dedicated vector DB, agent frameworks, fine-tuning, GraphQL

PURPOSE: Portfolio + job interviews (Java Backend / SDE-1)

TIMELINE: 8 weeks — 7 build + 1 write-up. From late September 2026.

COMPLEXITY LEVEL: Advanced
  (justified: two shipped Spring Boot projects and a backend internship
   already cover auth, JPA, schema design and REST; roughly 30% of Week 1
   is porting rather than writing)
```

✅ **Idea locked in.** Next: [02 — Project Plan](02-PROJECT-PLAN.md), generated by the `project-planner` skill.
