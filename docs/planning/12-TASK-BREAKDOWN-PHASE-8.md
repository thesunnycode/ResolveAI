# 12 — Task Breakdown: Phase 8

> Skill: `task-breakdown` · Run once per phase · **Re-run at the start of Phase 9/10**

**Phase:** Phase 8 — Incident Correlation
**Goal:** Bursts of semantically related tickets are detected behind a deterministic statistical gate, proposed for human confirmation, and resolved with one action that fans out idempotently to every linked ticket.
**Tech stack:** Java 21 · Spring Boot 3.3.x · PostgreSQL 16 + pgvector · Redis · Spring AI · Testcontainers

**Phase 7 is skipped** — you have taken Path C. Phase 8 does not depend on it: everything it needs is ticket embeddings (Phase 6 Task 19) and the worker runtime (Phase 6 Task 3), both already delivered. The dependency on "Phase 7" in [07](07-DEV-PHASES.md) was to the embedding infrastructure, which moved earlier.

---

# ⚠️ The reckoning

## Fourth consecutive estimate miss

| Phase | [07](07-DEV-PHASES.md) | Broken down | Factor |
|---|---|---|---|
| 4 | 4–5 d | 10–11 | 2.2× |
| 5 | 8–11 d | 19–23 | 2.2× |
| 6 | 6–8 d | 21 | 2.8× |
| **8** | **5–6 d** | **16–17** | **3.0×** |

## And a correction to my own advice

In [11](11-TASK-BREAKDOWN-PHASE-6.md) I put Path C at **~11–12 weeks**. **That was wrong** — I subtracted Phase 7 from a full-scope total without re-checking what remained. Working from the phases that have actually been broken down:

| Phase | Path C days |
|---|---|
| 1 — Setup | 4–5 |
| 2 — Design | *done* |
| 3 — Boilerplate | 7 |
| 4 — Auth | 11 |
| 5 — Ticketing + SLA *(minus attachments)* | 23 |
| 6 — Async + AI triage *(lean)* | 18 |
| 7 — Knowledge & drafting | **cut** |
| **8 — Incident correlation** | **17** |
| 9 — Testing & hardening *(reduced — most tests written inline)* | 7 |
| 10 — Deploy & docs | 8 |
| **Total** | **~95 days ≈ 20 weeks** |

**Cutting Phase 7 saved 14 days out of 110. It was never going to be the answer.**

The honest conclusion: **ResolveAI at the depth it is designed is a four-to-five month project, and no trim makes it ten weeks without gutting what makes it good.** I should have said that after Phase 5 rather than proposing a trim that did not close the gap.

## The reframe that actually solves this

**You do not need a finished project to apply. You need a strong project on the day you apply.** Those are different problems, and the second one has a good answer.

Build in the order already specified and **update the resume at each checkpoint.** Every milestone adds a bullet that is true the day it lands:

| Milestone | ~Week | What you can honestly claim |
|---|---|---|
| **5C complete** | 9 | *"SLA engine with business-hours arithmetic, pausable append-only clocks and restart-safe deadline polling; escalation ladder made exactly-once under an at-least-once poller via partial unique constraints."* Plus the concurrency bullet. **Two of five bullets. Already beats the e-commerce project it replaces.** |
| **6A complete** | 11 | + *"Asynchronous pipeline on a transactional outbox with `SKIP LOCKED` workers on virtual threads, visibility timeouts, jittered backoff and a DLQ."* **Three of five.** |
| **6D complete** | 15 | + *"LLM triage emitting structured signals into a versioned deterministic policy; PII redaction before any external call; classification accuracy gated in CI."* **Four of five.** |
| **8 complete** | 19 | + *"Incident correlation collapsing a burst of N related tickets into one human-confirmed incident behind a statistical arrival-rate gate, with idempotent per-ticket fan-out."* **All five, and the differentiator.** |

**One change to the plan, and make it now: do a minimal Phase 10 immediately after 6A.** Dockerfile, deploy, seeded demo, README. Two days. From week 11 onward you always have a live URL and a written architecture story — which is what a reviewer actually looks at. Then keep building against the deployed instance rather than deploying once at the end.

**This is the recommendation.** Not a smaller project — a project that is presentable from week 9 and keeps getting better.

---

## Sub-phase structure

| | Sub-phase | Tasks | Effort | Checkpoint |
|---|---|---|---|---|
| **8A** | Entities & deterministic extraction | 1–4 | ~8.5 h ≈ 3 days | Entities parsed and stored; no clustering yet |
| **8B** | Clustering & the gate | 5–11 | ~17 h ≈ 5 days | A seeded storm produces a proposed incident |
| **8C** | Incident lifecycle | 12–17 | ~12 h ≈ 4 days | Confirm, reject, link, detach, resolve |
| **8D** | Fan-out | 18–20 | ~6 h ≈ 2 days | One update reaches N tickets idempotently |
| **8E** | Demo, tests & close | 21–27 | ~12 h ≈ 3 days | `storm.sh`, precision test, benchmarks |

---

# PHASE 8A — Entities & Deterministic Extraction

## TASK 1 — Create the four incident entities and reference generation

**📝 Description**
Create `Incident`, `IncidentTicket`, `IncidentUpdate`, `IncidentUpdateDelivery` in `com.resolveai.incidents.domain` per [04 §Group 7](04-DATABASE-SCHEMA.md).

Carry forward: `@TenantId` on `Incident`, `@Enumerated(STRING)`, `@Version` on `Incident`, LAZY everywhere.

