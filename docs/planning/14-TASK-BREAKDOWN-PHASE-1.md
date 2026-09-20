# 14 — Task Breakdown: Phase 1

> Skill: `task-breakdown` · **The last phase to be broken down.** Planning is now complete.

**Phase:** Phase 1 — Requirements Finalization & Environment Setup
**Goal:** Every tool installed, the full Docker stack running and verified, the repository initialised, a coherent fictional product defined, and a committed synthetic corpus that makes every later feature demoable.
**Tech stack:** JDK 21 · Maven 3.9+ · Docker Desktop · Node 20+ · IntelliJ IDEA · DBeaver · Postman · an LLM API

---

## ⚠️ Estimate correction, and a structural change

**[07](07-DEV-PHASES.md) said 2–3 days. The real figure is ~32 hours ≈ 9–10 days** — the largest relative miss of any phase (3.5×).

Not because the tooling is hard. **Because the corpus is a real engineering artifact and doc 07 treated it as a side task**, budgeting "a full day and hand-check the output". By the time you reach Phase 8, that corpus has to support:

| Requirement | Discovered in |
|---|---|
| 8 fixed categories used consistently across enum, prompt schema, team skills and eval labels | [11 T14/T15](11-TASK-BREAKDOWN-PHASE-6.md), [09 T17](09-TASK-BREAKDOWN-PHASE-4.md) |
| A coherent fictional product with service names, error codes and regions | [12 T3](12-TASK-BREAKDOWN-PHASE-8.md) — entity extraction has nothing to extract otherwise |
| 4 weeks of tickets with a realistic **weekday × hour** arrival shape | [12 T5](12-TASK-BREAKDOWN-PHASE-8.md) — the arrival-rate baseline is per (dow, hour) |
| Realistic resolution durations per (category, priority, team) | [10 T30](10-TASK-BREAKDOWN-PHASE-5.md) — p75 breach prediction |
| Three planted storms, **linguistically varied** | [12 T11/T21](12-TASK-BREAKDOWN-PHASE-8.md) — tuning and the demo |
| Identifiable uncorrelated bursts | [12 T25](12-TASK-BREAKDOWN-PHASE-8.md) — the precision tests |
| 60–100 **independently** hand-labelled classification cases | [11 T34](11-TASK-BREAKDOWN-PHASE-6.md) — the CI eval gate |

That is a specification, not a side task. None of it was visible when doc 07 was written; all of it emerged from breaking down the phases that consume it.

### The structural change: split Phase 1 in two

**Do not spend nine days on setup before writing a line of code.** Most of the corpus is only needed by Phase 8, in week 16 — and generating it in week 1 means generating against a vocabulary that may still shift.

| | Sub-phase | Tasks | Effort | When |
|---|---|---|---|---|
| **1A–1E** | Toolchain, infrastructure, domain definition, **200-ticket starter corpus** | 1–11 | ~21 h ≈ 6 days | **Now** — enough for Phases 3–6 |
| **★ 1F** | Full 2,000-ticket corpus, storms, negative controls, eval labels | 12–16 | ~11 h ≈ 3–4 days | **Immediately before Phase 8** |

**Revised project total: ~108 working days ≈ 22–23 weeks.** Every phase is now planned at task level.

---

# PHASE 1A — Toolchain & Repository

## TASK 1 — Install and verify the toolchain

**📝 Description**
Install and verify each, in this order:

```bash
java -version          # must print 21.x — not 17, not 22
mvn -version           # 3.9+, and check it reports JDK 21
docker --version && docker compose version
node --version         # 20+
```
Plus IntelliJ IDEA (Community is fine), DBeaver or pgAdmin, Postman.

**The one that bites:** a machine with several JDKs where `JAVA_HOME` points at 17 while `java -version` prints 21 from the PATH. Maven uses `JAVA_HOME`. Set it explicitly and confirm `mvn -version` agrees.

