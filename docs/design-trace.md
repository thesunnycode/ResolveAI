# Design trace — Phase 2, Task 1

Traceability matrix across [02 §3](planning/02-PROJECT-PLAN.md) features,
[04](planning/04-DATABASE-SCHEMA.md) tables, [05](planning/05-API-CONTRACT.md) endpoints,
[06](planning/06-UI-UX-DESIGN.md) screens, and the task breakdowns in 08–15.

Checked in **both directions**. The forward direction (every feature has endpoints, tables
and a screen) confirms you wrote down what you planned. **The backward direction — every
table has a writer and a reader, every endpoint has a screen — is the one that finds
problems**, and it found six.

---

## Counts, as actually declared in the docs

| | Declared | Source |
|---|---|---|
| Tables | **37** + 2 folded in ([16 T2](planning/16-TASK-BREAKDOWN-PHASE-2.md)) = **39** | `CREATE TABLE` statements in doc 04 |
| Endpoints | **49** | extracted from doc 05 |
| Screens | **13** (9 MVP, 4 deferred) | doc 06 §3 |
| Workers | **6** | doc 03 §3 |

Doc 05 claims 52 endpoints; extraction finds 49 plus 3 `GET` halves of `GET/PUT` admin
pairs written on one line. No design is missing — a formatting artefact.

`GET /tickets/{id}/getAnalysis` appears once, in doc 05 §5's *"one genuine violation,
fixed"* note. Not a live endpoint. ✅

---

## Forward trace — every MVP feature is buildable

| # | Feature ([02 §3](planning/02-PROJECT-PLAN.md)) | Endpoints | Tables | Screens | Worker | Tests |
|---|---|---|---|---|---|---|
| 1 | Ticket lifecycle + state machine | `POST/GET /tickets`, `/{id}`, `/messages`, `/assign`, `/status`, `/resolve`, `/reopen`, `PATCH` | `ticket`, `ticket_message`, `ticket_event`, `tenant_sequence` | Queue, Ticket Detail ×2, New Ticket, My Tickets | — | 5A T5, 5C T32 |
| 2 | Four-role access + tenant isolation | `/auth/*` ×6 | `app_user`, `team`, `tenant`, `refresh_token` | Login, Register | — | 4 T18, T19 |
| 3 | SLA engine | `/tickets/{id}/sla`, `/sla/pause`, `/sla/resume`, `/sla/at-risk` | `business_calendar`, `business_holiday`, `sla_policy`, `sla_record`, `sla_clock_segment`, `sla_escalation` | SLA strip, At-risk list | `sla-poll` | 5B T20–22, 5C T33–34 |
| 4 | Async triage pipeline | `POST /tickets` → 202, `/analysis`, `/retriage` | `outbox_event`, `ai_analysis` | AI panel | `triage` | 6A T8, 6D T30–31 |
| 5 | Signals → deterministic policy | `/priority-rationale`, `/priority-override` | `priority_decision`, `priority_override`, `prompt_version`, `agent_profile` | Rationale popover | — | 6C T23, 6D T32 |
| 6 | Knowledge + hybrid retrieval | `/knowledge/*` ×6 | `knowledge_document`, `knowledge_chunk` | Knowledge Base, KB Form | `index` | 7B T10–11 |
| 7 | Citation-enforced drafting | `/tickets/{id}/drafts`, `/drafts/{id}`, `/action` | `draft`, `draft_claim`, `draft_claim_citation`, `agent_draft_action` | AI panel | `draft` | 7D T22–23 |
| 8 | Incident correlation + fan-out | `/incidents/*` ×8 | `incident`, `incident_ticket`, `incident_update`, `incident_update_delivery`, `ticket_entity` | Incident Board, Detail | `correlate`, `fanout` | 8E T22–25 |
| — | PII + AI governance | `/admin/ai-policy` | `tenant_ai_policy`, `pii_redaction_map` | Settings › AI | — | 6B T13 |
| — | CI evaluation gate | `/admin/eval/runs` | `eval_case`, `eval_run`, `eval_result` | Eval Dashboard | — | 6D T34, 7D T24 |

**Forward: clean.** Every feature has endpoints, tables, a screen and a test.

---

## Backward trace — six gaps

### 🔴 GAP 1 — `attachment` is referenced but has no endpoint

`attachmentIds` appears **three times** in doc 05 request bodies (`POST /tickets`,
`POST /tickets/{id}/messages`), but **`POST /api/v1/attachments` is never defined.**
[10 T17](planning/10-TASK-BREAKDOWN-PHASE-5.md) describes a two-step presigned upload in
prose; the contract never specifies it.

A client reading doc 05 cannot obtain an `attachmentId`. The table has **no writer**.

**Resolution:** add three endpoints in Task 2.

---

### 🔴 GAP 2 — `notification` has a writer but no reader

