# 04 — Database Schema

> Skill: `db-schema-designer` · Input: [02 — Project Plan](02-PROJECT-PLAN.md) §3/§8/§9
> Target: **PostgreSQL 16** with `vector` and `pg_trgm` extensions

---

## STEP 2 — Derived Table List

Derived from the MVP features and cross-checked against every endpoint in §9 of the project plan. Confirm this list before reading the full specs.

| # | Group | Tables |
|---|---|---|
| 1 | **Tenancy & identity** (5) | `tenant`, `app_user`, `team`, `agent_profile`, `refresh_token` |
| 2 | **Ticketing** (4) | `ticket`, `ticket_message`, `ticket_event`, `attachment` |
| 3 | **SLA** (6) | `business_calendar`, `business_holiday`, `sla_policy`, `sla_record`, `sla_clock_segment`, `sla_escalation` |
| 4 | **AI triage** (4) | `prompt_version`, `ai_analysis`, `priority_decision`, `priority_override` |
| 5 | **Knowledge** (2) | `knowledge_document`, `knowledge_chunk` |
| 6 | **Drafting** (4) | `draft`, `draft_claim`, `draft_claim_citation`, `agent_draft_action` |
| 7 | **Incidents** (4) | `incident`, `incident_ticket`, `incident_update`, `incident_update_delivery` |
| 8 | **Platform** (5) | `outbox_event`, `idempotency_record`, `tenant_ai_policy`, `pii_redaction_map`, `notification` |
| 9 | **Evaluation** (3) | `eval_case`, `eval_run`, `eval_result` |

**37 tables.**

### How the list was derived — the non-obvious ones

| Table | Why it exists (and why the obvious alternative is wrong) |
|---|---|
| `agent_profile` | Routing needs `open_count` and `is_available` under a **row lock**. Putting these on `app_user` means locking the identity row on every assignment — contention on a table read by every authenticated request. Separate table, separate lock. |
| `sla_clock_segment` | Because `elapsed_minutes` as a mutable column is a lost update waiting to happen. Append-only segments make elapsed time derived, reconstructible and auditable. |
| `sla_escalation` | Junction-ish table whose only job is the `UNIQUE (sla_record_id, rung)` constraint that turns an at-least-once poller into exactly-once side effects. |
| `priority_decision` | Separate from `ai_analysis` because the AI produced *signals* and the policy produced the *decision*. Storing them together would make it impossible to tell whether a wrong priority was a model error or a policy error — and those are different bugs. |
| `draft_claim` + `draft_claim_citation` | A draft is not a blob of text. It is a set of independently-verifiable claims, each with its own verdict. Storing the prose would make claim-level groundedness unmeasurable. |
| `agent_draft_action` | Captures `SENT_AS_IS` / `EDITED` / `DISCARDED` and the edit distance. **The best quality metric in the system**, measured from behaviour rather than asserted by another model. |
| `incident_update_delivery` | Fan-out is N independent deliveries with partial-failure semantics. Without a per-ticket row there is no way to retry delivery 23 without re-sending the other 37. |
| `pii_redaction_map` | Placeholder ↔ encrypted original. A junction table rather than a JSONB column on the ticket, because it is queried by placeholder during rehydration and must be independently purgeable on a data-deletion request. |
| `refresh_token` | Refresh tokens are stored **hashed and single-use**. A row per token is what makes reuse detection possible. |

### Tables deliberately NOT created

| Not created | Why |
|---|---|
| `role` table | Four fixed roles, never user-configurable. A `VARCHAR` + `CHECK` is correct; a lookup table with four rows is a join for nothing. |
| `ticket_tag` / `tag` | Not in MVP. When added it will be a proper junction table, never a comma-separated column. |
| `category` table | Categories are a fixed enum driven by the prompt's output schema. A table would let them drift out of sync with the schema the model is constrained to. |
| `sla_breach` | A breach is a *state* of `sla_record` (`state = 'BREACHED'`, `breached_at`), plus the rung-100 row in `sla_escalation`. A separate table would duplicate truth. |
| `team_member` junction | An agent belongs to exactly one team in MVP — `app_user.team_id` is enough. Many-to-many is a v2 change. |

---

## STEP 3 — Complete Table Specifications

Conventions used throughout:
- **All PKs are `BIGSERIAL`** except where noted — see Step 5 for the justification.
- **All timestamps are `TIMESTAMPTZ`** — never `TIMESTAMP`.
- **Every table carrying tenant data has `tenant_id`**, even when reachable via a parent, so the Hibernate tenant filter can apply uniformly and a missing join cannot leak rows.
- **Enums are `VARCHAR` + named `CHECK`** — see Step 5.

---

### Group 1 — Tenancy & Identity

#### TABLE: `tenant`

**Purpose:** An organisation using ResolveAI. The isolation boundary for every other table.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `name` | VARCHAR(150) | NOT NULL | — | Display name |
| `slug` | VARCHAR(60) | NOT NULL, UNIQUE | — | URL-safe identifier, used in ticket references |
| `plan_tier` | VARCHAR(20) | NOT NULL, CHECK | `'FREE'` | `FREE`, `PRO`, `ENTERPRISE` — an **input to the priority policy**, not decoration |
| `is_active` | BOOLEAN | NOT NULL | `TRUE` | Suspends all access without deleting data |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Primary key:** `BIGSERIAL`. Tenant count is small and tenant IDs never leave the server (they come from the JWT, never a URL).

**Foreign keys:** none — this is the root.

**Relationships:** one tenant → many of everything.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_tenant_slug` | `slug` | Unique B-tree | Slug lookup during tenant resolution |

---

#### TABLE: `app_user`

**Purpose:** Any human in the system — customer, agent, team lead or admin.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `email` | VARCHAR(255) | NOT NULL | — | Unique **per tenant**, not globally — the same person may be a customer of two tenants |
| `password_hash` | VARCHAR(72) | NOT NULL | — | BCrypt, work factor 12. Never plaintext. 72 is BCrypt's output length. |
| `full_name` | VARCHAR(120) | NOT NULL | — | |
| `role` | VARCHAR(20) | NOT NULL, CHECK | `'CUSTOMER'` | `CUSTOMER`, `AGENT`, `TEAM_LEAD`, `ADMIN` |
| `team_id` | BIGINT | NULL, FK | `NULL` | NULL for customers and admins |
| `is_active` | BOOLEAN | NOT NULL | `TRUE` | |
| `last_login_at` | TIMESTAMPTZ | NULL | `NULL` | |
| `deleted_at` | TIMESTAMPTZ | NULL | `NULL` | **Soft delete** — a deleted user's tickets must survive |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Primary key:** `BIGSERIAL`.

**Foreign keys:**
```sql
FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE RESTRICT
FOREIGN KEY (team_id)   REFERENCES team(id)   ON DELETE SET NULL
```
- `tenant_id` → **RESTRICT**: deleting a tenant that still has users must fail loudly. A cascade here would silently destroy an entire organisation's history because of one mis-typed admin action.
- `team_id` → **SET NULL**: disbanding a team must not delete its agents. They become unassigned and an admin reassigns them.

**Relationships:** belongs to one `tenant`; optionally belongs to one `team`; has many `ticket` (as requester and as assignee); has one `agent_profile` if an agent.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_user_tenant_email` | `(tenant_id, email) WHERE deleted_at IS NULL` | **Partial** unique | Login lookup, and allows the same email to be reused after a soft delete |
| `idx_user_tenant_role` | `(tenant_id, role)` | B-tree | Admin user lists filter by role |
| `idx_user_team` | `(team_id)` | B-tree | FK — team roster queries |

---

#### TABLE: `team`

**Purpose:** A routing destination. Tickets are routed to a team first, then to an agent within it.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `name` | VARCHAR(80) | NOT NULL | — | e.g. `Payments`, `Infrastructure` |
| `skills` | TEXT[] | NOT NULL | `'{}'` | Categories this team handles. **A real array, not a CSV string** — it is queried with the `&&` overlap operator and GIN-indexed. |
| `is_default` | BOOLEAN | NOT NULL | `FALSE` | Catch-all for unroutable tickets |
| `deleted_at` | TIMESTAMPTZ | NULL | `NULL` | Soft delete |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE RESTRICT` — same reasoning as `app_user`.

**Relationships:** belongs to one `tenant`; has many `app_user`; has many `ticket`.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_team_tenant_name` | `(tenant_id, name) WHERE deleted_at IS NULL` | Partial unique | Names are unique per tenant while live |
| `idx_team_skills` | `skills` | **GIN** | The router asks "which teams handle `PAYMENT`?" — `skills && ARRAY['PAYMENT']`. A B-tree cannot answer array containment. |
| `idx_team_default` | `(tenant_id) WHERE is_default` | Partial | Fallback lookup, one row per tenant |

---

#### TABLE: `agent_profile`

**Purpose:** Routing capacity and availability. **Separate from `app_user` specifically so the routing row lock does not contend with the identity row read on every request.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `user_id` | BIGINT | NOT NULL, UNIQUE, FK | — | One profile per agent |
| `tenant_id` | BIGINT | NOT NULL, FK | — | Denormalised for filter uniformity |
| `max_concurrent` | INTEGER | NOT NULL, CHECK > 0 | `15` | Capacity ceiling |
| `open_count` | INTEGER | NOT NULL, CHECK >= 0 | `0` | **The contended column.** Incremented inside the assignment transaction under `FOR UPDATE`. |
| `is_available` | BOOLEAN | NOT NULL | `TRUE` | Manual away toggle |
| `shift_start` / `shift_end` | TIME | NULL | `NULL` | NULL means always on shift |
| `version` | INTEGER | NOT NULL | `0` | JPA `@Version` — optimistic lock for profile edits |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:**
```sql
FOREIGN KEY (user_id)   REFERENCES app_user(id) ON DELETE CASCADE
FOREIGN KEY (tenant_id) REFERENCES tenant(id)   ON DELETE RESTRICT
```
- `user_id` → **CASCADE**: the profile is meaningless without the user; it is pure extension data with no independent value. This is the one place cascade is clearly right.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_agent_profile_user` | `user_id` | Unique | One-to-one enforcement |
| `idx_agent_routing` | `(tenant_id, is_available, open_count)` | B-tree | **The routing query**: `WHERE tenant_id=? AND is_available ORDER BY open_count LIMIT 1 FOR UPDATE SKIP LOCKED`. Without this index the lock is taken *after* a sequential scan, which is how you turn a fast lock into a slow one. |

---

#### TABLE: `refresh_token`

**Purpose:** Single-use, rotating refresh tokens stored hashed, enabling reuse detection.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `user_id` | BIGINT | NOT NULL, FK | — | |
| `token_hash` | CHAR(64) | NOT NULL, UNIQUE | — | SHA-256 of the token. **The raw token is never stored.** |
| `family_id` | UUID | NOT NULL | — | All tokens descended from one login share a family |
| `expires_at` | TIMESTAMPTZ | NOT NULL | — | |
| `used_at` | TIMESTAMPTZ | NULL | `NULL` | Non-null = already rotated. **Presenting a used token invalidates the whole family** — that is reuse detection. |
| `revoked_at` | TIMESTAMPTZ | NULL | `NULL` | |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE` — deleting a user must revoke their sessions; a token with no user is a security hole, not history worth keeping.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_refresh_hash` | `token_hash` | Unique | The lookup on every refresh |
| `idx_refresh_family` | `family_id` | B-tree | Family-wide revocation on reuse detection |
| `idx_refresh_expiry` | `expires_at` | B-tree | Nightly cleanup of expired rows |

---

### Group 2 — Ticketing

#### TABLE: `ticket`

**Purpose:** The central entity. A customer's request, from creation to closure.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `reference` | VARCHAR(24) | NOT NULL | — | Human-readable, e.g. `TKT-10428`. Unique per tenant. **Never expose `id` in a URL a human reads.** |
| `subject` | VARCHAR(200) | NOT NULL, CHECK length 1–200 | — | |
| `body` | TEXT | NOT NULL, CHECK length ≤ 20000 | — | **The cap is a security control**, not a style choice — an uncapped body is a token-cost denial of service. |
| `status` | VARCHAR(24) | NOT NULL, CHECK | `'OPEN'` | `OPEN`, `TRIAGED`, `ASSIGNED`, `IN_PROGRESS`, `WAITING_ON_CUSTOMER`, `PENDING_THIRD_PARTY`, `RESOLVED`, `CLOSED` |
| `priority` | VARCHAR(12) | NOT NULL, CHECK | `'UNTRIAGED'` | `UNTRIAGED`, `P1`…`P4`. Written **only** by the priority policy or an explicit override. |
| `category` | VARCHAR(40) | NULL | `NULL` | NULL until triage completes |
| `requester_id` | BIGINT | NOT NULL, FK | — | |
| `assignee_id` | BIGINT | NULL, FK | `NULL` | NULL = unassigned |
| `team_id` | BIGINT | NULL, FK | `NULL` | |
| `reopen_count` | INTEGER | NOT NULL, CHECK >= 0 | `0` | An **input to the priority policy** — repeatedly reopened tickets escalate |
| `first_responded_at` | TIMESTAMPTZ | NULL | `NULL` | Written in the **same transaction** as the first public agent message — this is what closes the race with the SLA poller |
| `resolved_at` / `closed_at` | TIMESTAMPTZ | NULL | `NULL` | |
| `embedding` | vector(768) | NULL | `NULL` | For incident clustering. NULL until the triage worker embeds it. |
| `search_tsv` | tsvector | NULL | `NULL` | Generated from subject + body for queue search |
| `version` | INTEGER | NOT NULL | `0` | `@Version` — optimistic lock behind `ETag`/`If-Match` |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Primary key:** `BIGSERIAL`, with `reference` as the public identifier. Sequential integer IDs are guessable, which is exactly why they are never exposed.

**Foreign keys:**
```sql
FOREIGN KEY (tenant_id)    REFERENCES tenant(id)   ON DELETE RESTRICT
FOREIGN KEY (requester_id) REFERENCES app_user(id) ON DELETE RESTRICT
FOREIGN KEY (assignee_id)  REFERENCES app_user(id) ON DELETE SET NULL
FOREIGN KEY (team_id)      REFERENCES team(id)     ON DELETE SET NULL
```
- `requester_id` → **RESTRICT**: a ticket must always have a requester. Users are soft-deleted, so this never blocks legitimate offboarding.
- `assignee_id` → **SET NULL**: an agent leaving must return their tickets to the queue, not delete them.

**Relationships:** belongs to `tenant`, `app_user` (requester), optionally `app_user` (assignee) and `team`. Has many `ticket_message`, `ticket_event`, `sla_record`, `ai_analysis`, `draft`. Has at most one live `incident_ticket`.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_ticket_reference` | `(tenant_id, reference)` | Unique | Public lookup by reference |
| `idx_ticket_queue` | `(tenant_id, status, priority, created_at DESC)` | **Composite B-tree** | **The single highest-traffic query in the application** — the agent queue. Without it, a sequential scan runs on every queue refresh. |
| `idx_ticket_assignee` | `(tenant_id, assignee_id, status)` | Composite | "My tickets" |
| `idx_ticket_team` | `(tenant_id, team_id, status)` | Composite | Team queue |
| `idx_ticket_requester` | `(tenant_id, requester_id, created_at DESC)` | Composite | Customer portal list |
| `idx_ticket_search` | `search_tsv` | **GIN** | Full-text queue search |
| `idx_ticket_embedding` | `embedding` | **HNSW** (cosine) | Incident clustering similarity |
| `idx_ticket_recent` | `(tenant_id, created_at) WHERE status NOT IN ('RESOLVED','CLOSED')` | Partial | The correlation sweep scans only recent open tickets — a partial index keeps it small as closed tickets accumulate |

