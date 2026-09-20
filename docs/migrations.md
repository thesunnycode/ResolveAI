# Migration roadmap

Phase 2, Task 3. **Every version number is reserved here before any migration is written**,
so two phases cannot collide on `V9` and a future reader can see the whole plan.

---

## Two rules that prevent the most common Flyway disaster

**1. Never edit an applied migration.** Flyway checksums every file. Editing one that has
already run fails *every subsequent startup* with an error that does not explain itself.
If a migration is wrong, write the next one.

> Deliberately trip this once, in [Task 13](planning/16-TASK-BREAKDOWN-PHASE-2.md) — change
> one character, watch it fail, revert. Knowing what the failure looks like, and that it is
> Flyway protecting you rather than a bug, saves an hour of panic the first time it happens
> for real.

**2. Forward-only.** No `DROP` of a column holding data without a documented backfill in the
same migration. No destructive change that a rollback would have to undo — Flyway Community
has no `undo`.

---

## The plan

| Version | Contents | Written in | Status |
|---|---|---|---|
| `V1` | Extensions, `tenant`, `team`, `app_user`, `agent_profile`, **`tenant_sequence`**, `refresh_token` | **Phase 2** | Task 4 |
| `V2` | `ticket`, `draft` (stub), `ticket_message`, `ticket_event`, **`ticket_entity`**, `attachment` | **Phase 2** | Task 5 |
| `V3` | `business_calendar`, `business_holiday`, `sla_policy`, `sla_record`, `sla_clock_segment`, `sla_escalation` | **Phase 2** | Task 6 |
| `V4` | `prompt_version`, `ai_analysis`, `priority_decision`, `priority_override`, `knowledge_document`, `knowledge_chunk`, `draft_claim`, `draft_claim_citation`, `agent_draft_action` | **Phase 2** | Task 7 |
| `V5` | `incident`, `incident_ticket`, `incident_update`, `incident_update_delivery` | **Phase 2** | Task 8 |
| `V6` | `outbox_event`, `idempotency_record`, `tenant_ai_policy`, `pii_redaction_map`, `notification`, `eval_case`, `eval_run`, `eval_result`, deferred FK on `sla_escalation` | **Phase 2** | Task 9 |
| `V7` | Trigger functions and triggers | **Phase 2** | Task 10 |
| `V8` | *(reserved)* | — | — |
| `V9` | `resolved_ticket_stats` materialised view | Phase 5 | [10 T30](planning/10-TASK-BREAKDOWN-PHASE-5.md) |
| `V10` | Seed `triage@1` prompt | Phase 6 | [11 T14](planning/11-TASK-BREAKDOWN-PHASE-6.md) |
| `V11` | *(reserved)* | — | — |
| `V12` | `ticket_arrival_baseline` materialised view | Phase 8 | [12 T5](planning/12-TASK-BREAKDOWN-PHASE-8.md) |
| `V13` | Seed `incident_title@1` prompt | Phase 8 | [12 T10](planning/12-TASK-BREAKDOWN-PHASE-8.md) |
| `V14` | Seed `draft@1` and `entailment@1` prompts | Phase 7 | [15 T12](planning/15-TASK-BREAKDOWN-PHASE-7.md) |

**39 tables total** after `V1`–`V7`.

---

## Two things about this table that look wrong and are not

**`V14` is written before `V13`.** Phase 7 runs after Phase 6 but *before* Phase 8 in the
build order, yet its migration is numbered higher. Harmless: Flyway applies in **version
order**, not authoring order, and these are independent seed rows. Renumbering later would
mean editing an applied migration, which rule 1 forbids.

**`V8` and `V11` are gaps.** Left as documented holes rather than compacted, for the same
reason — the moment `V9` is applied anywhere, renumbering it to `V8` is a checksum failure.
A gap in the sequence costs nothing; a renumber costs an afternoon.

---

## Why some tables are here in Phase 2 and others are deferred

The rule applied in [Task 2](planning/16-TASK-BREAKDOWN-PHASE-2.md):

> **Fold in structural tables. Defer derived views and seed data.**

A table is *structural* if its shape is fully determined by the design now.
`tenant_sequence` and `ticket_entity` are — they are plain tables with obvious columns, and
their absence made the schema literally incomplete (`ticket.reference` is `NOT NULL` with no
way to generate a value).

A **materialised view's** shape depends on what the query needs once there is real data to
look at, and a **prompt seed** depends on prompt text that has not been written. Writing
either now means rewriting it later — and rewriting means a *new* migration anyway, so
nothing is saved.

---

## Naming and layout

```
src/main/resources/db/migration/
  V1__extensions_and_tenancy.sql
  V2__ticketing.sql
  V3__sla.sql
  V4__ai_and_knowledge.sql
  V5__incidents.sql
  V6__platform_and_eval.sql
  V7__triggers.sql
```

- Double underscore between version and description — Flyway requires it.
- One concern per file. A failure then points at a file you wrote ten minutes ago.
- Each file runs in **one transaction**, so a mid-file error rolls the whole file back. That
  is what you want: no half-applied migration.
- **Apply after writing each file**, not all seven at the end
  ([16 §schedule](planning/16-TASK-BREAKDOWN-PHASE-2.md)). Expect two or three SQL typos per
  file on first application.

## Verification

`ops/verify-migrations.sh` ([Task 13](planning/16-TASK-BREAKDOWN-PHASE-2.md)) drops and
recreates the database, migrates from empty, and asserts table, index and trigger counts
plus the six structural guarantees. It runs in CI ([13 T16](planning/13-TASK-BREAKDOWN-PHASE-9-10.md))
and is what the `docker compose down -v` step in every phase's exit checklist exercises.