The SLA poller ([10 T29](planning/10-TASK-BREAKDOWN-PHASE-5.md)) and fan-out worker
([12 T19](planning/12-TASK-BREAKDOWN-PHASE-8.md)) both **write** notifications. Doc 06's
navigation shows a bell with an unread count in **seven** places, and `AppShell` takes an
`unreadCount` prop.

**Nothing reads the table.** There is no `/api/v1/notifications`.

This is the clearest instance of why the backward direction matters: the escalation ladder
is one of the three signature mechanisms, it dutifully writes a notification at every rung,
and **no user can ever see one.**

**Resolution:** add three endpoints in Task 2.

---

### 🟠 GAP 3 — `agent_profile` cannot be modified by anyone

`RoutingPolicy.claimLeastLoadedAgent` ([11 T25](planning/11-TASK-BREAKDOWN-PHASE-6.md))
filters on `is_available`, `max_concurrent` and `shift_start/end`. `open_count` is
maintained internally by assignment and the nightly reconciliation job.

But **no endpoint sets `is_available`.** An agent cannot mark themselves away for lunch; an
admin cannot change a capacity ceiling. The field is read on every routing decision and
writable by nobody.

**Resolution:** add two endpoints in Task 2.

---

### 🟠 GAP 4 — `team` has no write path

Teams are created only by the Phase 4 seeder ([09 T17](planning/09-TASK-BREAKDOWN-PHASE-4.md)).
Doc 05 lists `GET /admin/users` as *"manage users and teams"* but defines no team endpoint.
`team.skills[]` drives routing ([11 T24](planning/11-TASK-BREAKDOWN-PHASE-6.md)) and cannot
be changed without a database edit.

**Resolution:** add three endpoints in Task 2.

---

### 🟡 GAP 5 — `business_holiday` writer unspecified

Presumably managed through `PUT /admin/calendar`, but doc 05's calendar payload does not
mention holidays. Business-hours arithmetic ([10 T21](planning/10-TASK-BREAKDOWN-PHASE-5.md))
depends on them.

**Resolution:** specify holidays as part of the calendar payload in Task 2. No new endpoint.

---

### 🟢 GAP 6 — three tables are intentionally endpoint-free

Not defects, but they were never stated, so a future reader would flag them:

| Table | Written by | Read by | Why no endpoint |
|---|---|---|---|
| `tenant` | seeder | everything | No self-serve tenant signup in scope. Onboarding a tenant is an operational procedure. |
| `prompt_version` | Flyway migrations | every AI call | Prompts ship **with the code** so a prompt change is a reviewable diff and a CI-gated deploy, not a runtime edit. This is deliberate and worth saying out loud. |
| `eval_case` | migrations, hand-labelling, `/incidents/{id}/confirm`+`reject` | eval runner | Eval cases are test fixtures. A UI to edit your own test data defeats the point. |

**Resolution:** document as explicit non-goals in doc 05. No endpoints.

---

## Screen → data trace

All 13 screens have their data needs met by existing endpoints, **with one exception**: the
notification bell in `AppShell` (doc 06 §7) depends on GAP 2.

| Screen | Endpoints | OK? |
|---|---|---|
| Login, Register | `/auth/login`, `/auth/register` | ✅ |
| My Tickets, New Ticket, Ticket Detail (Customer) | `/tickets` (role-scoped), `POST /tickets`, `/tickets/{id}` | ✅ |
| Agent Queue | `/tickets`, `/sla/at-risk`, `/assign` | ✅ |
| Ticket Detail (Agent) | 11 endpoints | ✅ |
| Incident Board, Incident Detail | `/incidents/*` | ✅ |
| Knowledge Base, KB Form | `/knowledge/*` | ✅ |
| Settings (3 tabs) | `/admin/sla-policies`, `/admin/calendar`, `/admin/ai-policy` | ⚠️ holidays — GAP 5 |
| Eval Dashboard | `/admin/eval/runs`, `/admin/ai/usage` | ✅ |
| **AppShell bell** | **none** | 🔴 **GAP 2** |

---

## Summary

| | Count |
|---|---|
| Tables with a writer **and** a reader | 36 / 39 |
| Tables with a writer but **no reader** | 1 — `notification` (GAP 2) |
| Tables with a reader but **no writer** | 2 — `attachment` (GAP 1), `agent_profile` partial (GAP 3) |
| Intentionally endpoint-free, now documented | 3 (GAP 6) |
| Endpoints with no screen | 0 |
| Screens with unmet data needs | 1 — AppShell bell (GAP 2) |

**Net: 8 endpoints to add, 1 payload to extend, 3 non-goals to document.** All applied in
Task 2, before a single migration is written.

Two of these — attachments and notifications — would have surfaced mid-Phase 5 or Phase 6 as
*"wait, how does the client actually get one of these?"*, at which point the API contract,
the OpenAPI spec and the Postman collection would all already have been written against a
design with a hole in it. Three hours here.