---

#### TABLE: `ticket_message`

**Purpose:** One message in the conversation thread.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `author_id` | BIGINT | NOT NULL, FK | — | |
| `body` | TEXT | NOT NULL, CHECK length ≤ 20000 | — | |
| `visibility` | VARCHAR(10) | NOT NULL, CHECK | `'PUBLIC'` | `PUBLIC` (customer sees) / `INTERNAL` (agents only). **A bug here is a customer-visible data leak** — hence a CHECK, not an application constant. |
| `is_first_response` | BOOLEAN | NOT NULL | `FALSE` | Set on the first PUBLIC message by a non-requester |
| `from_draft_id` | BIGINT | NULL, FK | `NULL` | Links a sent message back to the AI draft it came from |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | No `updated_at` — **messages are immutable** |

**Foreign keys:**
```sql
FOREIGN KEY (ticket_id)     REFERENCES ticket(id)   ON DELETE CASCADE
FOREIGN KEY (tenant_id)     REFERENCES tenant(id)   ON DELETE RESTRICT
FOREIGN KEY (author_id)     REFERENCES app_user(id) ON DELETE RESTRICT
FOREIGN KEY (from_draft_id) REFERENCES draft(id)    ON DELETE SET NULL
```
- `ticket_id` → **CASCADE**: a message has no meaning without its ticket. (Tickets are only hard-deleted on a genuine data-erasure request, where cascading is exactly what is wanted.)
- `author_id` → **RESTRICT**: "who said this" must never become unknowable. Users are soft-deleted.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_message_thread` | `(ticket_id, created_at)` | Composite | Renders the thread in order — every ticket-detail page load |
| `idx_message_author` | `(author_id)` | B-tree | FK |
| `idx_message_first_response` | `(ticket_id) WHERE is_first_response` | Partial | At most one row per ticket; used by the SLA check |

---

#### TABLE: `ticket_event`

**Purpose:** Append-only audit of every state transition. **Never updated, never deleted.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `actor_id` | BIGINT | NULL, FK | `NULL` | **NULL means the system did it** — a worker, the poller. Distinguishing system actions from human ones is the point of the column. |
| `event_type` | VARCHAR(40) | NOT NULL, CHECK | — | `CREATED`, `TRIAGED`, `ASSIGNED`, `STATUS_CHANGED`, `PRIORITY_CHANGED`, `MESSAGE_ADDED`, `SLA_STARTED`, `SLA_PAUSED`, `SLA_RESUMED`, `SLA_ESCALATED`, `SLA_BREACHED`, `SLA_MET`, `INCIDENT_LINKED`, `INCIDENT_DETACHED`, `RESOLVED`, `REOPENED`, `CLOSED` |
| `from_value` / `to_value` | VARCHAR(80) | NULL | `NULL` | Old and new values for transitions |
| `payload` | JSONB | NULL | `NULL` | Extra context — the policy rationale, the escalation rung |
| `occurred_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `ticket_id` → CASCADE; `tenant_id` → RESTRICT; `actor_id` → SET NULL (the audit survives the user).

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_event_ticket` | `(ticket_id, occurred_at)` | Composite | Timeline rendering |
| `idx_event_audit` | `(tenant_id, event_type, occurred_at DESC)` | Composite | The admin audit filter |

**No `updated_at`, no soft delete, no UPDATE path in the repository.** Enforced by a trigger raising an exception on UPDATE or DELETE — see Step 6.

---

#### TABLE: `attachment`

**Purpose:** Metadata for a file. **The bytes live in MinIO/S3, never in Postgres.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `message_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `storage_key` | VARCHAR(255) | NOT NULL, UNIQUE | — | **A generated UUID path, never the user's filename** — prevents traversal and IDOR by guessing |
| `original_filename` | VARCHAR(255) | NOT NULL | — | Display only. Never used to build a path. |
| `mime_type` | VARCHAR(100) | NOT NULL | — | Verified against **actual magic bytes**, not the declared header |
| `byte_size` | BIGINT | NOT NULL, CHECK > 0 AND <= 10485760 | — | 10 MB cap enforced in the database as well as the application |
| `content_sha256` | CHAR(64) | NOT NULL | — | Deduplication and integrity |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `message_id` → CASCADE (an attachment without its message is orphaned storage); `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_attachment_key` | `storage_key` | Unique | Storage-key collisions must be impossible |
| `idx_attachment_message` | `(message_id)` | B-tree | FK — rendering a message's attachments |

---

### Group 3 — SLA

#### TABLE: `business_calendar`

**Purpose:** Working hours and timezone for a tenant. The basis of all business-hours arithmetic.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, UNIQUE, FK | — | One calendar per tenant in MVP |
| `timezone` | VARCHAR(64) | NOT NULL | `'Asia/Kolkata'` | IANA name — **never a UTC offset**, because offsets do not know about DST |
| `working_days` | SMALLINT[] | NOT NULL | `'{1,2,3,4,5}'` | ISO-8601 day numbers, Monday = 1. An array because a tenant may work Sunday–Thursday. |
| `day_start` / `day_end` | TIME | NOT NULL | `09:00` / `18:00` | Local to `timezone` |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_calendar_tenant` | `tenant_id` | Unique | One per tenant; also the cache key |

---

#### TABLE: `business_holiday`

**Purpose:** Non-working dates that the business-hours function must skip.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `calendar_id` | BIGINT | NOT NULL, FK | — | |
| `holiday_date` | DATE | NOT NULL | — | `DATE`, not `TIMESTAMPTZ` — a holiday is a calendar day, not an instant |
| `name` | VARCHAR(80) | NOT NULL | — | |

**Foreign keys:** `calendar_id` → **CASCADE** (a holiday has no meaning without its calendar).

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_holiday` | `(calendar_id, holiday_date)` | Unique | No duplicate holidays |
| `idx_holiday_range` | `(calendar_id, holiday_date)` | Composite | The business-hours function scans a date range — same index serves both |

---

#### TABLE: `sla_policy`

**Purpose:** Effective-dated SLA targets per priority and plan tier.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `priority` | VARCHAR(12) | NOT NULL, CHECK | — | `P1`…`P4` |
| `plan_tier` | VARCHAR(20) | NOT NULL, CHECK | — | `FREE`, `PRO`, `ENTERPRISE` |
| `first_response_minutes` | INTEGER | NOT NULL, CHECK > 0 | — | **Business** minutes |
| `resolution_minutes` | INTEGER | NOT NULL, CHECK > 0 | — | **Business** minutes |
| `escalation_rungs` | SMALLINT[] | NOT NULL | `'{50,75,90,100}'` | Percentages of budget |
| `effective_from` | TIMESTAMPTZ | NOT NULL | `NOW()` | |
| `effective_to` | TIMESTAMPTZ | NULL | `NULL` | NULL = currently in force |
| `version_label` | VARCHAR(30) | NOT NULL | — | Recorded on every `sla_record` so a March decision is reproducible in September |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `tenant_id` → RESTRICT.

**Effective dating, not mutation:** changing a policy **inserts a new row** and sets `effective_to` on the old one. Never `UPDATE` the targets — historical SLA records must remain explicable.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_sla_policy_live` | `(tenant_id, priority, plan_tier) WHERE effective_to IS NULL` | **Partial unique** | Exactly one live policy per combination — makes an overlapping-policy bug structurally impossible |
| `idx_sla_policy_lookup` | `(tenant_id, priority, plan_tier, effective_from DESC)` | Composite | Point-in-time lookup |

---

#### TABLE: `sla_record`

**Purpose:** One clock on one ticket. The table the poller scans.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `sla_policy_id` | BIGINT | NOT NULL, FK | — | |
| `policy_version` | VARCHAR(30) | NOT NULL | — | **Denormalised deliberately** — the policy row could be superseded; the version label on the record preserves what was actually applied |
| `kind` | VARCHAR(20) | NOT NULL, CHECK | — | `FIRST_RESPONSE` / `RESOLUTION` |
| `target_minutes` | INTEGER | NOT NULL, CHECK > 0 | — | Snapshotted from the policy at creation |
| `state` | VARCHAR(12) | NOT NULL, CHECK | `'RUNNING'` | `RUNNING`, `PAUSED`, `MET`, `BREACHED`, `CANCELLED` |
| `next_deadline_at` | TIMESTAMPTZ | NULL | — | **The most important column in the schema.** Absolute wall-clock instant of the next rung. NULL while PAUSED. Recomputed on pause, resume, priority change and each rung. |
| `next_rung` | SMALLINT | NULL | `50` | Which rung `next_deadline_at` refers to |
| `met_at` / `breached_at` | TIMESTAMPTZ | NULL | `NULL` | |
| `version` | INTEGER | NOT NULL | `0` | `@Version` |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `ticket_id` → CASCADE; `tenant_id` → RESTRICT; `sla_policy_id` → **RESTRICT** (a policy referenced by any record can never be deleted — it is the explanation of a past decision).

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`idx_sla_poller`** | `(state, next_deadline_at) WHERE state = 'RUNNING'` | **Partial composite** | **The most important index in the database.** The poller's query is an index-only range scan with a `LIMIT`, so its cost is proportional to *due* records, not *open* ones. The partial clause keeps the index to only running clocks. |
| `uq_sla_ticket_kind` | `(ticket_id, kind) WHERE state != 'CANCELLED'` | Partial unique | One live first-response clock and one live resolution clock per ticket |
| `idx_sla_ticket` | `(ticket_id)` | B-tree | FK |
| `idx_sla_at_risk` | `(tenant_id, state, next_deadline_at)` | Composite | The at-risk dashboard |

---

#### TABLE: `sla_clock_segment`

**Purpose:** Append-only run/pause intervals. **Elapsed time is `SUM()` over this table — there is no `elapsed_minutes` column anywhere.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `sla_record_id` | BIGINT | NOT NULL, FK | — | |
| `state` | VARCHAR(10) | NOT NULL, CHECK | — | `RUNNING` / `PAUSED` |
| `started_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |
| `ended_at` | TIMESTAMPTZ | NULL | `NULL` | **NULL = the currently open segment** |
| `pause_reason` | VARCHAR(40) | NULL | `NULL` | `WAITING_ON_CUSTOMER`, `PENDING_THIRD_PARTY`, `INCIDENT_LINKED` |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Constraint:** `CHECK (ended_at IS NULL OR ended_at >= started_at)` — a segment cannot end before it starts.

**Foreign keys:** `sla_record_id` → **CASCADE**.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`uq_segment_open`** | `(sla_record_id) WHERE ended_at IS NULL` | **Partial unique** | **Exactly one open segment per record, enforced by the database.** Two concurrent pause requests cannot both succeed — the second hits the constraint. This is the difference between "unlikely" and "impossible". |
| `idx_segment_record` | `(sla_record_id, started_at)` | Composite | The `SUM()` that derives elapsed time |

---

#### TABLE: `sla_escalation`

**Purpose:** One fired rung. Exists almost entirely for its unique constraint.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `sla_record_id` | BIGINT | NOT NULL, FK | — | |
| `rung` | SMALLINT | NOT NULL, CHECK IN (50,75,90,100) | — | |
| `fired_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |
| `notification_id` | BIGINT | NULL, FK | `NULL` | |
| `elapsed_minutes_at_fire` | INTEGER | NOT NULL | — | Snapshot for later analysis |

**Foreign keys:** `sla_record_id` → CASCADE; `notification_id` → SET NULL.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`uq_escalation_rung`** | `(sla_record_id, rung)` | **Unique** | **Turns an at-least-once poller into an exactly-once effect.** A redelivered poll hits this constraint, the insert fails, the handler swallows it, and the team lead is not emailed twice. An application-level "have we already sent this?" check is a race; this is not. |

---

### Group 4 — AI Triage

#### TABLE: `prompt_version`

**Purpose:** Immutable, versioned prompts. Without this, measuring accuracy over time is meaningless.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `name` | VARCHAR(60) | NOT NULL | — | `triage`, `incident_title`, `draft`, `entailment` |
| `version` | INTEGER | NOT NULL, CHECK > 0 | — | |
| `template` | TEXT | NOT NULL | — | |
| `model_id` | VARCHAR(80) | NOT NULL | — | |
| `params` | JSONB | NOT NULL | `'{}'` | temperature, max tokens, seed |
| `output_schema` | JSONB | NULL | `NULL` | The JSON Schema the output is constrained to |
| `is_active` | BOOLEAN | NOT NULL | `FALSE` | |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | No `updated_at` — **rows are immutable**; a change means a new version |

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_prompt_name_version` | `(name, version)` | Unique | Immutability |
| `uq_prompt_active` | `(name) WHERE is_active` | Partial unique | Exactly one active version per prompt name |