In IntelliJ, set the project SDK to 21 and the language level to 21 — **records, sealed interfaces and pattern matching are used throughout** ([10 T4](10-TASK-BREAKDOWN-PHASE-5.md), [11 T22](11-TASK-BREAKDOWN-PHASE-6.md)) and a language level of 17 fails them with confusing errors.

Allocate Docker Desktop at least **6 GB** of RAM. Eight containers with Postgres, Ollama and a JVM will thrash at the 2 GB default.

**⛓️ Dependencies** None — this is the starting point.
**✅ Expected Output** All four version commands print the expected majors, `mvn -version` reports JDK 21, and Docker has ≥6 GB allocated.
**⏱️** 2 hours *(add 1 hour if a JDK version conflict needs untangling).*

---

## TASK 2 — Create the repository and hygiene files

**📝 Description**
Create the GitHub repository (**private for now** — make it public at [13 T26](13-TASK-BREAKDOWN-PHASE-9-10.md)).

Commit, in this order, before anything else:
- `.gitignore` — `target/`, `node_modules/`, `.env`, `*.log`, `.idea/`, `*.iml`, `/seed/generated/*.tmp`
- `.gitattributes` — `* text=auto eol=lf`, so a Windows checkout does not break shell scripts
- `README.md` — a one-line placeholder
- `LICENSE` — MIT
- Directory skeleton: `docs/adr/`, `seed/`, `demo/`, `ops/`

**Commit `.gitignore` in the very first commit, before any `.env` exists.** A key committed and later deleted is still in the history, and `gitleaks` at [13 T10](13-TASK-BREAKDOWN-PHASE-9-10.md) scans the full history — you would find it in week 19 and have to rotate the credential.

**⛓️ Dependencies** Task 1.
**✅ Expected Output** Repository exists with the five files and four directories. `git log` shows `.gitignore` in commit 1.
**⏱️** 1 hour.

---

# PHASE 1B — Infrastructure

## TASK 3 — Write `docker-compose.yml`

**📝 Description**
Eight services. The critical details, each of which costs a debugging session if missed:

| Service | Image | Note |
|---|---|---|
| `postgres` | **`pgvector/pgvector:pg16`** | **Not `postgres:16`** — it does not ship pgvector, and you find out in week 11 |
| `redis` | `redis:7-alpine` | `--appendonly no` — this is a cache, not a datastore |
| `minio` | `minio/minio` | Console on 9001, API on 9000 |
| `mailhog` | `mailhog/mailhog` | UI on 8025 |
| `prometheus` | `prom/prometheus` | Mount `ops/prometheus.yml` scraping `host.docker.internal:8080` |
| `grafana` | `grafana/grafana` | Anonymous admin for local |
| `ollama` | `ollama/ollama` | For the `external_model_allowed=false` path |
| `app` | *(commented out)* | Placeholder until [13 E1](13-TASK-BREAKDOWN-PHASE-9-10.md) |

Give every service a **health check** and a named volume. Add `restart: unless-stopped`.

Prometheus scraping the host rather than a container is the fiddly bit while the app runs from IntelliJ: use `host.docker.internal:8080` on Windows and macOS; on Linux add `extra_hosts: ["host.docker.internal:host-gateway"]`.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** `docker compose config` validates. All eight services defined with health checks and volumes.
**⏱️** 2.5 hours.

---

## TASK 4 — Bring up the stack and verify every service

**📝 Description**
`docker compose up -d`, then verify each **individually** — not just that the container is running:

```bash
# Postgres — THE critical check
psql -h localhost -U resolveai -d resolveai \
  -c "CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS pg_trgm;" \
  -c "SELECT extname, extversion FROM pg_extension;"
# pgvector must be 0.5+ for HNSW. Older ships only ivfflat.

redis-cli -h localhost ping                      # PONG
```
MinIO: log into the console, create the `resolveai-attachments` bucket, generate an access key pair.
Mailhog, Prometheus, Grafana: load each UI.