`IncidentTicket` is an **association entity with its own identity** — it carries `linkConfidence`, `linkedBy`, `detachedAt` — so map it as a full `@Entity`, never as a `@ManyToMany`.

Reference generation reuses `ReferenceGenerator` from [10 Task 2](10-TASK-BREAKDOWN-PHASE-5.md) with `EntityType.INCIDENT` → `INC-204`. The `tenant_sequence` table already supports it; no new migration needed.

Store the gate's evidence on the incident itself: `clusterSizeAtDetection`, `arrivalRateMultiple`, `firstTicketAt`, `detectedAt`. **`generatedByModel` is nullable and null means the title was templated** — honest provenance, and the API exposes it.

**⛓️ Dependencies**
None.

**✅ Expected Output**
Four entities compile; `ddl-auto: validate` passes. `ReferenceGenerator.next(tenantId, INCIDENT)` returns `INC-1000`.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 2 — Create the incident repositories

**📝 Description**
Four repositories. Three queries need writing:

- `findLiveIncidents(tenantId)` — `status IN ('PROPOSED','CONFIRMED','MITIGATED')`, using `idx_incident_open`
- `findLinkedTickets(incidentId)` — `WHERE detached_at IS NULL`, using the partial index
- `findLiveLinkFor(ticketId)` — returns at most one row, backed by `uq_incident_ticket_live`

Add `IncidentUpdateDeliveryRepository.markSent(deliveryId)` as a conditional update returning the affected count — Task 19 needs the count to detect a redelivery.

**⛓️ Dependencies**
Task 1.

**✅ Expected Output**
Repositories compile; context starts. A test confirms `findLiveLinkFor` returns empty for a detached link.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 3 — Implement deterministic entity extraction

**📝 Description**
**A schema addition is needed.** Write `V11__ticket_entity.sql`:

```sql
CREATE TABLE ticket_entity (
    id          BIGSERIAL   PRIMARY KEY,
    ticket_id   BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    entity_type VARCHAR(24) NOT NULL
                CONSTRAINT ck_entity_type CHECK (entity_type IN
                  ('ERROR_CODE','SERVICE','REGION','PAYMENT_METHOD','APP_VERSION','HTTP_STATUS')),
    entity_value VARCHAR(80) NOT NULL,
    CONSTRAINT uq_ticket_entity UNIQUE (ticket_id, entity_type, entity_value),
    CONSTRAINT fk_te_ticket FOREIGN KEY (ticket_id) REFERENCES ticket(id) ON DELETE CASCADE,
    CONSTRAINT fk_te_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE RESTRICT
);
-- the boost lookup: "which other recent tickets mention payment-service?"
CREATE INDEX idx_ticket_entity_lookup ON ticket_entity(tenant_id, entity_type, entity_value);
CREATE INDEX idx_ticket_entity_ticket ON ticket_entity(ticket_id);
```

Implement `EntityExtractor.extract(text) → Set<TicketEntity>`:

| Type | Detection |
|---|---|
| `ERROR_CODE` | `ERR_[A-Z0-9_]+`, `E\d{3,5}`, Java exception class names |
| `HTTP_STATUS` | `\b[45]\d{2}\b` **in an error-like context** — bare `404` in prose is noise |
| `SERVICE` | Exact match against a tenant-configurable service list |
| `REGION` | Cloud region patterns plus a configurable list |
| `PAYMENT_METHOD` | `UPI`, `card`, `netbanking`, `wallet`, `NEFT`, `IMPS` |
| `APP_VERSION` | Semver in a version-like context |

Call it from `TriageWorker` (Phase 6 Task 21) in `tx2`, on the **redacted** text.

**This is deliberately not the LLM's job.** Extraction here must be deterministic and reproducible because it feeds the *gate*, and the entire argument of this feature is that a model does not decide an incident exists. The LLM's `extractedEntities` in `TriageSignals` is a separate, advisory field — do not use it here.

**⛓️ Dependencies**
Phase 6 Task 21.

**✅ Expected Output**
*"Getting ERR_PAY_TIMEOUT on UPI checkout, 503 from payment-service in ap-south-1"* yields five entities of four types. Prose containing "404 pages of documentation" does **not** yield `HTTP_STATUS`.

**⏱️ Estimated Time**
3 hours.

---

## TASK 4 — Write the entity extraction test suite

**📝 Description**
Plain JUnit, no Spring context.

1. Three positive and three negative cases per entity type
2. Idempotence — extracting twice yields an identical set
3. **No false positives on 50 seeded ticket bodies that contain no incident signal.** Assert the extraction rate is below a threshold you commit to.
4. Order independence — the returned set does not depend on text order
5. Extraction on redacted text — `«CARD_1»` and `«ORDER_1»` produce no entities

**Test 3 is the one that matters.** Every false-positive entity inflates the shared-entity boost in Task 6 and makes unrelated tickets look correlated. The gate's precision is downstream of this suite.

**⛓️ Dependencies**
Task 3.

**✅ Expected Output**
All five green. Test 3 across the real seeded corpus, not synthetic strings.

**⏱️ Estimated Time**
1.5 hours.

---

# PHASE 8B — Clustering & the Gate

## TASK 5 — Implement the arrival-rate baseline

**📝 Description**
Write `V12__ticket_arrival_baseline.sql` creating a materialized view of ticket counts per `(tenant_id, day_of_week, hour_of_day)` averaged over the previous 28 days, excluding the last 24 hours, refreshed nightly `CONCURRENTLY`.