---

#### TABLE: `ai_analysis`

**Purpose:** One triage run. Stores what the **model** produced — never what was decided.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `prompt_version_id` | BIGINT | NOT NULL, FK | — | |
| `model_id` | VARCHAR(80) | NOT NULL | — | The model actually used — may differ from the prompt's default after escalation or failover |
| `signals` | JSONB | NOT NULL | — | The `TriageSignals` object |
| `confidence` | NUMERIC(4,3) | NULL, CHECK 0–1 | `NULL` | Self-reported. Treated with suspicion; see the eval notes. |
| `raw_response` | JSONB | NULL | `NULL` | Full provider response for debugging |
| `tokens_in` / `tokens_out` | INTEGER | NOT NULL | `0` | |
| `cost_micros` | BIGINT | NOT NULL | `0` | **Integer micro-units. Never `FLOAT` for money.** |
| `latency_ms` | INTEGER | NOT NULL | `0` | |
| `attempt` | SMALLINT | NOT NULL | `1` | |
| `status` | VARCHAR(20) | NOT NULL, CHECK | `'OK'` | `OK`, `PARSE_FAILED`, `ESCALATED`, `FAILED`, `BUDGET_HELD` |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `ticket_id` → CASCADE; `tenant_id` → RESTRICT; `prompt_version_id` → **RESTRICT** (the prompt that produced an analysis must remain to explain it).

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_analysis_ticket_prompt` | `(ticket_id, prompt_version_id, attempt)` | Unique | **Idempotency** — a redelivered triage event cannot create a duplicate row |
| `idx_analysis_ticket` | `(ticket_id, created_at DESC)` | Composite | Latest analysis for the AI panel |
| `idx_analysis_usage` | `(tenant_id, created_at)` | Composite | Token and cost reporting |

---

#### TABLE: `priority_decision`

**Purpose:** What the **deterministic policy** decided, and from what. Separate from `ai_analysis` so model errors and policy errors are distinguishable.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `ai_analysis_id` | BIGINT | NULL, FK | `NULL` | NULL when triage was unavailable and a default policy applied |
| `policy_version` | VARCHAR(30) | NOT NULL | — | |
| `input_signals` | JSONB | NOT NULL | — | Snapshot of **every** input — signals, plan tier, incident link, reopen count |
| `computed_priority` | VARCHAR(12) | NOT NULL, CHECK | — | |
| `rationale` | JSONB | NOT NULL | — | The human-readable explanation shown in the UI |
| `decided_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `ticket_id` → CASCADE; `ai_analysis_id` → SET NULL; `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_priority_ticket` | `(ticket_id, decided_at DESC)` | Composite | The rationale endpoint |

---

#### TABLE: `priority_override`

**Purpose:** A human disagreeing with the policy. **Free labelled training data.**

| Field | Type | Constraints | Default |
|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — |
| `ticket_id` | BIGINT | NOT NULL, FK | — |
| `tenant_id` | BIGINT | NOT NULL, FK | — |
| `from_priority` / `to_priority` | VARCHAR(12) | NOT NULL, CHECK | — |
| `reason` | VARCHAR(500) | NOT NULL | — |
| `overridden_by` | BIGINT | NOT NULL, FK | — |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` |

**Foreign keys:** `ticket_id` → CASCADE; `overridden_by` → RESTRICT; `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_override_ticket` | `(ticket_id)` | B-tree | FK |
| `idx_override_analysis` | `(tenant_id, created_at DESC)` | Composite | Override-rate metric — a key quality signal |

---

### Group 5 — Knowledge

#### TABLE: `knowledge_document`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `source` | VARCHAR(20) | NOT NULL, CHECK | — | `ARTICLE`, `RUNBOOK`, `RESOLVED_TICKET` — **drives retrieval tier weighting**; a runbook is authoritative, a resolved ticket is precedent |
| `title` | VARCHAR(255) | NOT NULL | — | |
| `body` | TEXT | NOT NULL | — | |
| `uri` | VARCHAR(500) | NULL | `NULL` | |
| `content_sha256` | CHAR(64) | NOT NULL | — | Dedup and reindex-skip |
| `kb_version` | BIGINT | NOT NULL | `1` | **Bumped on any change; part of every retrieval cache key** — so a KB edit invalidates caches automatically rather than by explicit eviction |
| `source_ticket_id` | BIGINT | NULL, FK | `NULL` | Set when `source = RESOLVED_TICKET` |
| `indexed_at` | TIMESTAMPTZ | NULL | `NULL` | NULL = not yet embedded |
| `deleted_at` | TIMESTAMPTZ | NULL | `NULL` | Soft delete + deindex |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `tenant_id` → RESTRICT; `source_ticket_id` → SET NULL.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_kb_content` | `(tenant_id, content_sha256) WHERE deleted_at IS NULL` | Partial unique | Re-uploading the same document is a no-op |
| `idx_kb_tenant` | `(tenant_id, source) WHERE deleted_at IS NULL` | Partial composite | Admin list; tiered retrieval |
| `idx_kb_pending` | `(tenant_id) WHERE indexed_at IS NULL AND deleted_at IS NULL` | Partial | The indexing worker's queue |

---

#### TABLE: `knowledge_chunk`

**Purpose:** A retrievable span. Carries both halves of hybrid search.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `document_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | **Denormalised deliberately** — so the retrieval pre-filter is a single-table `WHERE`, not a join. Filtering *before* the vector scan rather than after is what keeps effective `k` correct. |
| `ordinal` | INTEGER | NOT NULL | — | Position in the document |
| `text` | TEXT | NOT NULL | — | |
| `text_tsv` | tsvector | NOT NULL | — | Lexical half |
| `embedding` | vector(768) | NOT NULL | — | Dense half |
| `token_count` | INTEGER | NOT NULL | — | Context-budget planning |
| `char_start` / `char_end` | INTEGER | NOT NULL | — | **Offsets into the parent document — required so a citation can point at an exact span** |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `document_id` → **CASCADE** (deleting a document must deindex it); `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_chunk_ordinal` | `(document_id, ordinal)` | Unique | Reindex idempotency |
| `idx_chunk_embedding` | `embedding` | **HNSW** `vector_cosine_ops` | Dense retrieval |
| `idx_chunk_tsv` | `text_tsv` | **GIN** | Lexical retrieval |
| `idx_chunk_tenant` | `(tenant_id)` | B-tree | The pre-filter that runs before both |

---

### Group 6 — Drafting

#### TABLE: `draft`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `prompt_version_id` | BIGINT | NOT NULL, FK | — | |
| `model_id` | VARCHAR(80) | NOT NULL | — | |
| `requested_by` | BIGINT | NOT NULL, FK | — | |
| `coverage` | NUMERIC(4,3) | NULL, CHECK 0–1 | `NULL` | supported claims ÷ total claims |
| `status` | VARCHAR(30) | NOT NULL, CHECK | `'PENDING'` | `PENDING`, `SHOWN`, `SUPPRESSED_LOW_COVERAGE`, `SUPPRESSED_NO_EVIDENCE`, `FAILED` |
| `tokens_in` / `tokens_out` | INTEGER | NOT NULL | `0` | |
| `cost_micros` | BIGINT | NOT NULL | `0` | |
| `latency_ms` | INTEGER | NOT NULL | `0` | |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `ticket_id` → CASCADE; `prompt_version_id` → RESTRICT; `requested_by` → RESTRICT; `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_draft_ticket` | `(ticket_id, created_at DESC)` | Composite | Latest draft for the panel |
| `idx_draft_quality` | `(tenant_id, status, created_at)` | Composite | Suppression-rate metric |

---

#### TABLE: `draft_claim`

**Purpose:** One independently-verifiable assertion. **This is why groundedness can be measured per claim rather than per response.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `draft_id` | BIGINT | NOT NULL, FK | — | |
| `ordinal` | INTEGER | NOT NULL | — | |
| `text` | TEXT | NOT NULL | — | |
| `verdict` | VARCHAR(30) | NOT NULL, CHECK | `'PENDING'` | `PENDING`, `SUPPORTED`, `PARTIAL`, `NOT_SUPPORTED`, `FAILED_NUMERIC_CHECK` |
| `verifier_model` | VARCHAR(80) | NULL | `NULL` | NULL when the free deterministic pre-filter rejected it — no LLM was spent |
| `kept` | BOOLEAN | NOT NULL | `FALSE` | Survived into the shown draft |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `draft_id` → CASCADE.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_claim_ordinal` | `(draft_id, ordinal)` | Unique | Stable ordering, retry idempotency |
| `idx_claim_verdict` | `(verdict)` | B-tree | Claim-level groundedness aggregation |

---

#### TABLE: `draft_claim_citation`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `draft_claim_id` | BIGINT | NOT NULL, FK | — | |
| `chunk_id` | BIGINT | NOT NULL, FK | — | |
| `char_start` / `char_end` | INTEGER | NOT NULL | — | The exact supporting span, so the UI can highlight it |

**Foreign keys:** `draft_claim_id` → CASCADE; `chunk_id` → **RESTRICT** (a cited chunk must not vanish while a draft references it; documents are soft-deleted, which makes this safe).

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_claim_citation` | `(draft_claim_id, chunk_id, char_start)` | Unique | No duplicate citations |
| `idx_citation_chunk` | `(chunk_id)` | B-tree | "Which drafts cited this article?" |

---

#### TABLE: `agent_draft_action`

**Purpose:** What the agent actually did. **The single most valuable quality metric in the system.**

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `draft_id` | BIGINT | NOT NULL, UNIQUE, FK | — | One terminal action per draft |
| `agent_id` | BIGINT | NOT NULL, FK | — | |
| `action` | VARCHAR(20) | NOT NULL, CHECK | — | `SENT_AS_IS`, `EDITED`, `DISCARDED` |
| `edit_distance` | INTEGER | NULL, CHECK >= 0 | `NULL` | Levenshtein between draft and sent text. NULL for `SENT_AS_IS` and `DISCARDED`. |
| `final_text` | TEXT | NULL | `NULL` | |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `draft_id` → CASCADE; `agent_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_draft_action` | `draft_id` | Unique | One action per draft |
| `idx_draft_action_metric` | `(action, created_at)` | Composite | `SENT_AS_IS` rate over time — measured from behaviour, not asserted by a model |

---

### Group 7 — Incidents

#### TABLE: `incident`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `reference` | VARCHAR(24) | NOT NULL | — | e.g. `INC-204` |
| `title` | VARCHAR(200) | NOT NULL | — | LLM-generated, or a template if the LLM was unavailable |
| `summary` | TEXT | NULL | `NULL` | |
| `generated_by_model` | VARCHAR(80) | NULL | `NULL` | **NULL means the title was templated** — honest provenance |
| `prompt_version_id` | BIGINT | NULL, FK | `NULL` | |
| `status` | VARCHAR(20) | NOT NULL, CHECK | `'PROPOSED'` | `PROPOSED`, `CONFIRMED`, `REJECTED`, `MITIGATED`, `RESOLVED` |
| `detection_method` | VARCHAR(20) | NOT NULL, CHECK | `'CLUSTER'` | `CLUSTER` / `MANUAL` |
| `cluster_size_at_detection` | INTEGER | NOT NULL | `0` | |
| `arrival_rate_multiple` | NUMERIC(6,2) | NULL | `NULL` | How far above baseline — **the deterministic gate's evidence, stored** |
| `first_ticket_at` | TIMESTAMPTZ | NOT NULL | — | |
| `detected_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | `detected_at − first_ticket_at` = **time-to-detect, the headline metric** |
| `confirmed_by` / `confirmed_at` | BIGINT / TIMESTAMPTZ | NULL, FK | `NULL` | |
| `rejected_reason` | VARCHAR(500) | NULL | `NULL` | |
| `resolved_at` | TIMESTAMPTZ | NULL | `NULL` | |
| `version` | INTEGER | NOT NULL | `0` | `@Version` |
| `created_at` / `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `tenant_id` → RESTRICT; `prompt_version_id` → RESTRICT; `confirmed_by` → SET NULL.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_incident_reference` | `(tenant_id, reference)` | Unique | Public lookup |
| `idx_incident_board` | `(tenant_id, status, detected_at DESC)` | Composite | The incident board |
| `idx_incident_open` | `(tenant_id) WHERE status IN ('PROPOSED','CONFIRMED','MITIGATED')` | Partial | The correlation sweep checks for an existing live incident before proposing a new one |

---