**Then the test that actually matters:**
```bash
docker compose down -v && docker compose up -d
```
and verify everything comes back healthy from empty volumes. **A stack that only works because of a volume you created by hand is one you cannot hand to a reviewer**, and `docker compose down -v` appears in the exit checklist of every subsequent phase.

**⛓️ Dependencies** Task 3.
**✅ Expected Output** All eight healthy. `vector` and `pg_trgm` present, version recorded in the README. Recreating from empty volumes works.
**⏱️** 2 hours.

---

## TASK 5 — Obtain LLM credentials and wire the environment

**📝 Description**
Obtain a primary API key and a **different-provider** fallback key. Different provider, not a second key from the same one — the failover path at [11 T16](11-TASK-BREAKDOWN-PHASE-6.md) exists to survive a provider outage, and two keys on one provider fail together.

Create `.env` (git-ignored) and commit `.env.example` with **names only**, using the variable list from [02 §11](02-PROJECT-PLAN.md).

Smoke-test both with curl. Record the model ids and the per-million-token rates in `docs/llm-costs.md` — [11 T16](11-TASK-BREAKDOWN-PHASE-6.md) needs them for the integer `cost_micros` rate table.

**Set a hard spend limit in each provider's console right now**, if they offer one. The budget enforcement at [11 T18](11-TASK-BREAKDOWN-PHASE-6.md) protects against runaway *application* spend; nothing in the application protects against a runaway generation script in Task 9.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** Both providers answer curl. `.env.example` committed, `.env` ignored — verify with `git status`. Rates recorded. Spend limits set.
**⏱️** 1.5 hours.

---

## TASK 6 — Pull and verify the local Ollama model

**📝 Description**
```bash
docker compose exec ollama ollama pull llama3.2:3b
curl http://localhost:11434/api/generate -d '{"model":"llama3.2:3b","prompt":"Reply with the single word OK","stream":false}'
```

3B, not 7B or larger — this only serves the `external_model_allowed = false` governance path at [11 T11](11-TASK-BREAKDOWN-PHASE-6.md). It needs to produce *valid structured output*, not good output. A 7B model on a laptop without a GPU makes the whole Compose stack unusable.

**Test structured output specifically**, since that is the only thing it must do: ask it to return a small fixed JSON schema and confirm it parses. Small models are much worse at this than large ones — if it cannot, note it now and plan to have the governance path return `UNAVAILABLE` rather than a local result.

**⛓️ Dependencies** Task 4.
**✅ Expected Output** Model pulled, responds under 10 s, and either produces parseable JSON for a simple schema or is documented as unable to.
**⏱️** 1 hour.

---

# PHASE 1C — Domain Definition

## TASK 7 — Define the fictional product and its domain vocabulary

**📝 Description**
**The task doc 07 missed entirely, and everything downstream depends on it.** Two thousand tickets have to be *about* something. Without a coherent product there is no consistent vocabulary, and [12 T3](12-TASK-BREAKDOWN-PHASE-8.md)'s entity extraction has nothing to extract.

Write `docs/fictional-product.md` defining the product whose customers raise these tickets. A worked proposal — use it or replace it, but commit whatever you choose:

> **Ledgerly** — a B2B invoicing and payments SaaS for Indian SMBs. Customers raise invoices, collect payments via UPI, cards and netbanking, sync with Tally and Zoho, and file GST returns.

Chosen because it produces genuinely varied *payment failure* language, which is what the storm demo at [12 T21](12-TASK-BREAKDOWN-PHASE-8.md) needs: *"card declined"*, *"money deducted no order"*, *"UPI not going through"*, *"collect request expired"* share almost no vocabulary and cluster only in embedding space.

Define and commit, as data files the generator and the extractor both read:

```yaml
# seed/domain/services.yml
services: [payment-service, auth-service, ledger-service, sync-service,
           report-service, api-gateway, kyc-service, notification-service]

# seed/domain/error-codes.yml
error_codes:
  PAYMENT:     [ERR_PAY_TIMEOUT, ERR_PAY_DECLINED, ERR_UPI_COLLECT_FAILED,
                ERR_SETTLEMENT_DELAY, ERR_REFUND_PENDING]
  AUTH:        [ERR_AUTH_TOKEN_EXPIRED, ERR_2FA_MISMATCH, ERR_SESSION_REVOKED]
  INTEGRATION: [ERR_SYNC_MAPPING, ERR_TALLY_CONN, ERR_BANK_FEED_STALE]
  # … one set per category

# seed/domain/regions.yml
regions: [ap-south-1, ap-southeast-1]

# seed/domain/payment-methods.yml
payment_methods: [UPI, card, netbanking, wallet, NEFT, IMPS]
```

**One source of truth, read by both the generator and the extractor.** If the corpus says `payments-service` and the extractor looks for `payment-service`, entity extraction silently returns nothing and the clustering boost in [12 T6](12-TASK-BREAKDOWN-PHASE-8.md) does nothing — a failure that produces no error and is very hard to notice.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** `docs/fictional-product.md` plus four YAML files committed. A reader understands what the product does in 60 seconds.
**⏱️** 2.5 hours.

---

## TASK 8 — Fix the category taxonomy and team mapping

**📝 Description**
Commit `seed/domain/taxonomy.yml` as the **single source** for the eight categories. They appear in at least five places later — the `Category` enum ([11 T15](11-TASK-BREAKDOWN-PHASE-6.md)), the prompt's output schema ([11 T14](11-TASK-BREAKDOWN-PHASE-6.md)), team `skills` arrays ([09 T17](09-TASK-BREAKDOWN-PHASE-4.md)), eval labels ([11 T34](11-TASK-BREAKDOWN-PHASE-6.md)) and the corpus — and they must agree exactly.

```yaml
categories:
  PAYMENT:     { description: "Payment failures, refunds, settlement", team: Payments }
  BILLING:     { description: "Subscription, plan changes, Ledgerly's own invoices", team: Payments }
  AUTH:        { description: "Login, password, 2FA, sessions",        team: Platform }
  API:         { description: "Developer API errors, rate limits, webhooks", team: Platform }
  PERFORMANCE: { description: "Slowness, timeouts, crashes",           team: Platform }
  INTEGRATION: { description: "Tally, Zoho, bank feed sync",           team: Integrations }
  DATA:        { description: "Reports, exports, missing records",     team: Integrations }
  ONBOARDING:  { description: "KYC, verification, account setup",      team: "Customer Success" }

teams:
  Payments:           { skills: [PAYMENT, BILLING],              default: false }
  Platform:           { skills: [AUTH, API, PERFORMANCE],        default: false }
  Integrations:       { skills: [INTEGRATION, DATA],             default: false }
  "Customer Success": { skills: [ONBOARDING],                    default: true }
```

**Include at least one deliberately confusable pair** — `PAYMENT` and `BILLING` here. A taxonomy with eight cleanly separated categories makes classification accuracy look great and teaches you nothing. The confusion matrix at [11 T34](11-TASK-BREAKDOWN-PHASE-6.md) is more interesting, and more honest, when two categories genuinely overlap.

**⛓️ Dependencies** Task 7.
**✅ Expected Output** `taxonomy.yml` committed with eight categories, four teams (one default), and every category mapped to exactly one team.
**⏱️** 1.5 hours.

---

# PHASE 1D — Starter Corpus

## TASK 9 — Write the corpus generator

**📝 Description**
A standalone script in `seed/generator/` — Node or Python, **not** part of the Spring application. It runs offline and its output is committed.

**The key design decision: generate the label first, then ask the model to express it.**