```sql
SELECT tenant_id,
       EXTRACT(DOW  FROM created_at)::int AS dow,
       EXTRACT(HOUR FROM created_at)::int AS hod,
       AVG(cnt) AS baseline_count,
       COUNT(*) AS sample_weeks
  FROM ( … hourly counts over 28 days, excluding the last 24h … ) s
 GROUP BY 1,2,3;
```

`BaselineService.baselineFor(tenantId, Instant) → Baseline(count, sampleWeeks, source)`.

**Handle cold start explicitly** — a new tenant has no 28-day history:
```
sampleWeeks >= 3  → use the (dow, hour) baseline        source = SPECIFIC
sampleWeeks 1–2   → use the tenant's global hourly mean  source = GLOBAL_HOURLY
sampleWeeks = 0   → use a configured floor (default 2)   source = FLOOR
```
Return the `source` and surface it in the incident's detection evidence. **A gate that silently falls back to a made-up baseline is a gate that fires arbitrarily on a new tenant's first busy Monday**, and you would have no way to see why.

Excluding the last 24 hours matters: including today means a live storm inflates its own baseline and suppresses detection.

**⛓️ Dependencies**
None.

**✅ Expected Output**
With seeded 4-week history, the baseline for Friday 14:00 matches a hand-computed average. A tenant with 5 days of data returns `GLOBAL_HOURLY`. A brand-new tenant returns `FLOOR`.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 6 — Implement the clustering pass

**📝 Description**
`TicketClusterer.cluster(List<CandidateTicket>) → List<Cluster>` — a pure function over tickets already loaded with embeddings and entities.

**Algorithm — leader clustering, not single-linkage:**
```
1. Compute the pairwise similarity matrix, O(N²) in memory
   sim(a,b) = cosine(a.embedding, b.embedding)
            + entityBoost × |a.entities ∩ b.entities| / |a.entities ∪ b.entities|
2. Pick the ticket with the most neighbours above τ as the seed
3. Gather every ticket within τ of the seed into a cluster
4. Remove them; repeat from 2 until no seed has ≥ 2 neighbours
5. Return clusters sorted by size
```

**Why leader clustering rather than single-linkage:** single-linkage chains — if A~B and B~C but A≁C, all three merge, and with 200 tickets in a window that reliably produces one giant cluster containing three unrelated outages. Leader clustering keeps every member within τ of a single seed, which is both more controllable and easier to explain.

**Why O(N²) in memory rather than a pgvector query per ticket:** N is 20–200 in a 30-minute window. 200² = 40,000 cosine comparisons over 768 dimensions is roughly 30 ms in Java. Doing it with 200 round trips to Postgres would be slower and far harder to test. **Load once, compute in memory, and say so when asked why you did not use the vector index** — the index is for search over 50,000 chunks, not for an all-pairs comparison over 200 rows.

Make `τ` and `entityBoost` configuration properties, not constants — Task 11 tunes them.

**⛓️ Dependencies**
Tasks 3, 4.

**✅ Expected Output**
A pure unit test with hand-crafted embedding vectors: three tight clusters plus five outliers produces exactly three clusters and leaves the outliers unclustered. Runs in under 100 ms for N = 200.

**⏱️ Estimated Time**
3.5 hours.

---

## TASK 7 — Implement `CorrelationGate` as a pure function

**📝 Description**
**The component the entire feature's credibility rests on.** No Spring, no repository, no I/O.

```java
public GateDecision evaluate(Cluster cluster, Baseline baseline, GateConfig config);
```

```
propose only when ALL hold:
   cluster.size >= config.minClusterSize            (default 5)
   AND arrivalRate > config.minRateMultiple × baseline.count   (default 3.0)
   AND cluster.windowMinutes <= config.maxWindowMinutes        (default 30)
   AND no live incident already covers > 50% of these tickets
```
where `arrivalRate` is the cluster's ticket count over the window, normalised to an hourly rate so it is comparable with the baseline.

Return a `GateDecision` carrying the verdict **and every input with its threshold**, so the UI can render *"38 tickets · 12.6× baseline · gate: ≥5 AND ≥3× — both passed"*.

**The fourth condition prevents a second incident being proposed for a storm that is already being handled** — without it, a burst that keeps arriving after confirmation proposes a duplicate every sweep.

**No model has a vote in this function.** That sentence is the feature's argument, and this class is where it is either true or not.

**⛓️ Dependencies**
Tasks 5, 6.

**✅ Expected Output**
Compiles with zero Spring or JPA imports. A cluster of 38 against a baseline of 3 proposes; a cluster of 4 does not; a cluster of 40 against a baseline of 35 does not.

**⏱️ Estimated Time**
2 hours.

---

## TASK 8 — Write the gate boundary tests

**📝 Description**
Plain JUnit, table-driven. Test **every boundary from both sides**:

| Case | Expected |
|---|---|
| size 4 / 5 / 6 at 10× baseline | reject / propose / propose |
| 2.9× / 3.0× / 3.1× baseline at size 10 | reject / reject / propose |
| window 29 / 30 / 31 min | propose / propose / reject |
| baseline 0 (new tenant, `FLOOR`) | uses the floor, does not divide by zero |
| 60% overlap with a live incident | reject |
| 40% overlap with a live incident | propose |

Add a property test: the decision is **monotonic in cluster size** — if a cluster of N proposes, N+1 with the same baseline and window also proposes.

**Testing both sides of each boundary is what separates a gate you can defend from a gate you hope works.** Off-by-one on `>=` versus `>` is the single most likely bug here, and it is invisible in a demo.

**⛓️ Dependencies**
Task 7.

**✅ Expected Output**
All boundary cases green in under 50 ms. The monotonicity property holds over 500 random inputs.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 9 — Implement the correlation sweep worker