#### TABLE: `incident_ticket`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `incident_id` | BIGINT | NOT NULL, FK | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `link_confidence` | NUMERIC(4,3) | NULL | `NULL` | Cosine similarity at link time |
| `linked_by` | BIGINT | NULL, FK | `NULL` | NULL = automatic |
| `linked_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |
| `detached_at` | TIMESTAMPTZ | NULL | `NULL` | **Soft detach** — the history of a wrong link is worth keeping |
| `detached_by` | BIGINT | NULL, FK | `NULL` | |

**Foreign keys:** `incident_id` → CASCADE; `ticket_id` → CASCADE; `linked_by`/`detached_by` → SET NULL.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`uq_incident_ticket_live`** | `(ticket_id) WHERE detached_at IS NULL` | **Partial unique** | **A ticket belongs to at most one live incident** — enforced by the database, not by hoping |
| `idx_incident_tickets` | `(incident_id) WHERE detached_at IS NULL` | Partial | Listing an incident's tickets |

---

#### TABLE: `incident_update`

| Field | Type | Constraints | Default |
|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — |
| `incident_id` | BIGINT | NOT NULL, FK | — |
| `author_id` | BIGINT | NOT NULL, FK | — |
| `body` | TEXT | NOT NULL | — |
| `visibility` | VARCHAR(10) | NOT NULL, CHECK | `'INTERNAL'` |
| `published_at` | TIMESTAMPTZ | NOT NULL | `NOW()` |

**Foreign keys:** `incident_id` → CASCADE; `author_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_update_incident` | `(incident_id, published_at)` | Composite | Update timeline |

---

#### TABLE: `incident_update_delivery`

**Purpose:** Per-ticket fan-out state. Without this, one failed delivery means re-sending to everyone.

| Field | Type | Constraints | Default |
|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — |
| `incident_update_id` | BIGINT | NOT NULL, FK | — |
| `ticket_id` | BIGINT | NOT NULL, FK | — |
| `status` | VARCHAR(12) | NOT NULL, CHECK | `'PENDING'` |
| `attempts` | SMALLINT | NOT NULL | `0` |
| `last_error` | VARCHAR(500) | NULL | `NULL` |
| `delivered_at` | TIMESTAMPTZ | NULL | `NULL` |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` |

**Foreign keys:** both → CASCADE.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`uq_delivery`** | `(incident_update_id, ticket_id)` | **Unique** | **Fan-out idempotency** — a redelivered event no-ops instead of double-notifying a customer |
| `idx_delivery_pending` | `(status, created_at) WHERE status = 'PENDING'` | Partial | The fan-out worker's queue |

---

### Group 8 — Platform

#### TABLE: `outbox_event`

**Purpose:** The transactional outbox **and** the job queue. Written in the same transaction as the domain change.

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NULL, FK | `NULL` | NULL for system-wide events |
| `aggregate_type` | VARCHAR(40) | NOT NULL | — | `TICKET`, `INCIDENT_UPDATE`, `KNOWLEDGE_DOCUMENT` |
| `aggregate_id` | BIGINT | NOT NULL | — | |
| `event_type` | VARCHAR(50) | NOT NULL | — | `TICKET_CREATED`, `DRAFT_REQUESTED`, `INCIDENT_UPDATE_PUBLISHED`, … |
| `payload` | JSONB | NOT NULL | — | |
| `status` | VARCHAR(12) | NOT NULL, CHECK | `'PENDING'` | `PENDING`, `IN_FLIGHT`, `DONE`, `DEAD` |
| `attempts` | SMALLINT | NOT NULL | `0` | |
| `next_attempt_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | Backoff target |
| `locked_until` | TIMESTAMPTZ | NULL | `NULL` | **Visibility timeout** — the reaper resets rows past this |
| `last_error` | VARCHAR(1000) | NULL | `NULL` | |
| `created_at` / `processed_at` | TIMESTAMPTZ | | `NOW()` / NULL | |

**Foreign keys:** `tenant_id` → SET NULL. **Deliberately no FK to the aggregate** — a generic queue must not carry a foreign key per aggregate type, and the payload is self-contained.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| **`idx_outbox_claim`** | `(event_type, next_attempt_at) WHERE status = 'PENDING'` | **Partial composite** | The worker claim query, run once per second per worker type. The partial clause keeps the index tiny even as `DONE` rows accumulate — which is what makes the highest-frequency query in the system cheap. |
| `idx_outbox_reaper` | `(locked_until) WHERE status = 'IN_FLIGHT'` | Partial | Resetting events whose worker died |
| `idx_outbox_prune` | `(status, created_at) WHERE status = 'DONE'` | Partial | The nightly prune job |
| `idx_outbox_dlq` | `(tenant_id, status) WHERE status = 'DEAD'` | Partial | Admin DLQ view |

---

#### TABLE: `idempotency_record`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `idem_key` | VARCHAR(128) | NOT NULL | — | Client-supplied |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `endpoint` | VARCHAR(160) | NOT NULL | — | |
| `request_hash` | CHAR(64) | NOT NULL | — | **Same key + different body = `422`**, not a silent replay of the wrong response |
| `response_status` | INTEGER | NOT NULL | — | |
| `response_body` | JSONB | NOT NULL | — | |
| `created_at` / `expires_at` | TIMESTAMPTZ | NOT NULL | `NOW()` / +24h | |

**Foreign keys:** `tenant_id` → CASCADE.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_idem` | `(tenant_id, idem_key, endpoint)` | Unique | The lookup, and the guarantee. Redis is the fast path; **this table is the durable backstop** if Redis is cold. |
| `idx_idem_expiry` | `(expires_at)` | B-tree | Cleanup |

---

#### TABLE: `tenant_ai_policy`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `tenant_id` | BIGINT | **PRIMARY KEY**, FK | — | One row per tenant — the tenant ID *is* the PK |
| `external_model_allowed` | BOOLEAN | NOT NULL | `TRUE` | `FALSE` → local model only, or AI disabled |
| `allowed_providers` | TEXT[] | NOT NULL | `'{}'` | Empty = any configured provider |
| `pii_redaction_required` | BOOLEAN | NOT NULL | `TRUE` | |
| `monthly_budget_micros` | BIGINT | NOT NULL, CHECK >= 0 | `5000000` | **Hard cap.** Exceeded → `BUDGET_HELD`, not silent overspend. |
| `current_month_spend_micros` | BIGINT | NOT NULL | `0` | Reset monthly |
| `retention_days` | INTEGER | NOT NULL, CHECK > 0 | `365` | |
| `updated_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** `tenant_id` → CASCADE.

**No extra index** — the PK is the only access path.

---

#### TABLE: `pii_redaction_map`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `tenant_id` | BIGINT | NOT NULL, FK | — | |
| `ticket_id` | BIGINT | NOT NULL, FK | — | |
| `placeholder` | VARCHAR(40) | NOT NULL | — | `«PERSON_1»`, `«ORDER_1»` |
| `pii_type` | VARCHAR(20) | NOT NULL, CHECK | — | `PERSON`, `EMAIL`, `PHONE`, `CARD`, `GOV_ID`, `IP`, `ORDER_REF` |
| `encrypted_value` | BYTEA | NOT NULL | — | AES-GCM. **Never transmitted, never logged.** |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

**Foreign keys:** both → **CASCADE** — a data-erasure request on a ticket must take the PII map with it. This is the one place cascade is a compliance requirement rather than a convenience.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_pii_placeholder` | `(ticket_id, placeholder)` | Unique | Rehydration lookup |

---

#### TABLE: `notification`

| Field | Type | Constraints | Default |
|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — |
| `tenant_id` | BIGINT | NOT NULL, FK | — |
| `recipient_id` | BIGINT | NOT NULL, FK | — |
| `kind` | VARCHAR(40) | NOT NULL, CHECK | — |
| `title` | VARCHAR(200) | NOT NULL | — |
| `body` | VARCHAR(1000) | NULL | `NULL` |
| `link_url` | VARCHAR(300) | NULL | `NULL` |
| `read_at` | TIMESTAMPTZ | NULL | `NULL` |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` |

**Foreign keys:** `recipient_id` → CASCADE; `tenant_id` → RESTRICT.

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `idx_notification_unread` | `(recipient_id, created_at DESC) WHERE read_at IS NULL` | **Partial composite** | The unread badge is polled constantly; a partial index keeps it small because most notifications are read |

---

### Group 9 — Evaluation

#### TABLE: `eval_case`

| Field | Type | Constraints | Default | Notes |
|---|---|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY | — | |
| `suite` | VARCHAR(40) | NOT NULL, CHECK | — | `CLASSIFICATION`, `RETRIEVAL`, `GROUNDING`, `REFUSAL`, `INCIDENT` |
| `name` | VARCHAR(120) | NOT NULL | — | |
| `input_fixture` | JSONB | NOT NULL | — | The ticket or query under test |
| `expected` | JSONB | NOT NULL | — | Ground truth |
| `source` | VARCHAR(20) | NOT NULL, CHECK | `'HAND_LABELLED'` | `HAND_LABELLED`, `AGENT_OVERRIDE`, `INCIDENT_CONFIRM` — **live labels flow in from real use** |
| `is_active` | BOOLEAN | NOT NULL | `TRUE` | |
| `created_at` | TIMESTAMPTZ | NOT NULL | `NOW()` | |

| Index | Field(s) | Type | Reason |
|---|---|---|---|
| `uq_eval_case` | `(suite, name)` | Unique | |
| `idx_eval_active` | `(suite) WHERE is_active` | Partial | The CI run loads one suite at a time |

---

#### TABLE: `eval_run` / `eval_result`

| `eval_run` | Type | Constraints |
|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY |
| `suite` | VARCHAR(40) | NOT NULL |
| `prompt_version_id` | BIGINT | NULL, FK → RESTRICT |
| `model_id` | VARCHAR(80) | NOT NULL |
| `git_sha` | CHAR(40) | NULL |
| `metrics` | JSONB | NOT NULL — accuracy, Recall@5, MRR, groundedness |
| `passed` | BOOLEAN | NOT NULL — **the CI gate reads this one column** |
| `started_at` / `finished_at` | TIMESTAMPTZ | |

| `eval_result` | Type | Constraints |
|---|---|---|
| `id` | BIGSERIAL | PRIMARY KEY |
| `eval_run_id` | BIGINT | NOT NULL, FK → CASCADE |
| `eval_case_id` | BIGINT | NOT NULL, FK → RESTRICT |
| `metric` | VARCHAR(40) | NOT NULL |
| `expected` / `actual` | JSONB | NOT NULL |
| `passed` | BOOLEAN | NOT NULL |

| Index | Field(s) | Reason |
|---|---|---|
| `idx_eval_run_suite` | `(suite, started_at DESC)` | Accuracy trend over prompt versions |
| `idx_eval_result_run` | `(eval_run_id, passed)` | Failure listing for a run |

---

## STEP 4 — Entity Relationship Overview

```
tenant
 ├── app_user ──────── agent_profile (1:1)
 │     └── refresh_token (1:many)
 ├── team ─────────── app_user.team_id (1:many)
 ├── business_calendar ── business_holiday (1:many)
 ├── sla_policy
 ├── tenant_ai_policy (1:1)
 └── ticket
       ├── ticket_message ──── attachment (1:many)
       │       └── from_draft_id ──→ draft
       ├── ticket_event (1:many, append-only)
       ├── sla_record (1:2 — FIRST_RESPONSE + RESOLUTION)
       │     ├── sla_clock_segment (1:many, append-only)
       │     └── sla_escalation (1:0–4, one per rung)
       ├── ai_analysis (1:many — one per attempt)
       ├── priority_decision (1:many — recomputed on relevant change)
       ├── priority_override (1:many)
       ├── draft (1:many)
       │     └── draft_claim (1:many)
       │           ├── draft_claim_citation ──→ knowledge_chunk
       │           └── (parent) agent_draft_action (1:1 with draft)
       └── incident_ticket ──→ incident        (many:1, ≤1 live)

incident
 ├── incident_ticket (1:many)  ──→ ticket
 └── incident_update (1:many)
       └── incident_update_delivery (1:many) ──→ ticket

knowledge_document
 └── knowledge_chunk (1:many)
       └── ←── draft_claim_citation

prompt_version  ←── ai_analysis, draft, incident, eval_run   (RESTRICT — immutable provenance)

eval_case ──→ eval_result ←── eval_run

