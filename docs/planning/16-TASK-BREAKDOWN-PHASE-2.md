# 16 — Task Breakdown: Phase 2

> Skill: `task-breakdown` · **The genuinely last one.** Planning is complete after this.

**Phase:** Phase 2 — System & Database Design
**Goal:** Every design decision validated against the task-level plan, the complete schema written as Flyway migrations and proven from an empty database, and the API contract published — so no design question blocks you once coding starts.
**Tech stack:** PostgreSQL 16 + pgvector · Flyway · OpenAPI 3.1 · Postman

---

## Why this was missed, and what is actually outstanding

Phase 2 was marked *"done"* in [07](07-DEV-PHASES.md) because its **document** outputs exist — [03](03-SYSTEM-ARCHITECTURE.md), [04](04-DATABASE-SCHEMA.md), [05](05-API-CONTRACT.md) and [06](06-UI-UX-DESIGN.md) are this phase's deliverables.

But doc 07's Phase 2 task list had nine items, and only the first four were "run the skills". **Items 5–9 were never done:**

| Doc 07 Phase 2 item | Status |
|---|---|
| 1–4. Run db-schema-designer, api-designer, ui-ux-designer, system-architect | ✅ Docs 03–06 |
| 5. Convert the SQL into Flyway migrations `V1`–`V7` | ❌ |
| 6. Apply and verify every table, index and trigger | ❌ |
| 7. Hand-test the three structural guarantees | ⚠️ Had been pushed into 08 Task 6 — **taken back here** as [Task 12](#task-12--hand-test-the-structural-guarantees), automated, and widened from three assertions to ten |
| 8. Build the Postman collection | ❌ |
| 9. Review the design end to end | ❌ |

And item 9 matters more now than it did then: **seven subsequent breakdowns uncovered four schema gaps and several contract ambiguities** that a design review would have caught. Those are folded in here.

**Phase 2 remaining work: ~29–32 hours ≈ 8–9 days.** Doc 07 allowed 3–4 days for all nine items; roughly two of those covered the skills, leaving 1–2 days for what is really 8–9.

### Overlap with Phase 3, resolved

[08](08-TASK-BREAKDOWN-PHASE-3.md) Tasks 5 and 6 currently say "copy the migrations from Phase 2" and "hand-verify the structural guarantees". **Phase 2 now owns both.** Phase 3's Task 5 shrinks to *"point Spring at the existing migrations and confirm `ddl-auto: validate` passes"* (~30 minutes instead of 1.5 hours) and Task 6 is deleted.

**Net effect on the total: +7 days, not +9.**

---

## Scope: full, nothing skipped

Confirmed. Building everything, including Phase 7C–7E.

**Total: ~135 working days ≈ 27–28 weeks** at 3–4 focused hours a day.

The `⚠️ lean path` markers scattered through [10](10-TASK-BREAKDOWN-PHASE-5.md), [11](11-TASK-BREAKDOWN-PHASE-6.md) and [15](15-TASK-BREAKDOWN-PHASE-7.md) are no longer recommendations — **treat them as a pre-agreed contingency order** if you fall behind, nothing more. They are worth keeping precisely so that if week 18 arrives and you are three weeks down, the decision about what to drop is already made and does not have to be made under pressure.

And the milestone map from [12](12-TASK-BREAKDOWN-PHASE-8.md) is unchanged and matters more at full scope, not less: **two resume bullets by week 9, three by week 11 with a live URL, four by week 16, five by week 22.** You apply with what is finished.

---

## Sub-phase structure

| | Sub-phase | Tasks | Effort |
|---|---|---|---|
| **2A** | Design review & gap closure | 1–3 | ~7 h ≈ 2 days |
| **2B** | Migrations & verification | 4–13 | ~16 h ≈ 5 days |
| **2C** | Contracts | 14–15 | ~6 h ≈ 2 days |
| **2D** | Close | 16 | ~1 h |

---

# PHASE 2A — Design Review & Gap Closure

## TASK 1 — Run the end-to-end design trace

**📝 Description**
Build a single traceability matrix in `docs/design-trace.md`, one row per MVP feature from [02 §3](02-PROJECT-PLAN.md):

| Feature | Endpoints | Tables | Screens | Worker | Tests |
|---|---|---|---|---|---|
| Ticket lifecycle | `POST /tickets`, `GET /tickets`, … | `ticket`, `ticket_message`, `ticket_event` | Queue, Detail | — | 5A T5, 5C T32 |

Then check it in **both directions**, because each finds a different class of bug:

- **Forward** — every feature has endpoints, tables and a screen. A feature with no endpoint is unbuildable.
- **Backward** — every table has something that writes to it and something that reads it; every endpoint has a screen or an explicit note that it is API-only; every screen's data needs are met by an endpoint that exists.

**The backward direction is the one that finds real problems.** A table nothing writes to is a design error; an endpoint no screen calls is either dead or a missing screen. Forward tracing only confirms you wrote down what you planned.

Cross-check against the task breakdowns in [08](08-TASK-BREAKDOWN-PHASE-3.md)–[15](15-TASK-BREAKDOWN-PHASE-7.md) — those are the ground truth for what actually gets built.

**⛓️ Dependencies** None — docs 02–06 exist.
**✅ Expected Output** `docs/design-trace.md` committed. Every table has a writer and a reader. Every gap either closed in Task 2 or explicitly accepted with a reason.
**⏱️** 3 hours.

---

## TASK 2 — Resolve the schema gaps found during task breakdown

**📝 Description**
Seven breakdowns surfaced four tables [04](04-DATABASE-SCHEMA.md) does not contain. **Decide where each belongs now**, rather than discovering the need mid-phase:

| Gap | Found in | Decision |
|---|---|---|
| `tenant_sequence` — generates `TKT-10428` / `INC-204` | [10 T2](10-TASK-BREAKDOWN-PHASE-5.md) | **Fold into `V1`.** Core, no dependency on later design, and `ticket.reference` is `NOT NULL` — the schema is incomplete without it. |
| `ticket_entity` — deterministic entities feeding the correlation gate | [12 T3](12-TASK-BREAKDOWN-PHASE-8.md) | **Fold into `V2`.** A plain child table of `ticket`; nothing about it depends on Phase 8's tuning. |
| `resolved_ticket_stats` — materialised view for p75 breach prediction | [10 T30](10-TASK-BREAKDOWN-PHASE-5.md) | **Defer to `V9`, written in Phase 5.** A materialised view over data that does not exist yet cannot be meaningfully tested. |
| `ticket_arrival_baseline` — materialised view for the correlation gate | [12 T5](12-TASK-BREAKDOWN-PHASE-8.md) | **Defer to `V12`, written in Phase 8.** Same reasoning. |

**The rule: fold in structural tables, defer derived views and seed data.** A table is structural if its shape is fully determined by the design; a materialised view's shape depends on what the query needs once you can see real data, and a prompt seed depends on prompt text you have not written. Writing those early means rewriting them.

Also resolve three contract ambiguities the breakdowns exposed:
1. **`POST /tickets` returns `201` in Phase 5 and `202` from Phase 6** ([11 T9](11-TASK-BREAKDOWN-PHASE-6.md)). Record it in [05](05-API-CONTRACT.md) as a documented, intentional change rather than leaving the contract looking wrong for six weeks.
2. **`PATCH /tickets/{id}` with no `If-Match`** — [10 T12](10-TASK-BREAKDOWN-PHASE-5.md) left the choice open between `428` and `400`. **Pick `428 Precondition Required`** and write it into the contract; it is the semantically exact code and it distinguishes "you forgot the header" from "your body is malformed".
3. **`ticket.search_tsv` and `ticket.embedding` are unmapped in JPA** ([10 T1](10-TASK-BREAKDOWN-PHASE-5.md)). Note it in [04 §Step 8](04-DATABASE-SCHEMA.md) so it reads as a decision rather than an oversight.

**⛓️ Dependencies** Task 1.
**✅ Expected Output** [04](04-DATABASE-SCHEMA.md) updated with the two folded-in tables. [05](05-API-CONTRACT.md) updated with the three resolutions. Deferred items listed in Task 3's roadmap.
**⏱️** 2.5 hours.

---

## TASK 3 — Reserve and document the migration roadmap

**📝 Description**
Write `docs/migrations.md` reserving every version number now, so two phases cannot collide on `V9` and so a future reader can see the plan:

| Version | Contents | Written in |
|---|---|---|
| `V1` | Extensions, tenancy, **`tenant_sequence`** | **Phase 2** |
| `V2` | Ticketing, `draft` stub, **`ticket_entity`** | **Phase 2** |
| `V3` | SLA | **Phase 2** |
| `V4` | AI, knowledge, drafting | **Phase 2** |
| `V5` | Incidents | **Phase 2** |
| `V6` | Platform, evaluation | **Phase 2** |
| `V7` | Triggers and functions | **Phase 2** |
| `V8` | *(reserved)* | — |
| `V9` | `resolved_ticket_stats` materialised view | Phase 5 ([10 T30](10-TASK-BREAKDOWN-PHASE-5.md)) |
| `V10` | Seed `triage@1` prompt | Phase 6 ([11 T14](11-TASK-BREAKDOWN-PHASE-6.md)) |
| `V11` | *(reserved)* | — |
| `V12` | `ticket_arrival_baseline` materialised view | Phase 8 ([12 T5](12-TASK-BREAKDOWN-PHASE-8.md)) |
| `V13` | Seed `incident_title@1` prompt | Phase 8 ([12 T10](12-TASK-BREAKDOWN-PHASE-8.md)) |
| `V14` | Seed `draft@1` and `entailment@1` prompts | Phase 7 ([15 T12](15-TASK-BREAKDOWN-PHASE-7.md)) |

Note the ordering wrinkle explicitly: **Phase 7 runs after Phase 6 but before Phase 8 in the build order, yet `V14` is numbered above `V13`.** Flyway applies in version order, not authoring order, so this is harmless — but leaving `V8` and `V11` as documented gaps is cleaner than renumbering later, and renumbering an applied migration is a checksum failure you do not want to debug.

Also record the two rules that prevent the most common Flyway disaster:
- **Never edit an applied migration.** Flyway checksums them; editing one fails every subsequent startup with an error that does not explain itself.
- **Every migration must be forward-only.** No `DROP` of a column that data lives in without a documented backfill.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** `docs/migrations.md` committed with every version reserved and both rules recorded.
**⏱️** 1.5 hours.

---

# PHASE 2B — Migrations & Verification

> The SQL for `V1`–`V7` is written out in full in [04 §Step 6](04-DATABASE-SCHEMA.md). These tasks are transcription, plus the two folded-in tables, plus debugging — expect two or three typos per file on first application.

## TASK 4 — Write `V1__extensions_and_tenancy.sql`

**📝 Description**
From [04 §Step 6](04-DATABASE-SCHEMA.md): extensions, `tenant`, `team`, `app_user`, `agent_profile`, `refresh_token` — **plus `tenant_sequence`** from Task 2.

Put `CREATE EXTENSION IF NOT EXISTS vector;` as the first statement in the first migration. If it fails, everything after is pointless and you want to know on line 1.

Watch the ordering: `app_user` references `team`, so `team` comes first. Flyway runs each file as one transaction, so a mid-file failure rolls the whole file back — which is what you want.

**⛓️ Dependencies** Task 3.
**✅ Expected Output** Applies cleanly to an empty database. Six tables plus both extensions present.
**⏱️** 1 hour.

---

## TASK 5 — Write `V2__ticketing.sql`

**📝 Description**
`ticket`, the `draft` stub, `ticket_message`, `ticket_event`, `attachment` — **plus `ticket_entity`** from Task 2.

**Order matters here and it is counter-intuitive:** `draft` must be created before `ticket_message`, because `ticket_message.from_draft_id` references it. [04](04-DATABASE-SCHEMA.md) already does this; do not "tidy" it by moving `draft` to `V4` with the rest of the AI tables.

Include all seven ticket indexes, including the HNSW index on `embedding` and the GIN index on `search_tsv`.

**⛓️ Dependencies** Task 4.
**✅ Expected Output** Applies cleanly. Six tables, seven ticket indexes present. `\d ticket` shows the vector and tsvector columns.
**⏱️** 1.5 hours.

---

## TASK 6 — Write `V3__sla.sql`

**📝 Description**
`business_calendar`, `business_holiday`, `sla_policy`, `sla_record`, `sla_clock_segment`, `sla_escalation`.

The three indexes here carry the system's most important guarantees — get them exactly right:
- `idx_sla_poller` — **partial**, `WHERE state = 'RUNNING'`
- `uq_segment_open` — **partial unique**, `WHERE ended_at IS NULL`
- `uq_escalation_rung` — plain unique on `(sla_record_id, rung)`

A non-partial `idx_sla_poller` still works but grows with every terminal record; a non-partial `uq_segment_open` would forbid a record from ever having more than one segment at all, which breaks pause/resume entirely. **Both mistakes are silent until Phase 5.**

**⛓️ Dependencies** Task 5.
**✅ Expected Output** Applies cleanly. `\di` confirms all three are `PARTIAL` where specified.
**⏱️** 1.5 hours.

---

## TASK 7 — Write `V4__ai_and_knowledge.sql`

**📝 Description**
`prompt_version`, `ai_analysis`, `priority_decision`, `priority_override`, `knowledge_document`, `knowledge_chunk`, `draft_claim`, `draft_claim_citation`, `agent_draft_action`.

The largest file. Two things to verify after applying:
- `knowledge_chunk.embedding` is `vector(768)` and the HNSW index uses `vector_cosine_ops` — **not** `vector_l2_ops`. The wrong operator class produces plausible-looking but wrong rankings, with no error.
- `ai_analysis`'s unique constraint is on `(ticket_id, prompt_version_id, attempt)` — omitting `attempt` makes a legitimate retry after a parse failure collide with the original.

**⛓️ Dependencies** Task 6.
**✅ Expected Output** Applies cleanly. Nine tables. `\d knowledge_chunk` confirms `vector(768)` and the cosine operator class.
**⏱️** 2 hours.

---

## TASK 8 — Write `V5__incidents.sql`

**📝 Description**
`incident`, `incident_ticket`, `incident_update`, `incident_update_delivery`.

Two partial unique indexes carry the guarantees:
- `uq_incident_ticket_live` on `(ticket_id) WHERE detached_at IS NULL` — one live incident per ticket
- `uq_delivery` on `(incident_update_id, ticket_id)` — fan-out idempotency

**⛓️ Dependencies** Task 7.
**✅ Expected Output** Applies cleanly. Both partial indexes present and confirmed partial.
**⏱️** 1 hour.

---

## TASK 9 — Write `V6__platform_and_eval.sql`

**📝 Description**
`outbox_event`, `idempotency_record`, `tenant_ai_policy`, `pii_redaction_map`, `notification`, `eval_case`, `eval_run`, `eval_result` — plus the deferred `ALTER TABLE sla_escalation ADD CONSTRAINT fk_escalation_notification`, which cannot be inline because `notification` is created after `sla_escalation`.

`idx_outbox_claim` is the highest-frequency query in the system — verify it is partial on `status = 'PENDING'`. Without the partial clause the index grows unboundedly with `DONE` rows and the claim query degrades over weeks in a way that is hard to attribute.

**⛓️ Dependencies** Task 8.
**✅ Expected Output** Applies cleanly. Eight tables. The deferred FK exists. All four outbox indexes partial as specified.
**⏱️** 1.5 hours.

---

## TASK 10 — Write `V7__triggers.sql`

**📝 Description**
Four trigger functions and their triggers, from [04 §Step 6](04-DATABASE-SCHEMA.md):
1. `set_updated_at` — on `ticket`, `sla_record`, `agent_profile`, `incident`. **Needed because native `UPDATE`s bypass Hibernate's `@UpdateTimestamp` entirely**, and several hot paths use native SQL.
2. `forbid_mutation` — append-only enforcement on `ticket_event`, `sla_escalation`, and the immutable columns of `prompt_version`
3. `forbid_segment_reopen` — narrower guard on `sla_clock_segment`, since closing a segment is a legitimate one-time update
4. `ticket_tsv_update` and `chunk_tsv_update` — full-text vectors maintained by the database so they cannot drift from the columns they index

**Test each trigger immediately after creating it**, in the same psql session. A trigger with a typo in its `WHEN` clause creates successfully and silently never fires — and you would not find out until Phase 5.

**⛓️ Dependencies** Task 9.
**✅ Expected Output** All four functions and their triggers created. `UPDATE ticket_event SET to_value='x'` raises the append-only exception. Inserting a ticket populates `search_tsv` automatically.
**⏱️** 1.5 hours.

---

## TASK 11 — Apply every migration and verify the schema

**📝 Description**
From a genuinely empty database — `DROP DATABASE` and recreate, not just truncate:

```bash
mvn flyway:migrate   # or a standalone Flyway CLI run; no Spring app exists yet
```

Then verify in psql:
```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
-- 7 rows, all success
\dt                                    -- 39 tables (37 from doc 04 + 2 folded in)
SELECT count(*) FROM pg_indexes WHERE schemaname='public';
SELECT tgname FROM pg_trigger WHERE NOT tgisinternal;
SELECT extname, extversion FROM pg_extension;
```

Cross-check the table list against [04 §Step 2](04-DATABASE-SCHEMA.md)'s derived list. **A count that is off by one means a `CREATE TABLE` silently landed in the wrong file**, which Flyway will not tell you.

**⛓️ Dependencies** Task 10.
**✅ Expected Output** Seven successful migrations, 39 tables, every index and trigger present, both extensions loaded. The table list matches the design exactly.
**⏱️** 2 hours.

---

## TASK 12 — Hand-test the structural guarantees

**📝 Description**
*(Absorbed from [08 Task 6](08-TASK-BREAKDOWN-PHASE-3.md), which is now deleted.)*

**The highest-value hour in the phase.** These constraints carry the system's most important correctness properties, and finding a mistake now instead of in Phase 5 or 8 saves a genuinely confused day.

Insert minimal parent rows, then verify each second insert **fails with the named constraint**:

```sql
-- 1. Exactly one open SLA segment per record
INSERT INTO sla_clock_segment (sla_record_id, state, started_at) VALUES (1,'RUNNING',NOW());
INSERT INTO sla_clock_segment (sla_record_id, state, started_at) VALUES (1,'RUNNING',NOW());
--   → must fail: uq_segment_open

-- 2. One escalation per rung
INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire) VALUES (1,50,120);
INSERT INTO sla_escalation (sla_record_id, rung, elapsed_minutes_at_fire) VALUES (1,50,121);
--   → must fail: uq_escalation_rung

-- 3. One live incident per ticket
INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (1,1);
INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (2,1);
--   → must fail: uq_incident_ticket_live

-- 4. One live SLA policy per (tenant, priority, plan)
--   → must fail: uq_sla_policy_live

-- 5. Fan-out idempotency
--   → must fail: uq_delivery

-- 6. Append-only
UPDATE ticket_event SET to_value = 'x' WHERE id = 1;   -- must raise
```

Then verify the **positive** cases: closing a segment and opening another succeeds; a *detached* incident link permits a new live one; a superseded SLA policy permits a new live one. A constraint that blocks the legitimate path is as broken as one that permits the illegitimate one.

Roll everything back.

**⛓️ Dependencies** Task 11.
**✅ Expected Output** All six negatives fail with the **named** constraint in the error. All three positives succeed. Database left clean.
**⏱️** 1.5 hours.

---

## TASK 13 — Write the migration repeatability test

**📝 Description**
A shell script `ops/verify-migrations.sh` that:
1. Drops and recreates the database
2. Runs Flyway from empty
3. Asserts the expected table, index and trigger counts
4. Runs the Task 12 constraint checks non-interactively
5. Exits non-zero on any failure

Then verify Flyway's protection works: edit an applied migration by one character, re-run, and **confirm it fails with a checksum error.** Revert. Knowing what that failure looks like — and that it is Flyway protecting you rather than a bug — saves an hour of panic the first time it happens for real.

This script becomes part of CI in [13 Task 16](13-TASK-BREAKDOWN-PHASE-9-10.md) and is what the `docker compose down -v` step in every phase's exit checklist actually exercises.

**⛓️ Dependencies** Task 12.
**✅ Expected Output** The script runs green from empty and is re-runnable. An edited migration produces a checksum failure, then reverts clean.
**⏱️** 1.5 hours.

---

# PHASE 2C — Contracts

## TASK 14 — Write the OpenAPI 3.1 specification

**📝 Description**
Hand-write `docs/openapi.yaml` from [05](05-API-CONTRACT.md). **By hand, not generated** — there is no code to generate from, and the spec is the contract the code will be written against.

Scope it sensibly:
- **All 52 paths** with method, summary, auth requirement and path/query parameters
- **Shared components in full:** the RFC 7807 `Problem` and `ValidationProblem` schemas, the cursor and offset pagination wrappers, `SecurityScheme` for bearer JWT, and the common header parameters (`Idempotency-Key`, `If-Match`)
- **Full request and response schemas with realistic examples** for the twelve endpoints the frontend needs first: login, refresh, me, create ticket, list tickets, get ticket, add message, assign, status, get analysis, get draft, list incidents

The remaining 40 get path, method, summary and a `TODO: schema` marker, filled in as each is built.

**Write the examples with real data, not `"string"`.** [05](05-API-CONTRACT.md) already has realistic bodies — copy them. A spec full of placeholder values is useless to a frontend developer and useless to you in three months.

**⛓️ Dependencies** Task 2.
**✅ Expected Output** `docs/openapi.yaml` validates against the OpenAPI 3.1 schema. All 52 paths present. Twelve fully specified with realistic examples. It renders in Swagger UI.
**⏱️** 3.5 hours.

---

## TASK 15 — Build the Postman collection

**📝 Description**
Import `openapi.yaml` into Postman, then do the work the import does not:

- **Folders per feature area** — Auth, Tickets, SLA, Triage, Knowledge, Incidents, Admin
- **Environment variables** — `baseUrl`, `tenantSlug`, `accessToken`, `refreshToken`, `ticketId`, `incidentId`, `draftId`
- **A collection-level post-response script on login** that stores `accessToken` into the environment, so every other request inherits it via a collection-level `Authorization` header. **Do this once here or paste tokens by hand several hundred times over the next six months.**
- **A pre-request script** generating a fresh UUID into `{{idempotencyKey}}` for the endpoints that require it
- **Chained happy-path requests** that capture `ticketId` from a create response into the environment, so the Tickets folder runs top to bottom without manual editing

Add a `Smoke Test` folder with the eight requests that prove the system works end to end — it becomes the deploy check in [13 Task 17](13-TASK-BREAKDOWN-PHASE-9-10.md).

Export to `ops/postman/` and commit both the collection and the environment template (**no secrets**).

**⛓️ Dependencies** Task 14.
**✅ Expected Output** Collection committed. Login stores the token automatically. The Tickets folder runs top to bottom with no manual edits.
**⏱️** 2.5 hours.

---

# PHASE 2D — Close

## TASK 16 — Phase 2 handoff verification

**📝 Description**
Confirm everything [08](08-TASK-BREAKDOWN-PHASE-3.md) now assumes:

- [ ] `ops/verify-migrations.sh` green from an empty database
- [ ] 39 tables, all indexes, all triggers present
- [ ] All six structural guarantees hand-tested; all three positive paths confirmed
- [ ] `docs/design-trace.md` shows no unclosed gaps
- [ ] `docs/migrations.md` reserves every version
- [ ] [04](04-DATABASE-SCHEMA.md) and [05](05-API-CONTRACT.md) updated with the Task 2 resolutions
- [ ] `docs/openapi.yaml` validates; 12 endpoints fully specified
- [ ] Postman collection committed and self-authenticating

Then **apply the two edits to [08](08-TASK-BREAKDOWN-PHASE-3.md)**: shrink Task 5 to "point Spring at the existing migrations, confirm `ddl-auto: validate` passes" (30 min), and delete Task 6. Renumber.

Commit and tag `phase-2-complete`.

**⛓️ Dependencies** Tasks 1–15.
**✅ Expected Output** All eight items tick. Doc 08 updated. Tag pushed.
**⏱️** 1 hour.

---

## 📅 Suggested Daily Schedule

| Day | Hours | Tasks |
|---|---|---|
| 1 | 3.0 | T1 Design trace (3.0) |
| 2 | 4.0 | T2 Gap resolution (2.5) · T3 Migration roadmap (1.5) |
| 3 | 4.0 | T4 `V1` (1.0) · T5 `V2` (1.5) · T6 `V3` (1.5) |
| 4 | 3.5 | T7 `V4` (2.0) · T8 `V5` (1.0) *— overrun likely* |
| 5 | 3.0 | T9 `V6` (1.5) · T10 `V7` (1.5) |
| 6 | 3.5 | T11 Apply & verify (2.0) · T12 Structural guarantees (1.5) |
| 7 | 3.5 | T13 Repeatability script (1.5) · start T14 |
| 8 | 3.5 | T14 OpenAPI spec (3.5) |
| 9 | 3.5 | T15 Postman (2.5) · T16 Handoff (1.0) |

**Day 10 — Buffer.** Realistically consumed by Day 3–5's migrations: expect two or three SQL typos per file on first application, and each one costs a drop-and-recreate cycle.

*Days 3–5 are three consecutive days of transcribing SQL. It is tedious and it feels like it should be faster than it is. Resist copying all seven files in one go and applying at the end — **apply after each file**, so a failure points at a file you wrote ten minutes ago rather than one of seven.*

---

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE SUMMARY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Total Tasks    : 16   (2A: 3 · 2B: 10 · 2C: 2 · 2D: 1)
Total Estimate : 29–32 hours
Suggested Days : 9 working days + 1 buffer
                 (doc 07 allowed 3–4 days for all nine of its
                  Phase 2 items, of which ~2 days were the skills
                  that are already done)

Hardest Task   : Task 1 — the end-to-end design trace.
                 Not difficult; genuinely tedious, and the only task
                 whose value is entirely in what it PREVENTS. Tracing
                 backward — every table has a writer, every endpoint
                 has a screen — is what finds the gaps, and it is the
                 direction people skip because forward tracing feels
                 like it covered it.

Runner-up      : Task 14 — the OpenAPI spec.
                 3.5 hours of YAML with no runnable output. It is also
                 the artifact that lets the frontend be built against
                 a contract rather than against whatever the backend
                 happened to return.

Most Skipped   : Task 12 — hand-testing the structural guarantees.
                 It writes no code and creates no artifact; you insert
                 rows and watch them fail. Those six constraints carry
                 the correctness properties the whole project rests on
                 — exactly one open clock segment, exactly one
                 escalation per rung, one live incident per ticket —
                 and a partial index written without its WHERE clause
                 creates successfully, behaves plausibly, and breaks
                 silently in week 12.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## Phase 2 exit checklist

- [ ] `ops/verify-migrations.sh` green from a dropped-and-recreated database
- [ ] `flyway_schema_history` shows 7 successful migrations
- [ ] 39 tables; every index present; partial indexes confirmed partial
- [ ] All four trigger functions fire correctly — tested, not assumed
- [ ] **Six structural guarantees fail as designed; three positive paths succeed**
- [ ] Editing an applied migration produces a checksum failure *(and you have seen it)*
- [ ] `docs/design-trace.md` — every table has a writer and a reader
- [ ] `docs/migrations.md` — every version reserved through `V14`
- [ ] Docs 04 and 05 updated with the Task 2 resolutions
- [ ] `docs/openapi.yaml` validates; 12 endpoints fully specified with real examples
- [ ] Postman collection self-authenticates; Tickets folder runs top to bottom
- [ ] Doc 08 amended — Task 5 shrunk, Task 6 deleted

---

## Closing note — planning is complete

> ✅ **Every phase of ResolveAI is now broken down: 17 documents, 241 tasks, ~135 working days.**
>
> *(242 at the time of writing. Phase 3 Task 6 was absorbed into Phase 2 Task 12 during the
> Phase 2 handoff, and Phase 3 Task 5 shrank from 1.5 hours to 30 minutes.)*
>
> ```
> Phase 1   ████  9–10 d     Phase 6   ████████  21 d
> Phase 2   ████  9 d        Phase 7   ████████  20 d
> Phase 3   ███   7 d        Phase 8   ████████  19–20 d
> Phase 4   ████  11 d       Phase 9+10 ████████ 19–20 d
> Phase 5   █████████ 23 d   ★ early deploy  3 d
> ```
>
> **Full scope, nothing skipped: ~135 days ≈ 27–28 weeks** at 3–4 focused hours a day.
>
> **Three things to carry out of the planning phase:**
>
> 1. **Start at [14 Task 1](14-TASK-BREAKDOWN-PHASE-1.md), then come here.** Phase 1 before Phase 2 — you need Docker and Postgres running before you can apply a migration.
> 2. **Deploy after Phase 6A, not at the end** ([13 ★](13-TASK-BREAKDOWN-PHASE-9-10.md)). From week 11 you always have a live URL, and you find out about pgvector on managed Postgres in week 11 rather than week 24.
> 3. **Update the resume at every checkpoint.** Two bullets by week 9, three by week 11, four by week 16, five by week 22. You apply with what is finished, not with what is planned.
>
> The estimate moved from 8 weeks to 27 across eight rounds of breakdown. **It did not move because the project grew — it moved because guesses were replaced with counted work.** A 27-week number you can hold yourself to is worth more than an 8-week number that was never real, and the checkpoint map means you are competitive from week 9 either way.
>
> **Nothing is left to plan. Start building.**