**📝 Description**
`CorrelationSweepWorker`, `@Scheduled(fixedDelay = 60_000)`, per tenant.

```java
for (Long tenantId : activeTenantIds()) {
    String token = UUID.randomUUID().toString();
    if (!redis.setIfAbsent("lock:correlate:" + tenantId, token, Duration.ofSeconds(55)))
        continue;                                    // FAIL CLOSED — skip, do not run
    try {
        TenantContext.runAs(tenantId, () -> sweep(tenantId));
    } finally {
        releaseIfOwner(token);                       // Lua CAS, not a bare DEL
    }
}
```

`sweep()`: load open tickets from the last 30 minutes **with embeddings and entities** in one query → cluster → for each cluster, fetch the baseline and evaluate the gate → propose.

**Fail closed on the lock, and it is the opposite of the rate limiter's posture.** Two sweeps running concurrently would propose duplicate incidents for the same storm — visible, confusing, and it would undermine trust in the feature immediately. Skipping a 60-second sweep costs at most one sweep of latency.

**Release with a Lua compare-and-delete, not `DEL`.** A bare `DEL` after the TTL expired would delete *another* instance's freshly acquired lock.

Tickets with a null embedding (triage failed) are excluded — log a count at DEBUG, because a rising count means AI is degraded and correlation is silently weakening.

**⛓️ Dependencies**
Tasks 6, 7, and Phase 6 Task 3.

**✅ Expected Output**
A test seeds 38 correlated tickets, runs one sweep, and asserts one `PROPOSED` incident. Two concurrent sweeps produce one incident, not two. A sweep with the lock held elsewhere is a clean no-op.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 10 — Implement title generation with template fallback

**📝 Description**
`IncidentTitleGenerator.generate(Cluster) → TitleResult(title, summary, modelId)`.

Seed `incident_title@1` as a `PromptVersion` (`V13__seed_incident_prompt.sql`). Input: the five most representative ticket subjects plus the shared entities. Output: a title under 80 characters and a two-sentence summary. Schema-constrained, temperature 0, via `ModelRouter`.

**The fallback is mandatory, not a nicety:**
```java
try {
    return modelRouter.call(...);
} catch (LlmUnavailableException | BudgetExhaustedException e) {
    return TitleResult.templated(
        "%d related tickets — %s".formatted(cluster.size(), topSharedEntity(cluster)),
        null);          // modelId null ⇒ templated
}
```

**This is the clearest expression of the architecture in the whole project.** The LLM's entire contribution to incident correlation is a human-readable title. When it is unavailable, incidents are still detected, still gated, still proposed, still confirmable, still fan-out-able — the product degrades by exactly one cosmetic field. If you can demo the feature with the provider switched off, you have proved the claim rather than asserted it.

**⛓️ Dependencies**
Task 9, Phase 6 Task 16.

**✅ Expected Output**
With the provider up, a sensible title and `generatedByModel` populated. With WireMock returning 503, a templated title, `generatedByModel = null`, and the incident otherwise identical.

**⏱️ Estimated Time**
2 hours.

---

## TASK 11 — Tune τ, `entityBoost`, K and M against the seeded storms

**📝 Description**
**Not a coding task — a measurement task.** Budget the time; it is the difference between a feature that works and a feature that demos.

1. Build the tuning set from the Phase 1 corpus: the **three planted storms** (positives) plus at least **three synthetic uncorrelated bursts** — 30 tickets about 30 different things arriving in ten minutes, which is what a Monday morning looks like.
2. Write `CorrelationTuningTest` that sweeps `τ ∈ [0.70 … 0.92]` step 0.02 and `entityBoost ∈ [0.0 … 0.3]` step 0.05, and reports **precision, recall and time-to-detect** for each combination as a table.
3. Pick the operating point, commit the chosen values to `application.yml`, and **commit the results table into the README.**
4. Do the same for K and M against the gate.

**Optimise for precision over recall, and say why.** A missed correlation costs you the fan-out saving on one outage. A false incident links 38 unrelated customers to a problem none of them have, pauses 38 resolution clocks, and destroys the team's trust in the feature permanently. Target precision ≥ 0.95 and take whatever recall that gives.

**"I tuned these thresholds against a labelled set and here is the precision/recall table" is a categorically different answer from "I picked 0.85 because it seemed reasonable."** It is also the cheapest credibility in the entire project.

**⛓️ Dependencies**
Tasks 6, 7, 9, and the Phase 1 corpus.

**✅ Expected Output**
A committed results table. Chosen values in config. Precision ≥ 0.95 on the tuning set. **Zero incidents proposed for any of the three uncorrelated bursts.**

**⏱️ Estimated Time**
3 hours.

---

### ✅ 8B checkpoint
- [ ] A seeded storm produces one `PROPOSED` incident within 90 seconds
- [ ] Three uncorrelated bursts produce **zero** incidents
- [ ] The gate's evidence is persisted and inspectable
- [ ] With the LLM down, incidents are still detected with templated titles

---

# PHASE 8C — Incident Lifecycle

## TASK 12 — Implement `GET /api/v1/incidents`

**📝 Description**
The board. Cursor-paginated, filtered by `status`, ordered `detected_at DESC`, role-scoped (`AGENT`+ read; confirm/reject is `TEAM_LEAD`+).

Each card carries: reference, title, status, `linkedTicketCount`, `timeToDetectSeconds`, and **the full `detection` object** — cluster size, arrival-rate multiple, baseline source, and the thresholds that were applied.