outbox_event, idempotency_record, notification, pii_redaction_map  — platform, loosely coupled
```

### Cardinality notes worth stating

- **`ticket` : `sla_record` is 1:2, not 1:many.** Exactly one first-response clock and one resolution clock, enforced by a partial unique index that excludes `CANCELLED`.
- **`ticket` : `incident` is many:1, but constrained to *at most one live link*** by `uq_incident_ticket_live`. A ticket can have many *historical* links after detach/re-link.
- **`draft` : `agent_draft_action` is 1:0-or-1.** A draft is acted on exactly once, or never.
- **`sla_record` : `sla_clock_segment` is 1:many with exactly one open segment**, enforced by partial unique index.

---

## STEP 5 — Cross-Cutting Concerns

### Normalization

**The schema is in 3NF**, with four deliberate, justified denormalizations.

| Denormalization | Why |
|---|---|
| `tenant_id` on almost every table | Reachable via parent joins, but duplicated so the Hibernate tenant filter applies uniformly to every entity. **Without it, a developer who writes a repository method that forgets a join has created a cross-tenant leak.** The redundancy buys a structural safety property. |
| `sla_record.policy_version` and `target_minutes` | Snapshotted from `sla_policy`. The policy row may be superseded; the record must remain explicable years later. **This is temporal correctness, not redundancy.** |
| `priority_decision.input_signals` | Duplicates data from `ai_analysis`. Deliberate: the decision must be reproducible even if the analysis row is later pruned under a retention policy. |
| `knowledge_chunk.tenant_id` | Reachable via `document_id`. Duplicated so the retrieval pre-filter is single-table — **filtering before the vector scan rather than after is what keeps effective `k` correct.** |

**Potential violation examined and rejected:** storing `open_count` on `agent_profile` is a derived aggregate (it equals `COUNT(*)` of that agent's open tickets) and therefore technically a 3NF violation. It is kept because the routing query must read it under a row lock on a single row — recomputing a `COUNT(*)` inside the lock would hold the lock across a scan of the ticket table, which is precisely how you turn a fast lock into a contention disaster. **A nightly reconciliation job corrects any drift**, and the correctness of assignment does not depend on the counter being exact, only on the lock being taken.

### Soft delete strategy

| Table | Strategy | Reasoning |
|---|---|---|
| `app_user` | **Soft** (`deleted_at`) | Their tickets, messages and audit entries must survive. FKs are `RESTRICT`, which only works because the row stays. |
| `team` | **Soft** | Historical tickets reference it |
| `knowledge_document` | **Soft** | Drafts cite its chunks; a hard delete would break citations |
| `incident_ticket` | **Soft** (`detached_at`) | A wrong link is itself useful history, and it feeds the eval set |
| `ticket` | **Hard**, and only on a genuine data-erasure request | Cascades intentionally take messages, events, SLA records and the PII map with it. This is the compliance path, not routine operation. |
| `ticket_message`, `ticket_event`, `sla_clock_segment`, `sla_escalation`, `ai_analysis`, `priority_decision`, `draft*` | **Neither** — append-only, never deleted individually | They are the audit and evaluation record |
| `outbox_event`, `idempotency_record`, `refresh_token`, `notification` | **Hard delete via scheduled pruning** | Operational data with a defined lifetime |

**The rule every query must follow:** any table with `deleted_at` is always queried with `WHERE deleted_at IS NULL`. Enforced by a Hibernate `@Where` clause on the entity plus `@SQLDelete` to convert `delete()` into an update — so it is not left to discipline.

### Timestamp conventions

```sql
created_at  TIMESTAMPTZ  NOT NULL  DEFAULT NOW()
updated_at  TIMESTAMPTZ  NOT NULL  DEFAULT NOW()
```

**Always `TIMESTAMPTZ`, never `TIMESTAMP`.** This system computes business hours across timezones and DST transitions; a naive timestamp column would make correctness impossible.

**`updated_at` maintenance:** Hibernate `@UpdateTimestamp` for entities written through JPA. **Plus a database trigger** (see Step 6) on the tables also written by native queries — `ticket`, `sla_record`, `agent_profile`, `outbox_event` — because a native `UPDATE` bypasses the ORM lifecycle and would silently leave `updated_at` stale. Belt and braces, because the mismatch is invisible until it matters.

**Append-only tables have `created_at`/`occurred_at` only** — the absence of `updated_at` is itself documentation that the row is immutable.

**All time comes from PostgreSQL's `NOW()`, never from application clocks.** With multiple instances polling the same deadlines, application clock skew would make "is this deadline due?" answerable differently on different instances. One source of time.

### Enum columns

**Decision: `VARCHAR` + a named `CHECK` constraint, mapped to a Java enum with `@Enumerated(EnumType.STRING)`.**

```sql
status VARCHAR(24) NOT NULL DEFAULT 'OPEN'
  CONSTRAINT ck_ticket_status CHECK (status IN (
    'OPEN','TRIAGED','ASSIGNED','IN_PROGRESS','WAITING_ON_CUSTOMER',
    'PENDING_THIRD_PARTY','RESOLVED','CLOSED'))
```

| Option | Verdict |
|---|---|
| **`VARCHAR` + named `CHECK`** ✅ | Readable in `psql` without a join. Adding a value is a one-line migration. The constraint is *named*, so a violation produces `ck_ticket_status` in the error rather than an anonymous `check_constraint_4`. |
| Native PostgreSQL `ENUM` type | Rejected: `ALTER TYPE … ADD VALUE` cannot run inside a transaction block in older versions, which fights Flyway, and removing a value requires recreating the type. |
| Lookup table | Rejected: a join on every query to resolve four fixed values that are also compiled into the Java enum. |
| `@Enumerated(EnumType.ORDINAL)` | **Rejected emphatically.** Storing `0,1,2` means reordering the Java enum silently corrupts every existing row. Always `STRING`. |

### UUID vs BIGSERIAL for primary keys

**Decision: `BIGSERIAL` for every table, with a separate human-readable `reference` column on `ticket` and `incident`.**

Reasoning specific to this project:

1. **Nothing merges from another system.** Single database, single writer. The distributed-ID-generation problem that motivates UUIDs does not exist here.
2. **Index locality matters for the hot path.** `BIGSERIAL` values are monotonic, so B-tree inserts append to the rightmost leaf. Random UUIDv4 values scatter inserts across the whole index, causing page splits and write amplification. On `outbox_event` — which takes roughly 9,000 inserts a day and is polled six times a second — that is a real cost for zero benefit.
3. **8 bytes versus 16.** Multiplied across every FK column and every index entry in a 37-table schema, this is meaningful.
4. **The security argument for UUIDs is satisfied differently, and better.** Sequential IDs are guessable — which is exactly why `ticket.id` is never in a URL. The public identifier is `reference` (`TKT-10428`), and every read additionally enforces tenant scope and ownership. **Unguessable IDs are defence in depth, never the primary access control** — a system whose security depends on ID entropy has already failed.

**One exception:** `refresh_token.family_id` is a `UUID`, because it is generated per login, never used as a lookup key in a hot path, and benefits from being unguessable.

**If this were a distributed system** writing from multiple regions, UUIDv7 (time-ordered, so it keeps index locality while remaining globally unique) would be the right answer. It is not, so it isn't.

---

## STEP 6 — SQL CREATE TABLE Statements

Ready to paste into Flyway migrations. Suggested split: `V1__extensions_and_tenancy.sql`, `V2__ticketing.sql`, `V3__sla.sql`, `V4__ai_and_knowledge.sql`, `V5__incidents.sql`, `V6__platform_and_eval.sql`, `V7__triggers.sql`.

### V1 — Extensions and tenancy

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE tenant (
    id          BIGSERIAL     PRIMARY KEY,
    name        VARCHAR(150)  NOT NULL,
    slug        VARCHAR(60)   NOT NULL,
    plan_tier   VARCHAR(20)   NOT NULL DEFAULT 'FREE'
                CONSTRAINT ck_tenant_plan CHECK (plan_tier IN ('FREE','PRO','ENTERPRISE')),
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_slug UNIQUE (slug)
);

CREATE TABLE team (
    id          BIGSERIAL     PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL,
    name        VARCHAR(80)   NOT NULL,
    skills      TEXT[]        NOT NULL DEFAULT '{}',   -- real array, never a CSV string
    is_default  BOOLEAN       NOT NULL DEFAULT FALSE,
    deleted_at  TIMESTAMPTZ   NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_team_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX uq_team_tenant_name ON team(tenant_id, name) WHERE deleted_at IS NULL;
CREATE INDEX idx_team_skills  ON team USING GIN (skills);   -- skills && ARRAY['PAYMENT']
CREATE INDEX idx_team_default ON team(tenant_id) WHERE is_default;

CREATE TABLE app_user (
    id             BIGSERIAL    PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(72)  NOT NULL,   -- BCrypt cost 12; never plaintext
    full_name      VARCHAR(120) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                   CONSTRAINT ck_user_role CHECK (role IN ('CUSTOMER','AGENT','TEAM_LEAD','ADMIN')),
    team_id        BIGINT       NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at  TIMESTAMPTZ  NULL,
    deleted_at     TIMESTAMPTZ  NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_team   FOREIGN KEY (team_id)
        REFERENCES team(id)   ON DELETE SET NULL
);
-- Partial unique: email reusable after soft delete
CREATE UNIQUE INDEX uq_user_tenant_email ON app_user(tenant_id, email) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_tenant_role ON app_user(tenant_id, role);
CREATE INDEX idx_user_team        ON app_user(team_id);

CREATE TABLE agent_profile (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    max_concurrent  INTEGER     NOT NULL DEFAULT 15
                    CONSTRAINT ck_agent_max CHECK (max_concurrent > 0),
    open_count      INTEGER     NOT NULL DEFAULT 0     -- the contended column
                    CONSTRAINT ck_agent_open CHECK (open_count >= 0),
    is_available    BOOLEAN     NOT NULL DEFAULT TRUE,
    shift_start     TIME        NULL,
    shift_end       TIME        NULL,
    version         INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_agent_profile_user UNIQUE (user_id),
    CONSTRAINT fk_agent_user   FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT
);
-- Serves: WHERE tenant_id=? AND is_available ORDER BY open_count
--         LIMIT 1 FOR UPDATE SKIP LOCKED
CREATE INDEX idx_agent_routing ON agent_profile(tenant_id, is_available, open_count);

CREATE TABLE refresh_token (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,   -- SHA-256; raw token never stored
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ NULL,       -- non-null + presented again = reuse attack
    revoked_at  TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_refresh_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_refresh_family ON refresh_token(family_id);
CREATE INDEX idx_refresh_expiry ON refresh_token(expires_at);
```

### V2 — Ticketing

```sql
CREATE TABLE ticket (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL,
    reference           VARCHAR(24)  NOT NULL,   -- TKT-10428; id never exposed
    subject             VARCHAR(200) NOT NULL
                        CONSTRAINT ck_ticket_subject CHECK (char_length(subject) BETWEEN 1 AND 200),
    body                TEXT         NOT NULL
                        CONSTRAINT ck_ticket_body CHECK (char_length(body) BETWEEN 1 AND 20000),
                        -- the cap is a token-cost DoS control, not a style rule
    status              VARCHAR(24)  NOT NULL DEFAULT 'OPEN'
                        CONSTRAINT ck_ticket_status CHECK (status IN
                          ('OPEN','TRIAGED','ASSIGNED','IN_PROGRESS','WAITING_ON_CUSTOMER',
                           'PENDING_THIRD_PARTY','RESOLVED','CLOSED')),
    priority            VARCHAR(12)  NOT NULL DEFAULT 'UNTRIAGED'
                        CONSTRAINT ck_ticket_priority CHECK (priority IN
                          ('UNTRIAGED','P1','P2','P3','P4')),
    category            VARCHAR(40)  NULL,
    requester_id        BIGINT       NOT NULL,
    assignee_id         BIGINT       NULL,
    team_id             BIGINT       NULL,
    reopen_count        INTEGER      NOT NULL DEFAULT 0
                        CONSTRAINT ck_ticket_reopen CHECK (reopen_count >= 0),
    first_responded_at  TIMESTAMPTZ  NULL,   -- written in the SAME txn as the first reply
    resolved_at         TIMESTAMPTZ  NULL,
    closed_at           TIMESTAMPTZ  NULL,
    embedding           vector(768)  NULL,   -- incident clustering
    search_tsv          tsvector     NULL,
    version             INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ticket_reference UNIQUE (tenant_id, reference),
    CONSTRAINT fk_ticket_tenant    FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_requester FOREIGN KEY (requester_id)
        REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ticket_assignee  FOREIGN KEY (assignee_id)
        REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_ticket_team      FOREIGN KEY (team_id)
        REFERENCES team(id)     ON DELETE SET NULL
);
-- THE highest-traffic query in the application: the agent queue
CREATE INDEX idx_ticket_queue     ON ticket(tenant_id, status, priority, created_at DESC);
CREATE INDEX idx_ticket_assignee  ON ticket(tenant_id, assignee_id, status);
CREATE INDEX idx_ticket_team      ON ticket(tenant_id, team_id, status);
CREATE INDEX idx_ticket_requester ON ticket(tenant_id, requester_id, created_at DESC);
CREATE INDEX idx_ticket_search    ON ticket USING GIN (search_tsv);
CREATE INDEX idx_ticket_embedding ON ticket USING hnsw (embedding vector_cosine_ops);
-- correlation sweep touches only recent OPEN tickets
CREATE INDEX idx_ticket_recent    ON ticket(tenant_id, created_at)
    WHERE status NOT IN ('RESOLVED','CLOSED');

CREATE TABLE draft (   -- created early: ticket_message.from_draft_id references it
    id                 BIGSERIAL    PRIMARY KEY,
    ticket_id          BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL,
    prompt_version_id  BIGINT       NOT NULL,
    model_id           VARCHAR(80)  NOT NULL,
    requested_by       BIGINT       NOT NULL,
    coverage           NUMERIC(4,3) NULL
                       CONSTRAINT ck_draft_coverage CHECK (coverage BETWEEN 0 AND 1),
    status             VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
                       CONSTRAINT ck_draft_status CHECK (status IN
                         ('PENDING','SHOWN','SUPPRESSED_LOW_COVERAGE',
                          'SUPPRESSED_NO_EVIDENCE','FAILED')),
    tokens_in          INTEGER      NOT NULL DEFAULT 0,
    tokens_out         INTEGER      NOT NULL DEFAULT 0,
    cost_micros        BIGINT       NOT NULL DEFAULT 0,   -- integer micro-units, never FLOAT
    latency_ms         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_draft_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_draft_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_draft_user   FOREIGN KEY (requested_by)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_draft_ticket  ON draft(ticket_id, created_at DESC);
CREATE INDEX idx_draft_quality ON draft(tenant_id, status, created_at);

CREATE TABLE ticket_message (
    id                BIGSERIAL   PRIMARY KEY,
    ticket_id         BIGINT      NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    author_id         BIGINT      NOT NULL,
    body              TEXT        NOT NULL
                      CONSTRAINT ck_message_body CHECK (char_length(body) BETWEEN 1 AND 20000),
    visibility        VARCHAR(10) NOT NULL DEFAULT 'PUBLIC'
                      CONSTRAINT ck_message_visibility CHECK (visibility IN ('PUBLIC','INTERNAL')),
                      -- a bug here is a customer-visible leak: constrained in the DB
    is_first_response BOOLEAN     NOT NULL DEFAULT FALSE,
    from_draft_id     BIGINT      NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- no updated_at: messages are immutable
    CONSTRAINT fk_message_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_message_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_message_author FOREIGN KEY (author_id)
        REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_message_draft  FOREIGN KEY (from_draft_id)
        REFERENCES draft(id)    ON DELETE SET NULL
);
CREATE INDEX idx_message_thread ON ticket_message(ticket_id, created_at);
CREATE INDEX idx_message_author ON ticket_message(author_id);
CREATE UNIQUE INDEX idx_message_first_response
    ON ticket_message(ticket_id) WHERE is_first_response;

CREATE TABLE ticket_event (          -- APPEND-ONLY (trigger-enforced, V7)
    id          BIGSERIAL   PRIMARY KEY,
    ticket_id   BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    actor_id    BIGINT      NULL,    -- NULL = the system did it
    event_type  VARCHAR(40) NOT NULL
                CONSTRAINT ck_event_type CHECK (event_type IN
                  ('CREATED','TRIAGED','ASSIGNED','STATUS_CHANGED','PRIORITY_CHANGED',
                   'MESSAGE_ADDED','SLA_STARTED','SLA_PAUSED','SLA_RESUMED','SLA_ESCALATED',
                   'SLA_BREACHED','SLA_MET','INCIDENT_LINKED','INCIDENT_DETACHED',
                   'RESOLVED','REOPENED','CLOSED')),
    from_value  VARCHAR(80) NULL,
    to_value    VARCHAR(80) NULL,
    payload     JSONB       NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_event_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_event_actor  FOREIGN KEY (actor_id)
        REFERENCES app_user(id) ON DELETE SET NULL
);
CREATE INDEX idx_event_ticket ON ticket_event(ticket_id, occurred_at);
CREATE INDEX idx_event_audit  ON ticket_event(tenant_id, event_type, occurred_at DESC);

CREATE TABLE attachment (
    id                BIGSERIAL    PRIMARY KEY,
    message_id        BIGINT       NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    storage_key       VARCHAR(255) NOT NULL,   -- generated UUID path, NOT user filename
    original_filename VARCHAR(255) NOT NULL,   -- display only
    mime_type         VARCHAR(100) NOT NULL,   -- verified against magic bytes
    byte_size         BIGINT       NOT NULL
                      CONSTRAINT ck_attachment_size CHECK (byte_size > 0 AND byte_size <= 10485760),
    content_sha256    CHAR(64)     NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attachment_key UNIQUE (storage_key),
    CONSTRAINT fk_attachment_message FOREIGN KEY (message_id)
        REFERENCES ticket_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_attachment_tenant  FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT
);
CREATE INDEX idx_attachment_message ON attachment(message_id);
```