```
for each ticket:
  1. Pick (category, priority, team) deterministically from a weighted distribution   ← ground truth
  2. Pick a scenario template for that category
  3. Pick entities from the Task 7 vocabulary — service, error code, region, payment method
  4. Ask the LLM: "write a support ticket from a frustrated SMB owner experiencing
     <scenario> involving <entities>. Vary the phrasing; do not use the words
     <category-name>. 40–120 words."
  5. Emit { subject, body, category, priority, team, entities, createdAt, resolvedAt }
```

**Ground truth by construction.** The labels are not inferred from the generated text — the text is generated *from* the labels. That gives you 2,000 labelled examples for the price of generation, rather than 2,000 hand-labelling decisions.

Two details that matter later:
- **"Do not use the words `<category-name>`"** — otherwise every PAYMENT ticket literally contains "payment", classification becomes keyword matching, and both the eval and the storm demo become meaningless.
- **Resolution durations** drawn from a log-normal per (category, priority): P1 median ~2 business hours, P4 median ~3 business days. [10 T30](10-TASK-BREAKDOWN-PHASE-5.md)'s p75 breach prediction needs a realistic spread, not a constant.

Batch 20 tickets per call with a cheap model. Cache by scenario hash so re-runs are free. Support `--count` and `--seed` for reproducibility.

**⛓️ Dependencies** Tasks 5, 8.
**✅ Expected Output** `node seed/generator/generate.js --count 20 --seed 42` emits 20 valid tickets in under 60 s. Re-running with the same seed produces identical output.
**⏱️** 3.5 hours.

---

## TASK 10 — Generate and hand-verify the 200-ticket starter corpus

**📝 Description**
Generate 200 tickets — roughly 25 per category — spread over two weeks. Enough for Phases 3–6; the full corpus comes at 1F.

**Then read 30 of them, properly.** For each: does it sound like a real person? Is the assigned category the one you would pick? Are the entities present and correctly spelled against the Task 7 vocabulary? Is the priority plausible?

Fix the generator and regenerate until the 30-ticket sample passes. Expect **two or three iterations** — the first pass is usually too formal, too uniform in length, and too willing to name its own category.

Commit `seed/generated/tickets-starter.json` plus the seed value and generator version used.

**Reading thirty tickets is not optional and it is not busywork.** A corpus you have not read is a corpus you cannot defend, and *"the data is synthetic — here is the generator and here is what I hand-verified"* is a sentence you will say in an interview.

**⛓️ Dependencies** Task 9.
**✅ Expected Output** 200 tickets committed. A 30-ticket sample hand-checked, with the review notes in `seed/VERIFICATION.md`. Category distribution within ±20% of target.
**⏱️** 2 hours.

---

# PHASE 1E — Close

## TASK 11 — Write README v-1 and verify the Phase 2 handoff

**📝 Description**
README at this point needs three sections only:
1. **The problem**, one paragraph, with the evidence it is real (from [02 §1](02-PROJECT-PLAN.md))
2. **The architecture diagram** from [03 §3](03-SYSTEM-ARCHITECTURE.md)
3. **Running the infrastructure** — prerequisites, `docker compose up -d`, the service URLs

Add a **"The data is synthetic"** section now, at the very start, rather than retrofitting it at [13 T24](13-TASK-BREAKDOWN-PHASE-9-10.md). Volunteering it reads as rigour; being caught omitting it reads as the opposite.

Then verify the handoff to Phase 2 — everything [08](08-TASK-BREAKDOWN-PHASE-3.md) assumes:
- [ ] Postgres running with `vector` and `pg_trgm`, version recorded
- [ ] Redis, MinIO (bucket created), Mailhog, Prometheus, Grafana all reachable
- [ ] `.env` complete and ignored; `.env.example` committed
- [ ] Both LLM providers smoke-tested; Ollama responding
- [ ] Domain vocabulary and taxonomy committed
- [ ] Starter corpus committed and hand-verified
- [ ] `docker compose down -v && docker compose up -d` works from empty