**The gate evidence goes on the card, not behind a click.** A team lead confirming an incident needs to see *"38 tickets · 12.6× baseline · gate: ≥5 AND ≥3× — both passed"* in the two seconds before they decide. It is also where the architectural argument becomes visible in the product rather than buried in a README.

Add the endpoint to the Phase 4 cross-tenant `@MethodSource`.

**⛓️ Dependencies**
Tasks 2, 9.

**✅ Expected Output**
Board returns proposed and live incidents with full detection evidence. An `AGENT` sees them; a `CUSTOMER` gets `403`.

**⏱️ Estimated Time**
2 hours.

---

## TASK 13 — Implement `GET /api/v1/incidents/{id}`

**📝 Description**
Full detail per [05 §3.5](05-API-CONTRACT.md): detection object, `titleGeneratedBy`, confirmation metadata, linked tickets with `linkConfidence`, update timeline with per-update delivery summaries, and the `ETag`.

Include **detached** links in a separate `detachedTickets` array rather than hiding them — a wrong link is useful history and it is the evidence behind the precision number.

**⛓️ Dependencies**
Task 12.

**✅ Expected Output**
Returns the full shape from the contract, including `timeToDetectSeconds` computed as `detected_at − first_ticket_at`.

**⏱️ Estimated Time**
2 hours.

---

## TASK 14 — Implement `POST /incidents/{id}/confirm`

**📝 Description**
`TEAM_LEAD`+. Requires `If-Match`. `PROPOSED` → `CONFIRMED` only; any other state → `409 INVALID_INCIDENT_STATE`.

In one transaction:
1. Set status, `confirmed_by`, `confirmed_at`
2. For each linked ticket: **pause the RESOLUTION clock** with reason `INCIDENT_LINKED`, **leave the FIRST_RESPONSE clock running**
3. Record `INCIDENT_LINKED` audit events
4. **Write an `eval_case` row** with `source = INCIDENT_CONFIRM` — the cluster and the human verdict become a labelled example
5. Return the `effects` object from [05 §3.5](05-API-CONTRACT.md)

**The clock decision is a product choice and the response makes it visible.** Resolution now depends on the incident, not the agent — so that clock pauses. But the customer still deserves an acknowledgement during an outage, so first-response keeps running. There is no universally right answer; surfacing `firstResponseClocksUnaffected: 38` in the response means nobody has to guess what happened.

Reuse `slaService.pause()` from [10 Task 25](10-TASK-BREAKDOWN-PHASE-5.md) — do not write a second pause path.

**⛓️ Dependencies**
Tasks 13, and Phase 5 Task 25.

**✅ Expected Output**
Confirming links 38 tickets, pauses 38 resolution clocks, leaves 38 first-response clocks running, writes one eval case. Confirming twice → `409`. Each affected ticket's `version` increments.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 15 — Implement `POST /incidents/{id}/reject`

**📝 Description**
`TEAM_LEAD`+. Mandatory `reason`, 10–500 characters. `PROPOSED` → `REJECTED`. No tickets are linked, no clocks touched.

**Write an `eval_case` with `source = INCIDENT_CONFIRM` and the negative label.** Rejections are the more valuable half of the labelled set: they are exactly the false positives your tuning in Task 11 is trying to eliminate, discovered in real use rather than in a synthetic test.

Surface it to the user — the UI copy in [06 §4.3](06-UI-UX-DESIGN.md) says *"Your decision improves detection accuracy"*, and it should be true.

**⛓️ Dependencies**
Task 14.

**✅ Expected Output**
Rejecting sets the status and reason, writes a negative eval case, and leaves every candidate ticket untouched. A reason under 10 characters → `400`.

**⏱️ Estimated Time**
1 hour.

---

## TASK 16 — Implement manual link and detach

**📝 Description**
`POST /incidents/{id}/tickets` — link a ticket manually, `linkedBy` set, `link_confidence` null (a human did not compute a cosine). Blocked by `uq_incident_ticket_live` if the ticket is already in a live incident → `409 TICKET_ALREADY_LINKED`.

`DELETE /incidents/{id}/tickets/{ticketId}` — set `detached_at` and `detached_by`, then **restore the ticket's own SLA**: close the `PAUSED` segment with reason `INCIDENT_LINKED`, open a new `RUNNING` one, recompute `next_deadline_at` from the elapsed total.

**Detach is trivially correct, and that is the payoff for the append-only design.** Because elapsed time is a `SUM()` over segments rather than a stored counter, restoring a clock is just resuming it — the time spent paused was never counted, so there is nothing to reconcile. Call `slaService.resume()` and nothing else. Say this when asked why the clock is append-only.

**⛓️ Dependencies**
Task 14, Phase 5 Task 25.

**✅ Expected Output**
Manual link works; linking an already-linked ticket → `409`. Detach restores the clock with the correct remaining budget — assert the elapsed total is unchanged by the pause/detach cycle. Re-linking after detach succeeds.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 17 — Implement `POST /incidents/{id}/resolve`

**📝 Description**
`TEAM_LEAD`+. Requires a `resolutionNote`. Sets the incident to `RESOLVED`, and for each linked ticket: resume the resolution clock, append the resolution note as a public message, transition to `RESOLVED`.

Route every ticket transition through `TicketStateMachine` — a linked ticket that an agent already moved to `CLOSED` must not be illegally transitioned. Skip those and **report them in the response** as `skipped: [...]` rather than failing the whole operation.

Optional `resolveLinkedTickets: false` for the case where the incident is fixed but individual tickets need follow-up.

**⛓️ Dependencies**
Tasks 14, 16.