### V3 — SLA

```sql
CREATE TABLE business_calendar (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    timezone    VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',  -- IANA name, never a UTC offset
    working_days SMALLINT[] NOT NULL DEFAULT '{1,2,3,4,5}',   -- ISO-8601, Monday = 1
    day_start   TIME        NOT NULL DEFAULT '09:00',
    day_end     TIME        NOT NULL DEFAULT '18:00',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_calendar_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_calendar_hours  CHECK (day_end > day_start),
    CONSTRAINT fk_calendar_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);

CREATE TABLE business_holiday (
    id           BIGSERIAL   PRIMARY KEY,
    calendar_id  BIGINT      NOT NULL,
    holiday_date DATE        NOT NULL,   -- a calendar day, not an instant
    name         VARCHAR(80) NOT NULL,
    CONSTRAINT uq_holiday UNIQUE (calendar_id, holiday_date),
    CONSTRAINT fk_holiday_calendar FOREIGN KEY (calendar_id)
        REFERENCES business_calendar(id) ON DELETE CASCADE
);

CREATE TABLE sla_policy (
    id                     BIGSERIAL   PRIMARY KEY,
    tenant_id              BIGINT      NOT NULL,
    priority               VARCHAR(12) NOT NULL
                           CONSTRAINT ck_slapolicy_priority CHECK (priority IN ('P1','P2','P3','P4')),
    plan_tier              VARCHAR(20) NOT NULL
                           CONSTRAINT ck_slapolicy_plan CHECK (plan_tier IN ('FREE','PRO','ENTERPRISE')),
    first_response_minutes INTEGER     NOT NULL
                           CONSTRAINT ck_slapolicy_fr CHECK (first_response_minutes > 0),
    resolution_minutes     INTEGER     NOT NULL
                           CONSTRAINT ck_slapolicy_res CHECK (resolution_minutes > 0),
    escalation_rungs       SMALLINT[]  NOT NULL DEFAULT '{50,75,90,100}',
    effective_from         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_to           TIMESTAMPTZ NULL,           -- NULL = currently in force
    version_label          VARCHAR(30) NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_slapolicy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT
);
-- exactly one live policy per (tenant, priority, plan) — overlaps are impossible
CREATE UNIQUE INDEX uq_sla_policy_live
    ON sla_policy(tenant_id, priority, plan_tier) WHERE effective_to IS NULL;
CREATE INDEX idx_sla_policy_lookup
    ON sla_policy(tenant_id, priority, plan_tier, effective_from DESC);

CREATE TABLE sla_record (
    id               BIGSERIAL   PRIMARY KEY,
    ticket_id        BIGINT      NOT NULL,
    tenant_id        BIGINT      NOT NULL,
    sla_policy_id    BIGINT      NOT NULL,
    policy_version   VARCHAR(30) NOT NULL,   -- snapshot: the policy row may be superseded
    kind             VARCHAR(20) NOT NULL
                     CONSTRAINT ck_sla_kind CHECK (kind IN ('FIRST_RESPONSE','RESOLUTION')),
    target_minutes   INTEGER     NOT NULL
                     CONSTRAINT ck_sla_target CHECK (target_minutes > 0),
    state            VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                     CONSTRAINT ck_sla_state CHECK (state IN
                       ('RUNNING','PAUSED','MET','BREACHED','CANCELLED')),
    next_deadline_at TIMESTAMPTZ NULL,       -- ABSOLUTE instant; NULL while PAUSED
    next_rung        SMALLINT    NULL DEFAULT 50,
    met_at           TIMESTAMPTZ NULL,
    breached_at      TIMESTAMPTZ NULL,
    version          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_sla_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)     ON DELETE CASCADE,
    CONSTRAINT fk_sla_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)     ON DELETE RESTRICT,
    CONSTRAINT fk_sla_policy FOREIGN KEY (sla_policy_id)
        REFERENCES sla_policy(id) ON DELETE RESTRICT
);
-- THE most important index in the database.
-- Poller cost is proportional to DUE records, not OPEN ones.
CREATE INDEX idx_sla_poller ON sla_record(state, next_deadline_at) WHERE state = 'RUNNING';
CREATE UNIQUE INDEX uq_sla_ticket_kind
    ON sla_record(ticket_id, kind) WHERE state <> 'CANCELLED';
CREATE INDEX idx_sla_ticket  ON sla_record(ticket_id);
CREATE INDEX idx_sla_at_risk ON sla_record(tenant_id, state, next_deadline_at);

CREATE TABLE sla_clock_segment (        -- APPEND-ONLY. There is no elapsed_minutes column.
    id            BIGSERIAL   PRIMARY KEY,
    sla_record_id BIGINT      NOT NULL,
    state         VARCHAR(10) NOT NULL
                  CONSTRAINT ck_segment_state CHECK (state IN ('RUNNING','PAUSED')),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at      TIMESTAMPTZ NULL,     -- NULL = the currently open segment
    pause_reason  VARCHAR(40) NULL
                  CONSTRAINT ck_segment_reason CHECK (pause_reason IS NULL OR pause_reason IN
                    ('WAITING_ON_CUSTOMER','PENDING_THIRD_PARTY','INCIDENT_LINKED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_segment_order CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT fk_segment_record FOREIGN KEY (sla_record_id)
        REFERENCES sla_record(id) ON DELETE CASCADE
);
-- Exactly one open segment per record. Two concurrent pauses cannot both succeed.
CREATE UNIQUE INDEX uq_segment_open
    ON sla_clock_segment(sla_record_id) WHERE ended_at IS NULL;
CREATE INDEX idx_segment_record ON sla_clock_segment(sla_record_id, started_at);

CREATE TABLE sla_escalation (
    id                       BIGSERIAL   PRIMARY KEY,
    sla_record_id            BIGINT      NOT NULL,
    rung                     SMALLINT    NOT NULL
                             CONSTRAINT ck_escalation_rung CHECK (rung IN (50,75,90,100)),
    fired_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notification_id          BIGINT      NULL,
    elapsed_minutes_at_fire  INTEGER     NOT NULL,
    -- Turns an at-least-once poller into an exactly-once effect.
    CONSTRAINT uq_escalation_rung UNIQUE (sla_record_id, rung),
    CONSTRAINT fk_escalation_record FOREIGN KEY (sla_record_id)
        REFERENCES sla_record(id) ON DELETE CASCADE
);
```

### V4 — AI and knowledge

```sql
CREATE TABLE prompt_version (        -- immutable rows
    id            BIGSERIAL   PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    version       INTEGER     NOT NULL CONSTRAINT ck_prompt_version CHECK (version > 0),
    template      TEXT        NOT NULL,
    model_id      VARCHAR(80) NOT NULL,
    params        JSONB       NOT NULL DEFAULT '{}',
    output_schema JSONB       NULL,
    is_active     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_prompt_name_version UNIQUE (name, version)
);
CREATE UNIQUE INDEX uq_prompt_active ON prompt_version(name) WHERE is_active;

CREATE TABLE ai_analysis (
    id                BIGSERIAL    PRIMARY KEY,
    ticket_id         BIGINT       NOT NULL,
    tenant_id         BIGINT       NOT NULL,
    prompt_version_id BIGINT       NOT NULL,
    model_id          VARCHAR(80)  NOT NULL,   -- may differ after escalation/failover
    signals           JSONB        NOT NULL,   -- the TriageSignals object
    confidence        NUMERIC(4,3) NULL
                      CONSTRAINT ck_analysis_conf CHECK (confidence BETWEEN 0 AND 1),
    raw_response      JSONB        NULL,
    tokens_in         INTEGER      NOT NULL DEFAULT 0,
    tokens_out        INTEGER      NOT NULL DEFAULT 0,
    cost_micros       BIGINT       NOT NULL DEFAULT 0,
    latency_ms        INTEGER      NOT NULL DEFAULT 0,
    attempt           SMALLINT     NOT NULL DEFAULT 1,
    status            VARCHAR(20)  NOT NULL DEFAULT 'OK'
                      CONSTRAINT ck_analysis_status CHECK (status IN
                        ('OK','PARSE_FAILED','ESCALATED','FAILED','BUDGET_HELD')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- idempotency: a redelivered triage event cannot duplicate the row
    CONSTRAINT uq_analysis_ticket_prompt UNIQUE (ticket_id, prompt_version_id, attempt),
    CONSTRAINT fk_analysis_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)         ON DELETE CASCADE,
    CONSTRAINT fk_analysis_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT,
    CONSTRAINT fk_analysis_prompt FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT
);
CREATE INDEX idx_analysis_ticket ON ai_analysis(ticket_id, created_at DESC);
CREATE INDEX idx_analysis_usage  ON ai_analysis(tenant_id, created_at);

CREATE TABLE priority_decision (
    id                BIGSERIAL   PRIMARY KEY,
    ticket_id         BIGINT      NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    ai_analysis_id    BIGINT      NULL,      -- NULL when triage was unavailable
    policy_version    VARCHAR(30) NOT NULL,
    input_signals     JSONB       NOT NULL,  -- EVERY input, snapshotted
    computed_priority VARCHAR(12) NOT NULL
                      CONSTRAINT ck_decision_priority CHECK (computed_priority IN
                        ('UNTRIAGED','P1','P2','P3','P4')),
    rationale         JSONB       NOT NULL,  -- shown in the UI
    decided_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_decision_ticket   FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)      ON DELETE CASCADE,
    CONSTRAINT fk_decision_tenant   FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)      ON DELETE RESTRICT,
    CONSTRAINT fk_decision_analysis FOREIGN KEY (ai_analysis_id)
        REFERENCES ai_analysis(id) ON DELETE SET NULL
);
CREATE INDEX idx_priority_ticket ON priority_decision(ticket_id, decided_at DESC);

CREATE TABLE priority_override (
    id            BIGSERIAL    PRIMARY KEY,
    ticket_id     BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL,
    from_priority VARCHAR(12)  NOT NULL,
    to_priority   VARCHAR(12)  NOT NULL
                  CONSTRAINT ck_override_to CHECK (to_priority IN ('P1','P2','P3','P4')),
    reason        VARCHAR(500) NOT NULL,
    overridden_by BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_override_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_override_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_override_user   FOREIGN KEY (overridden_by)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_override_ticket   ON priority_override(ticket_id);
CREATE INDEX idx_override_analysis ON priority_override(tenant_id, created_at DESC);

CREATE TABLE knowledge_document (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    source           VARCHAR(20)  NOT NULL
                     CONSTRAINT ck_kb_source CHECK (source IN
                       ('ARTICLE','RUNBOOK','RESOLVED_TICKET')),
    title            VARCHAR(255) NOT NULL,
    body             TEXT         NOT NULL,
    uri              VARCHAR(500) NULL,
    content_sha256   CHAR(64)     NOT NULL,
    kb_version       BIGINT       NOT NULL DEFAULT 1,  -- part of every retrieval cache key
    source_ticket_id BIGINT       NULL,
    indexed_at       TIMESTAMPTZ  NULL,
    deleted_at       TIMESTAMPTZ  NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_kb_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_kb_ticket FOREIGN KEY (source_ticket_id)
        REFERENCES ticket(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX uq_kb_content
    ON knowledge_document(tenant_id, content_sha256) WHERE deleted_at IS NULL;
CREATE INDEX idx_kb_tenant  ON knowledge_document(tenant_id, source) WHERE deleted_at IS NULL;
CREATE INDEX idx_kb_pending ON knowledge_document(tenant_id)
    WHERE indexed_at IS NULL AND deleted_at IS NULL;

CREATE TABLE knowledge_chunk (
    id          BIGSERIAL   PRIMARY KEY,
    document_id BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,   -- denormalised: pre-filter before the vector scan
    ordinal     INTEGER     NOT NULL,
    text        TEXT        NOT NULL,
    text_tsv    tsvector    NOT NULL,   -- lexical half
    embedding   vector(768) NOT NULL,   -- dense half
    token_count INTEGER     NOT NULL,
    char_start  INTEGER     NOT NULL,   -- so a citation can point at an exact span
    char_end    INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chunk_ordinal UNIQUE (document_id, ordinal),
    CONSTRAINT ck_chunk_span    CHECK (char_end > char_start),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
        REFERENCES knowledge_document(id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_tenant   FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)             ON DELETE RESTRICT
);
CREATE INDEX idx_chunk_embedding ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_tsv       ON knowledge_chunk USING GIN (text_tsv);
CREATE INDEX idx_chunk_tenant    ON knowledge_chunk(tenant_id);

CREATE TABLE draft_claim (
    id             BIGSERIAL   PRIMARY KEY,
    draft_id       BIGINT      NOT NULL,
    ordinal        INTEGER     NOT NULL,
    text           TEXT        NOT NULL,
    verdict        VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                   CONSTRAINT ck_claim_verdict CHECK (verdict IN
                     ('PENDING','SUPPORTED','PARTIAL','NOT_SUPPORTED','FAILED_NUMERIC_CHECK')),
    verifier_model VARCHAR(80) NULL,   -- NULL = rejected by the free deterministic pre-filter
    kept           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_claim_ordinal UNIQUE (draft_id, ordinal),
    CONSTRAINT fk_claim_draft FOREIGN KEY (draft_id)
        REFERENCES draft(id) ON DELETE CASCADE
);
CREATE INDEX idx_claim_verdict ON draft_claim(verdict);

CREATE TABLE draft_claim_citation (
    id             BIGSERIAL PRIMARY KEY,
    draft_claim_id BIGINT    NOT NULL,
    chunk_id       BIGINT    NOT NULL,
    char_start     INTEGER   NOT NULL,
    char_end       INTEGER   NOT NULL,
    CONSTRAINT uq_claim_citation UNIQUE (draft_claim_id, chunk_id, char_start),
    CONSTRAINT ck_citation_span CHECK (char_end > char_start),
    CONSTRAINT fk_citation_claim FOREIGN KEY (draft_claim_id)
        REFERENCES draft_claim(id)     ON DELETE CASCADE,
    CONSTRAINT fk_citation_chunk FOREIGN KEY (chunk_id)
        REFERENCES knowledge_chunk(id) ON DELETE RESTRICT
);
CREATE INDEX idx_citation_chunk ON draft_claim_citation(chunk_id);

CREATE TABLE agent_draft_action (
    id            BIGSERIAL   PRIMARY KEY,
    draft_id      BIGINT      NOT NULL,
    agent_id      BIGINT      NOT NULL,
    action        VARCHAR(20) NOT NULL
                  CONSTRAINT ck_draft_action CHECK (action IN
                    ('SENT_AS_IS','EDITED','DISCARDED')),
    edit_distance INTEGER     NULL CONSTRAINT ck_edit_distance CHECK (edit_distance >= 0),
    final_text    TEXT        NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_draft_action UNIQUE (draft_id),
    CONSTRAINT fk_action_draft FOREIGN KEY (draft_id)
        REFERENCES draft(id)    ON DELETE CASCADE,
    CONSTRAINT fk_action_agent FOREIGN KEY (agent_id)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_draft_action_metric ON agent_draft_action(action, created_at);
```