**⛓️ Dependencies** Tasks 1–10.
**✅ Expected Output** README with three sections plus the synthetic-data note. All seven handoff items tick.
**⏱️** 1.5 hours.

---

# ★ PHASE 1F — Full Corpus *(deferred — run immediately before Phase 8)*

Five tasks, ~11 hours ≈ 3–4 days. **Do not do this in week 1.** It is only consumed by Phase 8, and by then your domain vocabulary will have settled.

## TASK 12 — Generate the 4-week arrival pattern

**📝 Description**
Scale to ~2,000 tickets over 28 days with a realistic shape, because [12 T5](12-TASK-BREAKDOWN-PHASE-8.md)'s baseline is computed per **(weekday, hour)**:

```
Weekday weighting:  Mon 1.4 · Tue 1.2 · Wed 1.1 · Thu 1.1 · Fri 1.0 · Sat 0.3 · Sun 0.2
Hourly shape (IST): trough 00–07 (~0.1) · ramp 08–09 · peak 10–12 (~1.8)
                    dip 13–14 · second peak 15–17 (~1.5) · decay 18–23
```

Add a little week-over-week drift so four weeks are not identical, and inject **one legitimate Monday-morning spike** — a 2× day with no correlation — as a natural negative control.

**A flat arrival pattern makes the baseline meaningless**, and the gate would then fire on any busy hour. The shape is what makes 12.6× baseline a meaningful number rather than an artifact.

**⛓️ Dependencies** Tasks 9, 10.
**✅ Expected Output** ~2,000 tickets over 28 days. A histogram per (dow, hour) shows the intended shape. Saturday volume is ~20% of Monday.
**⏱️** 2.5 hours.

---

## TASK 13 — Generate the three planted storms

**📝 Description**
Three incident storms injected into the timeline:

| Storm | Shape | Purpose |
|---|---|---|
| **A — payment gateway** | 38 tickets / 18 min, Friday 14:02 | The demo. Must be the most varied. |
| **B — login outage** | 22 tickets / 25 min, Tuesday 09:40 | A second positive, different category |
| **C — slow sync** | 12 tickets / 28 min, Wednesday 16:15 | **Borderline** — smaller and slower, near the gate threshold |

For storm A, generate **38 genuinely different phrasings** of one payment outage. Prompt explicitly for variety, then **read all 38** and rewrite any that are too similar. Target vocabulary overlap below 30% between any pair.

**The variety is the entire point.** If all 38 say "payment failed", a keyword matcher clusters them and your embedding approach demonstrates nothing. They must be tickets **no rule could group**: *"card declined"*, *"money deducted no order"*, *"UPI not going through"*, *"collect request expired"*, *"paid twice charged once"*.

Storm C exists to keep you honest during tuning at [12 T11](12-TASK-BREAKDOWN-PHASE-8.md) — a threshold set purely against storms A and B will be too loose.

**⛓️ Dependencies** Task 12.
**✅ Expected Output** Three storms in `seed/generated/storms.json` with ground-truth membership. Storm A's pairwise vocabulary overlap measured and below 30%.
**⏱️** 2.5 hours.

---

## TASK 14 — Generate the negative-control windows

**📝 Description**
Three windows that **must not** trigger the gate, each isolating a different condition from [12 T7](12-TASK-BREAKDOWN-PHASE-8.md):

1. **High rate, low similarity** — 30 tickets about 30 unrelated things in 10 minutes *(fails the similarity threshold)*
2. **High similarity, low rate** — 8 genuinely related tickets over 4 hours *(fails the window)*
3. **High similarity, high rate, too few** — 4 related tickets in 5 minutes *(fails cluster size)*

Label each window explicitly in `seed/generated/negative-controls.json` with the condition it is meant to trip.