**✅ Expected Output**
Resolving closes the incident and all 38 linked tickets in one action, with a resolution message on each. A ticket already `CLOSED` is skipped and named in the response. `resolveLinkedTickets: false` resolves only the incident.

**⏱️ Estimated Time**
2 hours.

---

# PHASE 8D — Fan-out

## TASK 18 — Implement `POST /incidents/{id}/updates`

**📝 Description**
`TEAM_LEAD`+. `Idempotency-Key` **required** — this reaches real customers. Body 1–5,000 chars, `visibility` defaulting to `INTERNAL`.

In **one** transaction:
1. Insert `incident_update`
2. Insert **N** `incident_update_delivery` rows, one per live linked ticket, `status = PENDING`
3. Publish **N** outbox events, one per delivery

Return `202` with `fanout: { total: N, status: "QUEUED" }` and the deliveries link.

**N independent delivery rows and N independent events, not one event carrying a list.** One event over N tickets means a single failure rolls back all N and re-sends to everyone on retry. Per-ticket rows are what make delivery 23 retryable in isolation — **and this is the one place in the whole project where a queue genuinely earns its keep**, as opposed to ticket creation where the outbox alone suffices.

Guard N: refuse with `422` above 500 linked tickets and require an explicit `force` — an accidental fan-out to thousands is not recoverable.

**⛓️ Dependencies**
Task 14, Phase 6 Task 2.

**✅ Expected Output**
Publishing to an incident with 38 linked tickets creates 1 update, 38 delivery rows and 38 outbox events, all in one transaction. Rolling back leaves zero of all three. Idempotency replay does not duplicate.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 19 — Implement `FanoutWorker`

**📝 Description**
Handles `INCIDENT_UPDATE_PUBLISHED`. Per event:

```java
int claimed = deliveryRepo.markSent(deliveryId);   // UPDATE … WHERE id=? AND status='PENDING'
if (claimed == 0) return;                          // already delivered — redelivery, no-op
// then: insert notification; if visibility = PUBLIC, append a public message to the ticket
```

The conditional update **is** the idempotency mechanism, backed by `uq_delivery (incident_update_id, ticket_id)`. A redelivered outbox event cannot message a customer twice.

On failure: increment `attempts`, record `last_error`, and let the outbox retry policy handle backoff. After max attempts, `status = FAILED` on the delivery row **and** `DEAD` on the event — so the failure is visible in both the incident UI and the DLQ.

Set `batchSize` to 50 — fan-out is the one workload where batching helps, since the work per event is small and local.

**⛓️ Dependencies**
Task 18, Phase 6 Tasks 3–4.

**✅ Expected Output**
38 events produce 38 notifications and 38 delivery rows marked `SENT`. Replaying one event produces no second notification. A forced failure on one delivery leaves the other 37 `SENT`.

**⏱️ Estimated Time**
2 hours.

---

## TASK 20 — Implement the delivery status endpoint

**📝 Description**
`GET /incidents/{id}/updates/{updateId}/deliveries` per [05 §3.5](05-API-CONTRACT.md): a summary of total/sent/pending/failed plus a `failures` array with ticket id, attempts and last error.

The UI polls this every 3 seconds until `pending = 0` to render the live counter `21/38 sent`.

**Exposing per-ticket delivery state is what makes the endpoint honest.** "Published" is not the same as "delivered", and a summary that only said "published" would hide exactly the failure this design exists to isolate.

**⛓️ Dependencies**
Task 19.

**✅ Expected Output**
Mid-fan-out returns a partial count. After completion, 38 sent, 0 pending. With one forced failure, 37 sent and 1 failure with its error.

**⏱️ Estimated Time**
1.5 hours.

---

# PHASE 8E — Demo, Tests & Close

## TASK 21 — Write `demo/storm.sh` and the storm corpus

**📝 Description**
**The most valuable 2.5 hours in this phase.** This script is the demo.

Write `demo/storm.json` — 38 realistic, *linguistically varied* tickets about one payment outage. They must share almost no vocabulary: *"card declined"*, *"money deducted no order"*, *"UPI not going through"*, *"checkout page error"*, *"paid twice charged once"*, *"payment stuck on processing"*.

**The variety is the point.** If they all say "payment failed", a keyword matcher would cluster them and your embedding-based approach demonstrates nothing. The tickets must be ones no rule could group.

`demo/storm.sh`: authenticate, POST 38 tickets over ~20 seconds with jittered gaps, poll the incident board, and print a timeline:
```
14:02:11  posted 12 tickets
14:02:34  posted 26 tickets
14:02:48  ⚠ INCIDENT PROPOSED — "UPI and card payment failures at checkout"
          38 linked · detected 37s after first report · 12.6× baseline
```

Add `demo/no-storm.sh` — 30 tickets about 30 unrelated things over the same window — and have it print **"no incident proposed ✓"**.

**Ship both.** The negative control is what turns "it found a pattern" into "it distinguishes a pattern from noise", and it is the difference between a demo and evidence.

**⛓️ Dependencies**
Tasks 9, 12.

**✅ Expected Output**
`./demo/storm.sh` reliably produces a proposed incident in under 90 seconds. `./demo/no-storm.sh` reliably produces none. Both runnable against the local stack and the deployed instance.

**⏱️ Estimated Time**
2.5 hours.

---

## TASK 22 — Test: one live incident per ticket

**📝 Description**
1. Link a ticket to incident A; linking to incident B → `409 TICKET_ALREADY_LINKED`, enforced by `uq_incident_ticket_live` rather than an application check
2. Detach from A, then link to B → succeeds
3. Two threads on a latch linking the same ticket to two different incidents → exactly one `200`, one `409`
4. The partial index permits many *detached* links for one ticket