### V5 — Incidents

```sql
CREATE TABLE incident (
    id                        BIGSERIAL     PRIMARY KEY,
    tenant_id                 BIGINT        NOT NULL,
    reference                 VARCHAR(24)   NOT NULL,
    title                     VARCHAR(200)  NOT NULL,
    summary                   TEXT          NULL,
    generated_by_model        VARCHAR(80)   NULL,   -- NULL = templated title
    prompt_version_id         BIGINT        NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'PROPOSED'
                              CONSTRAINT ck_incident_status CHECK (status IN
                                ('PROPOSED','CONFIRMED','REJECTED','MITIGATED','RESOLVED')),
    detection_method          VARCHAR(20)   NOT NULL DEFAULT 'CLUSTER'
                              CONSTRAINT ck_incident_method CHECK (detection_method IN
                                ('CLUSTER','MANUAL')),
    cluster_size_at_detection INTEGER       NOT NULL DEFAULT 0,
    arrival_rate_multiple     NUMERIC(6,2)  NULL,   -- the gate's evidence, stored
    first_ticket_at           TIMESTAMPTZ   NOT NULL,
    detected_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    confirmed_by              BIGINT        NULL,
    confirmed_at              TIMESTAMPTZ   NULL,
    rejected_reason           VARCHAR(500)  NULL,
    resolved_at               TIMESTAMPTZ   NULL,
    version                   INTEGER       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_incident_reference UNIQUE (tenant_id, reference),
    CONSTRAINT fk_incident_tenant  FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)         ON DELETE RESTRICT,
    CONSTRAINT fk_incident_prompt  FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_incident_confirm FOREIGN KEY (confirmed_by)
        REFERENCES app_user(id)       ON DELETE SET NULL
);
CREATE INDEX idx_incident_board ON incident(tenant_id, status, detected_at DESC);
CREATE INDEX idx_incident_open  ON incident(tenant_id)
    WHERE status IN ('PROPOSED','CONFIRMED','MITIGATED');

CREATE TABLE incident_ticket (
    id              BIGSERIAL    PRIMARY KEY,
    incident_id     BIGINT       NOT NULL,
    ticket_id       BIGINT       NOT NULL,
    link_confidence NUMERIC(4,3) NULL,
    linked_by       BIGINT       NULL,     -- NULL = automatic
    linked_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    detached_at     TIMESTAMPTZ  NULL,     -- soft detach; a wrong link is useful history
    detached_by     BIGINT       NULL,
    CONSTRAINT fk_it_incident FOREIGN KEY (incident_id)
        REFERENCES incident(id) ON DELETE CASCADE,
    CONSTRAINT fk_it_ticket   FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)   ON DELETE CASCADE,
    CONSTRAINT fk_it_linker   FOREIGN KEY (linked_by)
        REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_it_detacher FOREIGN KEY (detached_by)
        REFERENCES app_user(id) ON DELETE SET NULL
);
-- A ticket belongs to at most ONE live incident. Enforced, not hoped for.
CREATE UNIQUE INDEX uq_incident_ticket_live
    ON incident_ticket(ticket_id) WHERE detached_at IS NULL;
CREATE INDEX idx_incident_tickets
    ON incident_ticket(incident_id) WHERE detached_at IS NULL;

CREATE TABLE incident_update (
    id           BIGSERIAL   PRIMARY KEY,
    incident_id  BIGINT      NOT NULL,
    author_id    BIGINT      NOT NULL,
    body         TEXT        NOT NULL,
    visibility   VARCHAR(10) NOT NULL DEFAULT 'INTERNAL'
                 CONSTRAINT ck_iu_visibility CHECK (visibility IN ('PUBLIC','INTERNAL')),
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_iu_incident FOREIGN KEY (incident_id)
        REFERENCES incident(id) ON DELETE CASCADE,
    CONSTRAINT fk_iu_author   FOREIGN KEY (author_id)
        REFERENCES app_user(id) ON DELETE RESTRICT
);
CREATE INDEX idx_update_incident ON incident_update(incident_id, published_at);

CREATE TABLE incident_update_delivery (
    id                 BIGSERIAL    PRIMARY KEY,
    incident_update_id BIGINT       NOT NULL,
    ticket_id          BIGINT       NOT NULL,
    status             VARCHAR(12)  NOT NULL DEFAULT 'PENDING'
                       CONSTRAINT ck_delivery_status CHECK (status IN
                         ('PENDING','SENT','FAILED')),
    attempts           SMALLINT     NOT NULL DEFAULT 0,
    last_error         VARCHAR(500) NULL,
    delivered_at       TIMESTAMPTZ  NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- fan-out idempotency: a redelivered event will not double-notify a customer
    CONSTRAINT uq_delivery UNIQUE (incident_update_id, ticket_id),
    CONSTRAINT fk_delivery_update FOREIGN KEY (incident_update_id)
        REFERENCES incident_update(id) ON DELETE CASCADE,
    CONSTRAINT fk_delivery_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id)          ON DELETE CASCADE
);
CREATE INDEX idx_delivery_pending
    ON incident_update_delivery(status, created_at) WHERE status = 'PENDING';
```

### V6 — Platform and evaluation

```sql
CREATE TABLE outbox_event (
    id              BIGSERIAL     PRIMARY KEY,
    tenant_id       BIGINT        NULL,
    aggregate_type  VARCHAR(40)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,   -- deliberately NOT a FK: generic queue
    event_type      VARCHAR(50)   NOT NULL,
    payload         JSONB         NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'PENDING'
                    CONSTRAINT ck_outbox_status CHECK (status IN
                      ('PENDING','IN_FLIGHT','DONE','DEAD')),
    attempts        SMALLINT      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    locked_until    TIMESTAMPTZ   NULL,       -- visibility timeout
    last_error      VARCHAR(1000) NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ   NULL,
    CONSTRAINT fk_outbox_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE SET NULL
);
-- The single highest-frequency query in the system (6 workers × 1/sec).
-- The partial clause keeps the index tiny as DONE rows accumulate.
CREATE INDEX idx_outbox_claim  ON outbox_event(event_type, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_reaper ON outbox_event(locked_until) WHERE status = 'IN_FLIGHT';
CREATE INDEX idx_outbox_prune  ON outbox_event(created_at)   WHERE status = 'DONE';
CREATE INDEX idx_outbox_dlq    ON outbox_event(tenant_id)    WHERE status = 'DEAD';

CREATE TABLE idempotency_record (
    id              BIGSERIAL    PRIMARY KEY,
    idem_key        VARCHAR(128) NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    endpoint        VARCHAR(160) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,   -- same key + different body = 422
    response_status INTEGER      NOT NULL,
    response_body   JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    CONSTRAINT uq_idem UNIQUE (tenant_id, idem_key, endpoint),
    CONSTRAINT fk_idem_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);
CREATE INDEX idx_idem_expiry ON idempotency_record(expires_at);

CREATE TABLE tenant_ai_policy (
    tenant_id                  BIGINT      PRIMARY KEY,
    external_model_allowed     BOOLEAN     NOT NULL DEFAULT TRUE,
    allowed_providers          TEXT[]      NOT NULL DEFAULT '{}',
    pii_redaction_required     BOOLEAN     NOT NULL DEFAULT TRUE,
    monthly_budget_micros      BIGINT      NOT NULL DEFAULT 5000000
                               CONSTRAINT ck_policy_budget CHECK (monthly_budget_micros >= 0),
    current_month_spend_micros BIGINT      NOT NULL DEFAULT 0,
    retention_days             INTEGER     NOT NULL DEFAULT 365
                               CONSTRAINT ck_policy_retention CHECK (retention_days > 0),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_aipolicy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE
);

CREATE TABLE pii_redaction_map (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL,
    ticket_id       BIGINT      NOT NULL,
    placeholder     VARCHAR(40) NOT NULL,
    pii_type        VARCHAR(20) NOT NULL
                    CONSTRAINT ck_pii_type CHECK (pii_type IN
                      ('PERSON','EMAIL','PHONE','CARD','GOV_ID','IP','ORDER_REF')),
    encrypted_value BYTEA       NOT NULL,   -- AES-GCM; never transmitted, never logged
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pii_placeholder UNIQUE (ticket_id, placeholder),
    CONSTRAINT fk_pii_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id) ON DELETE CASCADE,
    CONSTRAINT fk_pii_ticket FOREIGN KEY (ticket_id)
        REFERENCES ticket(id) ON DELETE CASCADE
);

CREATE TABLE notification (
    id           BIGSERIAL     PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL,
    recipient_id BIGINT        NOT NULL,
    kind         VARCHAR(40)   NOT NULL
                 CONSTRAINT ck_notification_kind CHECK (kind IN
                   ('SLA_ESCALATION','SLA_BREACH','TICKET_ASSIGNED','INCIDENT_PROPOSED',
                    'INCIDENT_UPDATE','BUDGET_EXHAUSTED','TRIAGE_FAILED')),
    title        VARCHAR(200)  NOT NULL,
    body         VARCHAR(1000) NULL,
    link_url     VARCHAR(300)  NULL,
    read_at      TIMESTAMPTZ   NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notification_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant(id)   ON DELETE RESTRICT,
    CONSTRAINT fk_notification_user   FOREIGN KEY (recipient_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);
CREATE INDEX idx_notification_unread
    ON notification(recipient_id, created_at DESC) WHERE read_at IS NULL;

ALTER TABLE sla_escalation
    ADD CONSTRAINT fk_escalation_notification FOREIGN KEY (notification_id)
        REFERENCES notification(id) ON DELETE SET NULL;

CREATE TABLE eval_case (
    id            BIGSERIAL    PRIMARY KEY,
    suite         VARCHAR(40)  NOT NULL
                  CONSTRAINT ck_eval_suite CHECK (suite IN
                    ('CLASSIFICATION','RETRIEVAL','GROUNDING','REFUSAL','INCIDENT')),
    name          VARCHAR(120) NOT NULL,
    input_fixture JSONB        NOT NULL,
    expected      JSONB        NOT NULL,
    source        VARCHAR(20)  NOT NULL DEFAULT 'HAND_LABELLED'
                  CONSTRAINT ck_eval_source CHECK (source IN
                    ('HAND_LABELLED','AGENT_OVERRIDE','INCIDENT_CONFIRM')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_eval_case UNIQUE (suite, name)
);
CREATE INDEX idx_eval_active ON eval_case(suite) WHERE is_active;

CREATE TABLE eval_run (
    id                BIGSERIAL   PRIMARY KEY,
    suite             VARCHAR(40) NOT NULL,
    prompt_version_id BIGINT      NULL,
    model_id          VARCHAR(80) NOT NULL,
    git_sha           CHAR(40)    NULL,
    metrics           JSONB       NOT NULL,
    passed            BOOLEAN     NOT NULL,   -- the CI gate reads this one column
    started_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at       TIMESTAMPTZ NULL,
    CONSTRAINT fk_evalrun_prompt FOREIGN KEY (prompt_version_id)
        REFERENCES prompt_version(id) ON DELETE RESTRICT
);
CREATE INDEX idx_eval_run_suite ON eval_run(suite, started_at DESC);

CREATE TABLE eval_result (
    id           BIGSERIAL PRIMARY KEY,
    eval_run_id  BIGINT      NOT NULL,
    eval_case_id BIGINT      NOT NULL,
    metric       VARCHAR(40) NOT NULL,
    expected     JSONB       NOT NULL,
    actual       JSONB       NOT NULL,
    passed       BOOLEAN     NOT NULL,
    CONSTRAINT fk_evalresult_run  FOREIGN KEY (eval_run_id)
        REFERENCES eval_run(id)  ON DELETE CASCADE,
    CONSTRAINT fk_evalresult_case FOREIGN KEY (eval_case_id)
        REFERENCES eval_case(id) ON DELETE RESTRICT
);
CREATE INDEX idx_eval_result_run ON eval_result(eval_run_id, passed);
```