**One negative per gate condition, not one generic "noise" case.** When [12 T25](12-TASK-BREAKDOWN-PHASE-8.md) fails, you need to know *which* threshold is wrong — a single blended negative would tell you only that something is.

**⛓️ Dependencies** Task 13.
**✅ Expected Output** Three labelled windows committed, each annotated with its intended failing condition.
**⏱️** 1.5 hours.

---

## TASK 15 — Build the hand-labelled evaluation set

**📝 Description**
100 cases for the `CLASSIFICATION` suite at [11 T34](11-TASK-BREAKDOWN-PHASE-6.md).

**Crucially: label these independently, by reading the ticket — do not reuse the generator's labels.**

The generator's labels are ground truth *for the generator*. Measuring against them tells you whether the classifier agrees with the process that wrote the text, which is a weaker and more flattering claim than whether it agrees with a human reading the ticket cold. Where your independent label disagrees with the generator's, **keep your label and note the disagreement** — those cases are the most informative in the set.

Composition: 60 clear cases across all eight categories, 25 genuinely ambiguous ones (especially the PAYMENT/BILLING pair from Task 8), and 15 edge cases — very short tickets, tickets spanning two categories, tickets with no clear category at all.

Commit with a `labelledBy` and `labelledAt` field, and record how many disagreed with the generator. **That disagreement rate is itself a result worth reporting** — it is the honest upper bound on what any classifier could score.

**⛓️ Dependencies** Task 12.
**✅ Expected Output** `seed/eval/classification-cases.json` with 100 independently labelled cases. Disagreement rate against generator labels recorded.
**⏱️** 2.5 hours.

---

## TASK 16 — Final hand-verification and commit

**📝 Description**
Read a fresh 50-ticket sample from the full corpus — not the 30 from Task 10. Check realism, label correctness, entity spelling against Task 7's vocabulary, and the priority distribution.

Run a statistical sanity pass: category distribution, priority distribution, resolution-time distribution per (category, priority), arrival histogram per (dow, hour), and storm membership counts.

Write `seed/CORPUS.md`: how it was generated, the model and prompts used, the seed values, what was hand-verified and by whom, the known limitations, and the Task 15 disagreement rate.

**`seed/CORPUS.md` is a README section, not an internal note.** *"Here is exactly how the data was made and here is what I checked"* is the answer to the first question a sharp reviewer asks, and having it written down is what turns synthetic data from a weakness into a demonstration of rigour.

**⛓️ Dependencies** Tasks 12–15.
**✅ Expected Output** Full corpus committed. `CORPUS.md` written. All distributions within tolerance. 50-ticket sample verified.
**⏱️** 2 hours.

---

## 📅 Suggested Daily Schedule

### 1A–1E — Now (6 days)

| Day | Hours | Tasks |
|---|---|---|
| 1 | 3.0 | T1 Toolchain (2.0) · T2 Repository (1.0) |
| 2 | 2.5 | T3 docker-compose (2.5) |
| 3 | 3.0 | T4 Stack verification (2.0) · T6 Ollama (1.0) |
| 4 | 4.0 | T5 LLM credentials (1.5) · T7 Fictional product (2.5) |
| 5 | 3.5 | T8 Taxonomy (1.5) · start T9 Generator (2.0) |
| 6 | 3.5 | T9 finish (1.5) · T10 Starter corpus (2.0) |
| 7 | 1.5 | T11 README v-1 + handoff (1.5) |

*Day 4 pairs the credentials task with the product definition deliberately — the generator in Task 9 needs both, and splitting them across days means re-loading the domain into your head twice.*

### ★ 1F — Immediately before Phase 8 (3–4 days)

| Day | Hours | Tasks |
|---|---|---|
| F1 | 2.5 | T12 Arrival pattern (2.5) |
| F2 | 2.5 | T13 Planted storms (2.5) |
| F3 | 4.0 | T14 Negative controls (1.5) · T15 Eval set (2.5) |
| F4 | 2.0 | T16 Final verification (2.0) |