**⛓️ Dependencies**
Task 16.

**✅ Expected Output**
All four green. Test 3 over 10 runs with no flake.

**⏱️ Estimated Time**
1 hour.

---

## TASK 23 — Test: merge during edit

**📝 Description**
1. Agent GETs ticket 88301 and holds its `ETag`
2. A team lead confirms an incident that links 88301, pausing its clock and bumping `version`
3. The agent PATCHes with the stale `ETag` → **`409 VERSION_CONFLICT`**
4. The agent re-fetches, sees the incident banner, and the retry succeeds

Also: the agent posts a *message* during confirmation. **Messages must still succeed** — a paused clock does not block a reply, and an agent mid-sentence when an incident is confirmed should not lose their work.

**Test 4's distinction is the interesting one.** `If-Match` guards *field mutations*; appending to a thread is not a mutation of the fields the ETag covers. Getting this wrong in either direction is bad: too strict and agents lose replies, too loose and you silently overwrite a concurrent edit.

**⛓️ Dependencies**
Tasks 14, Phase 5 Task 12.

**✅ Expected Output**
Stale PATCH → `409`. Concurrent message → `201`. Re-fetch and retry succeeds.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 24 — Test: partial fan-out failure isolation

**📝 Description**
1. Publish an update to 38 linked tickets with delivery 23 forced to fail
2. Assert: 37 `SENT`, 1 `FAILED` after max attempts, 37 notifications created
3. **Assert the other 37 received exactly one notification each** — not two. The whole point is that one failure does not re-send to everyone.
4. Retry the dead event; assert delivery 23 succeeds and **no additional notifications** are created for the other 37
5. Two `FanoutWorker` instances on the same 38 events → exactly 38 notifications

Test 4 is the one that proves the design. A naive implementation retries the whole update and double-messages 37 customers.

**⛓️ Dependencies**
Task 19.

**✅ Expected Output**
All five green over 10 runs.

**⏱️ Estimated Time**
2 hours.

---

## TASK 25 — Test: gate precision against uncorrelated bursts

**📝 Description**
**The credibility test.** Three scenarios, each asserting **no incident is proposed**:

1. **Monday morning burst** — 30 tickets about 30 unrelated things in 10 minutes. High rate, low similarity.
2. **Slow correlated trickle** — 8 genuinely related tickets over 4 hours. High similarity, normal rate, window exceeded.
3. **Correlated but below threshold** — 4 related tickets in 5 minutes. High similarity, high rate, cluster too small.

Then the positive control: 38 correlated tickets in 18 minutes → **proposed**, with `arrivalRateMultiple` matching a hand-computed value.

Add scenario 4: a storm arriving *while* an incident is already confirmed → **no duplicate incident** (the >50% overlap condition from Task 7).

**Each negative isolates a different gate condition**, so a failure tells you exactly which threshold is wrong. A single "does not fire on noise" test would not.

**⛓️ Dependencies**
Tasks 7, 9, 11.

**✅ Expected Output**
All five green. Scenario 1 in particular, since it is the realistic false-positive case and the one that would destroy trust in production.

**⏱️ Estimated Time**
2 hours.

---

## TASK 26 — Measure time-to-detect and record benchmarks

**📝 Description**
Run `storm.sh` 10 times against a freshly seeded database and record:

| Metric | Source |
|---|---|
| Time-to-detect, p50 / p95 | `detected_at − first_ticket_at` |
| Cluster precision / recall | the Task 11 tuning set |
| Agent actions saved | 38 tickets → 1 confirm + 1 update + 1 resolve |
| Fan-out latency, p95 | publish → all deliveries `SENT` |
| Sweep duration at N=200 | Micrometer timer |

Add Micrometer metrics: `incident.proposed`, `incident.confirmed`, `incident.rejected` (counters), `incident.time_to_detect` (timer), `incident.cluster_size` (summary), `correlation.sweep.duration` (timer).

**Replace the benchmark placeholders in the [PROJECT-DECISION-2026.md](../PROJECT-DECISION-2026.md) resume bullets with these numbers.** The incident bullet has two, and the confirm/reject ratio over the seeded runs gives you the precision figure.

**Report the confirm-versus-reject ratio honestly, including rejections.** A feature that proposes ten incidents of which nine are confirmed is a real result. One that claims perfect precision on a tuning set it was fitted to is not, and an interviewer who has done this will ask.

**⛓️ Dependencies**
Tasks 21, 25.

**✅ Expected Output**
Five measured numbers in the README. Six metrics on `/actuator/prometheus`. Resume placeholders replaced.

**⏱️ Estimated Time**
1.5 hours.

---

## TASK 27 — Clean up, verify and tag Phase 8

**📝 Description**
Remove stubs. Full clean verify from empty.

README section "Incident correlation" covering: why embeddings are load-bearing here and nowhere else (no rule clusters *"card declined"* with *"UPI not going through"*); the deterministic gate and why the model has no vote; the tuning table from Task 11; per-ticket fan-out and why one event per ticket; and the two demo GIFs — the storm collapsing, and the no-storm control producing nothing.

Extend the Postman collection with the Incidents folder.

Commit and tag `phase-8-complete`.

**⛓️ Dependencies**
Task 26.

**✅ Expected Output**
Clean verify from empty. CI green. Both demo GIFs in the README. Tag pushed.

**⏱️ Estimated Time**
1.5 hours.

---

## 📅 Suggested Daily Schedule