### V7 — Triggers

```sql
-- updated_at maintenance for tables also written by NATIVE queries,
-- which bypass Hibernate's @UpdateTimestamp entirely.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_updated        BEFORE UPDATE ON ticket
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_sla_record_updated    BEFORE UPDATE ON sla_record
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_agent_profile_updated BEFORE UPDATE ON agent_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_incident_updated      BEFORE UPDATE ON incident
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only enforcement. Convention is not a constraint.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % is not permitted',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_event_immutable
    BEFORE UPDATE OR DELETE ON ticket_event
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_escalation_immutable
    BEFORE UPDATE OR DELETE ON sla_escalation
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_prompt_immutable
    BEFORE UPDATE OF template, model_id, output_schema ON prompt_version
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
-- NOTE: sla_clock_segment is NOT fully immutable — closing a segment sets
-- ended_at exactly once. Enforced by a narrower guard instead:
CREATE OR REPLACE FUNCTION forbid_segment_reopen() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'sla_clock_segment % is closed and cannot be modified', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_segment_close_once
    BEFORE UPDATE ON sla_clock_segment
    FOR EACH ROW EXECUTE FUNCTION forbid_segment_reopen();

-- Full-text vectors maintained by the database, so they can never drift
-- out of sync with the columns they index.
CREATE OR REPLACE FUNCTION ticket_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.search_tsv :=
        setweight(to_tsvector('english', COALESCE(NEW.subject,'')), 'A') ||
        setweight(to_tsvector('english', COALESCE(NEW.body,'')),    'B');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_tsv BEFORE INSERT OR UPDATE OF subject, body ON ticket
    FOR EACH ROW EXECUTE FUNCTION ticket_tsv_update();

CREATE OR REPLACE FUNCTION chunk_tsv_update() RETURNS TRIGGER AS $$
BEGIN
    NEW.text_tsv := to_tsvector('english', COALESCE(NEW.text,''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chunk_tsv BEFORE INSERT OR UPDATE OF text ON knowledge_chunk
    FOR EACH ROW EXECUTE FUNCTION chunk_tsv_update();
```

---

## STEP 7 — Scalability Notes

Three real bottlenecks for **this** schema, in the order they will bite.

### 1. `outbox_event` unbounded growth — the one that will actually happen

**What breaks:** `idx_outbox_claim` is queried ~518,000 times a day. Although it is a partial index on `status='PENDING'`, the *table* keeps every `DONE` row forever. Autovacuum works harder, the table bloats, and index maintenance on insert slows down.

**When:** noticeable around 500k retained rows (~2 months at 9,000 events/day); painful past 2M.

**The fix:**
```sql
DELETE FROM outbox_event
 WHERE status = 'DONE' AND created_at < NOW() - INTERVAL '7 days';
```
Nightly, batched at 10,000 rows per statement so it does not hold a long transaction. `idx_outbox_prune` exists precisely for this. **Ship it in Phase 10.**
At genuinely high volume, switch to monthly `PARTITION BY RANGE (created_at)` and `DROP PARTITION`, which is instant and produces no dead tuples.

### 2. The agent queue query

**What breaks:** `GET /tickets` filtering on `(tenant_id, status, priority)` sorted by `created_at DESC` with an optional full-text term. `idx_ticket_queue` handles the common case, but **offset pagination degrades linearly** — `OFFSET 5000` makes PostgreSQL scan and discard 5,000 rows.

**When:** 50k–100k tickets per tenant, or any agent paging beyond ~20 pages.

**The fix — and note that the first part is a correctness fix, not a performance one:**
- **Cursor pagination**, keyed on `(created_at, id)`. Offset pagination is *wrong* here regardless of speed: tickets get resolved and drop out of the filtered set while an agent pages through it, so `OFFSET` silently skips rows the agent never sees.
- If the text-search branch is slow, split it: run the GIN search first to get candidate IDs, then filter — rather than making PostgreSQL choose between two indexes.
- Only after both: consider a covering index with `INCLUDE (subject, assignee_id)` to get an index-only scan.

### 3. HNSW index build time and recall on `knowledge_chunk`

**What breaks:** `POST /knowledge/reindex` rebuilds every embedding and the HNSW index. Build time grows super-linearly, and default `hnsw.ef_search` trades recall for latency in a way that is invisible until you measure it.

**When:** past ~100k chunks for build time; recall degradation is measurable much earlier.

**The fix:**
- **Never reindex everything.** `content_sha256` lets the indexer skip unchanged documents — reindex is then proportional to *changed* documents.
- Tune `hnsw.ef_search` **against the retrieval eval suite**, not by guessing. This is exactly what the eval harness is for, and "I tuned a parameter against a measured recall curve" is a much better sentence than "I used the default."
- Keep the `tenant_id` pre-filter as a `WHERE` clause so the scan covers fewer rows.

### Honest framing

This is a portfolio project that will run with a few thousand tickets. **Do fix #1 and the cursor-pagination half of #2** — the first is ten lines and the second is a correctness bug. Know the rest, say them in interviews, and do not build for them.

**And note the one that does *not* break:** `idx_sla_poller` is a partial index on `state='RUNNING'`, scanned as a range with a `LIMIT`. Its cost is proportional to *due* records, not *open* ones — a million future deadlines cost nothing to skip. That property falls directly out of choosing absolute deadlines in an indexed column over per-ticket timers, and it is worth pointing at.

---

## STEP 8 — JPA Entity Mapping Notes

### Table → entity

```
tenant                    → @Entity Tenant
app_user                  → @Entity AppUser          ("user" is reserved in SQL — hence app_user)
team                      → @Entity Team
agent_profile             → @Entity AgentProfile
refresh_token             → @Entity RefreshToken
ticket                    → @Entity Ticket
ticket_message            → @Entity TicketMessage
ticket_event              → @Entity TicketEvent      (no setters — append-only)
attachment                → @Entity Attachment
business_calendar         → @Entity BusinessCalendar
business_holiday          → @Entity BusinessHoliday
sla_policy                → @Entity SlaPolicy
sla_record                → @Entity SlaRecord
sla_clock_segment         → @Entity SlaClockSegment
sla_escalation            → @Entity SlaEscalation
prompt_version            → @Entity PromptVersion
ai_analysis               → @Entity AiAnalysis
priority_decision         → @Entity PriorityDecision
priority_override         → @Entity PriorityOverride
knowledge_document        → @Entity KnowledgeDocument
knowledge_chunk           → @Entity KnowledgeChunk
draft                     → @Entity Draft
draft_claim               → @Entity DraftClaim
draft_claim_citation      → @Entity DraftClaimCitation
agent_draft_action        → @Entity AgentDraftAction
incident                  → @Entity Incident
incident_ticket           → @Entity IncidentTicket   (association entity, has own PK + fields)
incident_update           → @Entity IncidentUpdate
incident_update_delivery  → @Entity IncidentUpdateDelivery
outbox_event              → @Entity OutboxEvent
idempotency_record        → @Entity IdempotencyRecord
tenant_ai_policy          → @Entity TenantAiPolicy   (@MapsId — PK is the FK)
pii_redaction_map         → @Entity PiiRedactionMap
notification              → @Entity Notification
eval_case / eval_run / eval_result → @Entity EvalCase / EvalRun / EvalResult
```

### Annotation cheat sheet

```java
// Primary keys — BIGSERIAL
@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

// Enums — ALWAYS STRING. ORDINAL corrupts every row if you reorder the enum.
@Enumerated(EnumType.STRING)
@Column(name = "status", length = 24, nullable = false)
private TicketStatus status;

// Timestamps
@CreationTimestamp @Column(updatable = false) private OffsetDateTime createdAt;
@UpdateTimestamp                              private OffsetDateTime updatedAt;

// Optimistic locking — backs ETag / If-Match, returns 409 on conflict
@Version private Integer version;

// Relationships — ALWAYS LAZY. EAGER on a @ManyToOne is the N+1 factory.
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "requester_id", nullable = false)
private AppUser requester;

@OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderBy("createdAt ASC")
private List<TicketMessage> messages = new ArrayList<>();

// One-to-one where the PK is the FK
@OneToOne(fetch = FetchType.LAZY) @MapsId
@JoinColumn(name = "tenant_id")
private Tenant tenant;

// Soft delete — converts delete() into an UPDATE, and filters every read
@SQLDelete(sql = "UPDATE app_user SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")

// JSONB
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb", nullable = false)
private TriageSignals signals;

// PostgreSQL arrays
@JdbcTypeCode(SqlTypes.ARRAY)
@Column(name = "skills", columnDefinition = "text[]")
private String[] skills;

// pgvector — no built-in Hibernate type; use the pgvector-java Hibernate integration
// or map as a custom UserType. Vector reads/writes go through native queries anyway.
@JdbcTypeCode(SqlTypes.VECTOR)
@Column(name = "embedding", columnDefinition = "vector(768)")
private float[] embedding;

// Pessimistic lock — the agent-assignment race
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
@Query(value = """
    SELECT * FROM agent_profile
     WHERE tenant_id = :tenantId AND is_available = TRUE
       AND open_count < max_concurrent
     ORDER BY open_count ASC
     LIMIT 1
     FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
Optional<AgentProfile> claimLeastLoadedAgent(@Param("tenantId") Long tenantId);
```

### Five mapping traps specific to this schema

1. **Do not map `outbox_event` claiming through JPA.** The claim is a native `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *`. Hibernate's dirty-checking and first-level cache actively work against a queue claim. Use `JdbcTemplate` or a native query and keep the transaction minimal.

2. **`ddl-auto` must be `validate`, never `update`.** Flyway owns the schema. `update` cannot express partial unique indexes, `CHECK` constraints, HNSW indexes, triggers or generated `tsvector` columns — it would silently produce a *different, weaker* schema than the one designed here. Set it once and never change it.

3. **`sla_clock_segment` must not be mapped as a cascading `@OneToMany` collection you mutate.** Closing a segment and opening a new one in the same transaction, through a managed collection, invites Hibernate to reorder the statements and trip `uq_segment_open`. **Insert and update the rows explicitly, in order.** This is the single most likely source of a mysterious constraint violation in Phase 5.

4. **Catch `DataIntegrityViolationException` on the escalation insert and swallow it.** That exception *is* the exactly-once mechanism working correctly, not an error. Check the constraint name (`uq_escalation_rung`) before swallowing, so you do not accidentally hide a genuine bug.

5. **Every repository method must be tenant-scoped.** Use a Hibernate `@Filter` enabled per request from the authenticated principal, rather than relying on every `@Query` remembering `AND tenant_id = :tenantId`. Then write the parameterised cross-tenant test that proves it for every endpoint — the filter is the mechanism, the test is the evidence.

---

## STEP 9 — Next Steps

> **Schema complete ✅**
>
> **Right now (Design Phase):**
> - Paste the SQL from Step 6 into `src/main/resources/db/migration/` as `V1__…` through `V7__…`
> - Run `docker compose up postgres` and apply the migrations. **Verify `CREATE EXTENSION vector` succeeds before anything else** — if the extension is unavailable, the retrieval design needs rethinking and you want to know that on day one, not in Week 4.
> - Sanity-check the partial unique indexes by hand in `psql`: insert two open `sla_clock_segment` rows for the same record and confirm the second fails. That two-minute test validates the most important structural guarantee in the schema.
>
> **When you start Phase 3 (Project Setup):**
> - `spring.jpa.hibernate.ddl-auto=validate` — let JPA validate against the Flyway-created schema, never auto-create
> - Configure HikariCP with `maximum-pool-size=10`
>
> **When you start Phases 4–8:**
> - Use the Step 8 mapping notes for every entity. Your entities must mirror this schema exactly — same names, same types, same nullability.
> - Re-read the five traps before writing the SLA module.
>
> **Next:** run the **api-designer skill** → [05 — API Contract](05-API-CONTRACT.md).