**Buffer day.** Most likely used by T9 (the first generator output is always too uniform and needs two or three prompt iterations) or T13 (getting 38 genuinely different phrasings takes rewriting by hand).

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 16   (1A: 2 · 1B: 4 · 1C: 2 · 1D: 2 · 1E: 1 · ★1F: 5)
Total Estimate : 21 h now  +  11 h deferred  =  ~32 hours
Suggested Days : 6 now + 1 buffer, then 3–4 before Phase 8
                 (doc 07 said 2–3 days — 3.5×, the largest relative
                  miss of any phase, because the corpus is a real
                  engineering artifact and was treated as a side task)

Hardest Task   : Task 9 — the corpus generator.
                 Not technically hard; hard to get RIGHT. The
                 label-first design is what makes 2,000 labelled
                 examples cheap, and the "do not use the category
                 name" instruction is what stops classification
                 collapsing into keyword matching. Miss either and
                 the corpus looks fine while quietly invalidating
                 your eval numbers.

Runner-up      : Task 13 — the planted storms.
                 38 genuinely different ways to say one thing is
                 harder than it sounds. An LLM asked for variety
                 produces five patterns and paraphrases them.

Most Skipped   : Task 7 — defining the fictional product.
                 It produces no code and feels like world-building
                 for its own sake. It is the single source of the
                 service names, error codes and regions that
                 [12 T3](12-TASK-BREAKDOWN-PHASE-8.md) extracts and
                 [12 T6](12-TASK-BREAKDOWN-PHASE-8.md) boosts
                 clustering with. Skip it and the corpus has no
                 consistent vocabulary, entity extraction silently
                 returns nothing, and the clustering boost does
                 nothing — with no error anywhere to tell you.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 1 exit checklist

- [ ] `java -version` prints 21 **and** `mvn -version` reports JDK 21
- [ ] Docker Desktop has ≥6 GB allocated
- [ ] `docker compose down -v && docker compose up -d` brings all eight services healthy **from empty volumes**
- [ ] **`CREATE EXTENSION vector` succeeds; version is 0.5+ (HNSW available)**
- [ ] MinIO bucket created; Redis PONGs; Mailhog, Prometheus, Grafana reachable
- [ ] Both LLM providers smoke-tested, from **different** vendors; spend limits set
- [ ] Ollama responds and its structured-output ability is documented either way
- [ ] `.gitignore` is in commit 1; `.env` ignored; `.env.example` committed
- [ ] `docs/fictional-product.md` + four vocabulary YAMLs committed
- [ ] `taxonomy.yml` has 8 categories, 4 teams, one default, one confusable pair
- [ ] Starter corpus of 200 tickets committed; **30 hand-verified**, notes in `VERIFICATION.md`
- [ ] README has the problem, the diagram, how to run, and the synthetic-data note

---

## Closing note — planning complete

> ✅ **Every phase of ResolveAI is now planned at task level.** Fifteen documents, **199 tasks**, ~108 working days.
>
> **Two things specific to this phase:**
>
> 1. **Do 1A–1E now and defer 1F to just before Phase 8.** Nine days of setup before writing code is demoralising and premature — the storms and tuning data are consumed in week 16, and by then your vocabulary will have settled.
>
> 2. **Task 7 is the one to protect.** It produces no code and it is the easiest thing here to skip. Every entity your correlation engine extracts in [12](12-TASK-BREAKDOWN-PHASE-8.md) comes from the vocabulary defined there, and a mismatch between generator and extractor fails silently.
>
> **The honest summary of this whole planning exercise:** the estimate went from 8 weeks to 22 — not because the project grew, but because six rounds of task-level breakdown replaced guesses with counted work. **That number is now one you can hold yourself to**, and the milestone map in [12](12-TASK-BREAKDOWN-PHASE-8.md) means you are competitive from week 9 rather than week 22.
>
> **Start with Task 1.**