### 8A — Entities & extraction (3 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 4.0 | T1 Entities + references (2.5) · T2 Repositories (1.5) |
| 2 | 3.0 | T3 Entity extraction (3.0) |
| 3 | 1.5 | T4 Extraction tests (1.5) *— light day; start T5* |

### 8B — Clustering & the gate (5 days)

| Day | Hours | Tasks |
|---|---|---|
| 4 | 2.5 | T5 Arrival-rate baseline (2.5) |
| 5 | 3.5 | T6 Clustering pass (3.5) |
| 6 | 3.5 | T7 CorrelationGate (2.0) · T8 Boundary tests (1.5) |
| 7 | 2.5 | T9 Sweep worker (2.5) |
| 8 | 2.0 | T10 Title generation + fallback (2.0) |
| 9 | 3.0 | **T11 Tuning (3.0)** — measurement, not coding |

**→ 8B checkpoint. A storm produces an incident; noise does not.**

### 8C — Lifecycle (4 days)

| Day | Hours | Tasks |
|---|---|---|
| 10 | 4.0 | T12 Board (2.0) · T13 Detail (2.0) |
| 11 | 3.5 | T14 Confirm (2.5) · T15 Reject (1.0) |
| 12 | 2.5 | T16 Link + detach (2.5) |
| 13 | 2.0 | T17 Resolve (2.0) |

### 8D — Fan-out (2 days)

| Day | Hours | Tasks |
|---|---|---|
| 14 | 4.5 | T18 Publish update (2.5) · T19 FanoutWorker (2.0) |
| 15 | 1.5 | T20 Delivery status (1.5) |

### 8E — Demo, tests & close (3 days)

| Day | Hours | Tasks |
|---|---|---|
| 16 | 3.5 | T21 storm.sh + corpus (2.5) · T22 One-live-incident test (1.0) |
| 17 | 3.5 | T23 Merge-during-edit (1.5) · T24 Fan-out isolation (2.0) |
| 18 | 3.5 | T25 Precision tests (2.0) · T26 Benchmarks (1.5) |
| 19 | 1.5 | T27 Close (1.5) |

**Day 20 — Buffer.** Most likely consumed by T6 (clustering behaviour is easy to get subtly wrong and hard to eyeball) or T11 (tuning always takes a second pass once you see the first precision table).

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 27   (8A: 4 · 8B: 7 · 8C: 6 · 8D: 3 · 8E: 7)
Total Estimate : 55–58 hours
Suggested Days : 19 working days + 1 buffer
                 (NOT the 5–6 days in doc 07)

Hardest Task   : Task 6 — the clustering pass.
                 Not algorithmically hard, but the failure mode is
                 silent: single-linkage chaining produces one giant
                 cluster containing three unrelated outages, and it
                 looks like it is working until you check. Leader
                 clustering plus the Task 25 precision tests are what
                 make the behaviour observable rather than assumed.

Runner-up      : Task 11 — threshold tuning.
                 It is the only task here with no code deliverable, so
                 it feels skippable. It is also the difference between
                 "I picked 0.85 because it seemed reasonable" and
                 "here is the precision/recall table I tuned against."

Most Skipped   : Task 21's negative control — demo/no-storm.sh.
                 Writing a script whose entire purpose is to make
                 nothing happen feels pointless. It is the single most
                 persuasive artifact in the phase: it turns "it found a
                 pattern" into "it distinguishes a pattern from noise,"
                 which is the only version of that claim an experienced
                 interviewer will accept.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 8 exit checklist

- [ ] `docker compose down -v && docker compose up -d && mvn clean verify` passes from empty
- [ ] `./demo/storm.sh` produces a proposed incident in under 90 seconds
- [ ] **`./demo/no-storm.sh` produces nothing** — and so do all three negative scenarios in T25
- [ ] The gate's evidence is on the board card, not behind a click
- [ ] **With the LLM provider switched off, incidents are still detected** with templated titles
- [ ] Confirm pauses 38 resolution clocks and leaves 38 first-response clocks running
- [ ] Detach restores a ticket's clock with the elapsed total unchanged
- [ ] One forced delivery failure leaves the other 37 with exactly one notification each
- [ ] A ticket cannot belong to two live incidents — enforced by the index, not by code
- [ ] Threshold tuning table committed; precision ≥ 0.95
- [ ] Time-to-detect measured; resume placeholders replaced
- [ ] Every new endpoint is in the Phase 4 cross-tenant `@MethodSource`

---

## Next Steps

> ✅ **Task list ready for Phase 8 — Incident Correlation!**
>
> **Act on the reframe at the top of this document first.** Do a minimal Phase 10 — Dockerfile, deploy, seeded demo, README — **immediately after 6A**, not at the end. Two days. From week 11 you always have a live URL and a written architecture story, and you build the rest against a deployed instance rather than discovering deployment problems in week 19.
>
> **Three things the ordering depends on:**
> 1. **Task 11 (tuning) before any of 8C.** Building the lifecycle on thresholds you have not measured means rebuilding your demo data when they change.
> 2. **Write `no-storm.sh` at the same time as `storm.sh`**, not afterwards. The negative control is what makes the positive one credible.
> 3. **Task 10's template fallback is not optional.** Being able to demo incident correlation with the LLM switched off is the cleanest proof of the whole project's architectural claim.
>
> **After Phase 8** you have all five resume bullets. Run `task-breakdown` on a **merged Phase 9+10** — most of Phase 9's testing has been written inline across Phases 4–8, so what remains is the security pass, the chaos pass, and productionisation. Expect ~15 days combined rather than the 9–11 in [07](07-DEV-PHASES.md).
