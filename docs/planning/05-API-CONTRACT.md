# 05 — API Contract

> Skill: `api-designer` · Input: [02 — Project Plan](02-PROJECT-PLAN.md) + [04 — Database Schema](04-DATABASE-SCHEMA.md)
> Output: every endpoint decided before a single controller is written

Base URL: `/api/v1` · Auth: JWT Bearer · Content type: `application/json` · Errors: RFC 7807

---

## STEP 2 — Derived Endpoint List

Cross-checked both ways: every MVP feature has endpoints, and every table has at least one path that writes to it and one that reads from it.

| Group | Count | Endpoints |
|---|---|---|
| **Auth** | 6 | register, login, refresh, logout, me, change-password |
| **Tickets** | 9 | create, list, get, patch, message, assign, status, resolve, reopen |
| **Triage & AI** | 7 | analysis, retriage, priority-rationale, priority-override, request draft, get draft, draft action |
| **SLA** | 4 | get, pause, resume, at-risk |
| **Incidents** | 8 | list, get, confirm, reject, link, detach, publish update, resolve |
| **Knowledge** | 6 | create, list, get, delete, reindex, search |
| **Admin** | 9 | sla-policies (2), calendar (2), ai-policy (2), usage, eval runs, audit |
| **Platform** | 3 | users list, outbox DLQ, retry event |
| **Total** | **52** | |

### Endpoints that do NOT exist, deliberately

| Not created | Why |
|---|---|
| `PUT /tickets/{id}` | There is no operation that replaces a whole ticket. Every mutation is a specific, auditable transition: `PATCH` for fields, `/status` for the state machine, `/assign` for ownership. A full `PUT` would make mass assignment trivially available and the audit trail meaningless. |
| `DELETE /tickets/{id}` | Tickets are never deleted through the API. Data erasure is an operational procedure, not an endpoint. |
| `PUT /drafts/{id}` | A draft is immutable output. The agent's edit is recorded as an *action* with an edit distance, which is the metric we actually want. |
| `POST /tickets/{id}/priority` | Priority is **computed**, never set. The only human path is `/priority-override`, which requires a reason and is recorded as a label. |
| `POST /incidents` | Incidents are proposed by the correlation gate or created via `detection_method=MANUAL` through `/incidents/{id}/tickets`. There is no "create an empty incident" operation. |
| `GET /tickets/{id}/events` | Folded into `GET /tickets/{id}?include=timeline`. A separate endpoint for a sub-resource always fetched with its parent is a wasted round trip. |

### Table coverage check

Every table is reachable. The ones written *only* by workers and never by an HTTP request — `ai_analysis`, `priority_decision`, `sla_clock_segment`, `sla_escalation`, `outbox_event`, `pii_redaction_map`, `incident_update_delivery`, `eval_*` — are read through their parent resources or admin endpoints. That is correct: **they are system-generated, and an API that let a client write them would let a client forge an audit trail.**

---

## STEP 3 — Endpoint Specifications

### 3.1 Auth

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Register a customer account | Public |
| POST | `/api/v1/auth/login` | Exchange credentials for tokens | Public |
| POST | `/api/v1/auth/refresh` | Rotate the access token | Public (refresh token in body) |
| POST | `/api/v1/auth/logout` | Revoke the refresh token family | JWT |
| GET | `/api/v1/auth/me` | Current identity and permissions | JWT |
| PUT | `/api/v1/auth/me/password` | Change own password | JWT |

---

#### POST `/api/v1/auth/register`

**Description:** Register a new customer account under a tenant identified by slug. Agents, leads and admins are created by an admin, never self-registered.

**Auth:** Public

**Request:**
```json
{
  "tenantSlug": "string (required) — existing tenant slug, 2–60 chars",
  "email":      "string (required) — RFC 5322, max 255, unique within the tenant",
  "password":   "string (required) — 10–128 chars, ≥1 letter and ≥1 digit",
  "fullName":   "string (required) — 2–120 chars"
}
```

**Validation rules:**
- `tenantSlug`: required, must match an active tenant, else `404`
- `email`: required, valid format, normalised to lowercase, unique per tenant among non-deleted users
- `password`: required, min 10 chars (**not 8** — this is 2026), max 128 (BCrypt truncates at 72 bytes, so a longer cap silently weakens nothing but is still capped for sanity), at least one letter and one digit, checked against a top-1000 common-password list
- `fullName`: required, trimmed, 2–120 chars

**Success — `201 Created`:**
```json
{
  "id": 4821,
  "email": "priya@example.com",
  "fullName": "Priya Raman",
  "role": "CUSTOMER",
  "tenantSlug": "acme",
  "createdAt": "2026-09-20T09:14:33Z"
}
```

**Errors:**

| Status | When | Body `errorCode` |
|---|---|---|
| 400 | Validation failed | `VALIDATION_ERROR` (with `field`) |
| 404 | Tenant slug unknown or inactive | `TENANT_NOT_FOUND` |
| 409 | Email already registered for this tenant | `EMAIL_ALREADY_EXISTS` |
| 429 | Rate limit — 5/hour per IP | `RATE_LIMITED` |

**Note on `409`:** this does leak whether an email is registered with a tenant. Accepted deliberately — a support portal that silently swallows duplicate registration produces a worse failure (the user cannot log in and does not know why). Mitigated by the aggressive rate limit.

**Spring Boot:**
```java
@PostMapping("/auth/register")
@ResponseStatus(HttpStatus.CREATED)
public UserResponse register(@Valid @RequestBody RegisterRequest req) { ... }
```

---

#### POST `/api/v1/auth/login`

**Description:** Exchange credentials for an access token and a rotating refresh token.

**Auth:** Public

**Request:**
```json
{
  "tenantSlug": "string (required)",
  "email":      "string (required)",
  "password":   "string (required)"
}
```

**Success — `200 OK`:**
```json
{
  "accessToken":  "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "8f3a1c0e-...-b27d",
  "tokenType":    "Bearer",
  "expiresIn":    900,
  "user": {
    "id": 4821,
    "email": "priya@example.com",
    "fullName": "Priya Raman",
    "role": "CUSTOMER",
    "teamId": null,
    "tenantId": 3
  }
}
```

**Errors:**

| Status | When | `errorCode` |
|---|---|---|
| 401 | Wrong email **or** wrong password **or** user not found | `INVALID_CREDENTIALS` |
| 403 | Account or tenant deactivated | `ACCOUNT_DISABLED` |
| 429 | 10/minute per IP, 5/minute per email | `RATE_LIMITED` |

**Security note worth stating:** a single `401 INVALID_CREDENTIALS` covers all three failure causes. Distinguishing "no such user" from "wrong password" turns the login endpoint into a user-enumeration oracle. The response time is also padded to a constant by always running a BCrypt comparison — against a dummy hash when the user does not exist — so timing does not leak what the status code does not.

---

#### POST `/api/v1/auth/refresh`

**Description:** Exchange a refresh token for a new access token and a **new** refresh token. The presented token is consumed.

**Auth:** Public — the refresh token is the credential

**Request:** `{ "refreshToken": "string (required)" }`

**Success — `200 OK`:** same shape as login.

**Errors:**

| Status | When | `errorCode` |
|---|---|---|
| 401 | Unknown, expired or revoked token | `INVALID_REFRESH_TOKEN` |
| 401 | **Token was already used** — reuse detected | `TOKEN_REUSE_DETECTED` |

**The interesting case:** presenting a token whose `used_at` is already set means either the token was stolen and replayed, or the legitimate client is replaying. Either way the system cannot tell which party is the attacker, so it **revokes the entire `family_id`** and forces a fresh login. That is the standard refresh-token-rotation defence and it is worth being able to explain.

---

#### GET `/api/v1/auth/me`

**Success — `200 OK`:**
```json
{
  "id": 1204, "email": "arjun@acme.com", "fullName": "Arjun Mehta",
  "role": "AGENT", "teamId": 7, "teamName": "Payments",
  "tenantId": 3, "tenantSlug": "acme", "planTier": "PRO",
  "permissions": ["ticket:read:team","ticket:reply","ticket:assign:self",
                  "draft:request","incident:read"],
  "agentProfile": { "maxConcurrent": 15, "openCount": 9, "isAvailable": true }
}
```

The explicit `permissions` array exists so the frontend never has to re-derive authorization from the role string. **The server remains the only authority** — this array is for hiding buttons, never for enforcing anything.

---

### 3.2 Tickets

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/tickets` | Create → `202` + async triage | Any |
| GET | `/api/v1/tickets` | List, filter, search, cursor-paginated | Any (role-scoped) |
| GET | `/api/v1/tickets/{id}` | Full detail | Any (ownership-checked) |
| PATCH | `/api/v1/tickets/{id}` | Update subject / category / team | AGENT+ |
| POST | `/api/v1/tickets/{id}/messages` | Reply or internal note | Any |
| POST | `/api/v1/tickets/{id}/assign` | Assign to self or another agent | AGENT+ |
| POST | `/api/v1/tickets/{id}/status` | Drive the state machine | AGENT+ |
| POST | `/api/v1/tickets/{id}/resolve` | Resolve with resolution text | AGENT+ |
| POST | `/api/v1/tickets/{id}/reopen` | Reopen | Any |

---

#### POST `/api/v1/tickets`

**Description:** Create a ticket. Commits the ticket and an outbox event in one transaction and returns immediately — **triage happens asynchronously.**

**Auth:** JWT, any role

**Headers:**
```
Authorization:   Bearer <jwt>          required
Idempotency-Key: <uuid>                REQUIRED
Content-Type:    application/json
```

**Request:**
```json
{
  "subject":       "string (required) — 1–200 chars",
  "body":          "string (required) — 1–20000 chars",
  "attachmentIds": "long[] (optional) — max 5, from a prior upload",
  "onBehalfOf":    "long (optional) — AGENT+ only; create on a customer's behalf"
}
```

**Validation rules:**
- `subject`: required, trimmed, 1–200 chars, not whitespace-only
- `body`: required, 1–20,000 chars. **The upper bound is a security control** — an uncapped body is a token-cost denial of service, since every ticket triggers an LLM call.
- `attachmentIds`: max 5, each must exist, be owned by the caller, and be unattached
- `onBehalfOf`: rejected with `403` if the caller is a `CUSTOMER`
- `Idempotency-Key`: required, 16–128 chars, `[A-Za-z0-9_-]` only (it becomes a Redis key)

**Success — `202 Accepted`:**
```json
{
  "id": 88213,
  "reference": "TKT-10428",
  "subject": "Payment deducted but order still pending",
  "status": "OPEN",
  "priority": "UNTRIAGED",
  "category": null,
  "analysisStatus": "PROCESSING",
  "requester": { "id": 4821, "fullName": "Priya Raman" },
  "assignee": null,
  "team": null,
  "createdAt": "2026-09-20T09:22:05Z",
  "links": {
    "self":     "/api/v1/tickets/88213",
    "analysis": "/api/v1/tickets/88213/analysis"
  }
}
```

> **Phase 2 Task 2 — a documented, intentional change.** This endpoint returns **`201` in
> Phase 5** and **`202` from Phase 6 onward.** In Phase 5 there is no outbox and no async
> triage, so the representation returned *is* final and `202` would be a lie. When triage
> becomes asynchronous the status changes, in its own commit. The contract is not wrong for
> six weeks — it is versioned by build phase.

**`202`, not `201` — and this is the interview question.** The resource *is* created, which normally argues for `201`. But the response deliberately advertises `analysisStatus: "PROCESSING"`, and `priority`, `category`, `assignee` and `team` are all still null and **will change without any further client action**. `202` tells the client "accepted, processing continues, poll the status resource." Returning `201` would imply the representation is final, and the client would have no reason to poll. Either choice is defensible; what matters is being able to say *why*.

**Errors:**

| Status | When | `errorCode` |
|---|---|---|
| 400 | Validation failed | `VALIDATION_ERROR` |
| 401 | Missing/invalid JWT | `UNAUTHORIZED` |
| 403 | `onBehalfOf` used by a CUSTOMER | `FORBIDDEN` |
| 400 | `Idempotency-Key` missing or malformed | `IDEMPOTENCY_KEY_REQUIRED` |
| 422 | Same key, **different** body | `IDEMPOTENCY_KEY_CONFLICT` |
| 429 | 20/min per user, 200/min per tenant | `RATE_LIMITED` |
| 503 | Redis unavailable (idempotency fails closed) | `IDEMPOTENCY_UNAVAILABLE` |

**Replaying the same key with the same body returns the original `202` response verbatim**, including the same `id`. Same key with a different body is `422`, never a silent replay of the wrong response.

**Spring Boot:**
```java
@PostMapping("/tickets")
@ResponseStatus(HttpStatus.ACCEPTED)
@Idempotent                                    // custom annotation → interceptor
public TicketResponse create(
        @Valid @RequestBody CreateTicketRequest req,
        @RequestHeader("Idempotency-Key") @Pattern(regexp="[A-Za-z0-9_-]{16,128}") String key,
        @AuthenticationPrincipal ResolvePrincipal principal) { ... }
```

---

#### GET `/api/v1/tickets`

**Description:** List tickets. **What the caller can see is determined by role, not by a query parameter.**

**Auth:** JWT

**Query parameters:**

| Param | Type | Required | Default | Description |
|---|---|---|---|---|
| `cursor` | string | No | — | Opaque cursor from the previous page. **Not `page`** — see below. |
| `size` | integer | No | 25 | 1–100, clamped server-side |
| `status` | string[] | No | — | Repeatable: `?status=OPEN&status=ASSIGNED` |
| `priority` | string[] | No | — | Repeatable |
| `teamId` | long | No | — | TEAM_LEAD+ only |
| `assigneeId` | long | No | — | `me` accepted as an alias |
| `incidentId` | long | No | — | Tickets linked to an incident |
| `slaState` | string | No | — | `AT_RISK`, `BREACHED` |
| `q` | string | No | — | Full-text over subject + body, max 200 chars |
| `createdFrom` / `createdTo` | ISO-8601 | No | — | |
| `sort` | string | No | `created_at` | `created_at`, `priority`, `sla_deadline` |
| `order` | string | No | `desc` | `asc` / `desc` |

**Role scoping, applied server-side and not overridable:**

| Role | Sees |
|---|---|
| `CUSTOMER` | Only tickets where `requester_id = me` |
| `AGENT` | Tickets assigned to them **or** in their team's queue |
| `TEAM_LEAD` | All tickets in their team |
| `ADMIN` | All tickets in the tenant |

**Success — `200 OK`:**
```json
{
  "data": [
    {
      "id": 88213, "reference": "TKT-10428",
      "subject": "Payment deducted but order still pending",
      "status": "ASSIGNED", "priority": "P2", "category": "PAYMENT",
      "requester": { "id": 4821, "fullName": "Priya Raman" },
      "assignee":  { "id": 1204, "fullName": "Arjun Mehta" },
      "team":      { "id": 7, "name": "Payments" },
      "incidentRef": null,
      "sla": {
        "firstResponse": { "state": "RUNNING", "remainingBusinessMinutes": 142, "atRisk": false },
        "resolution":    { "state": "RUNNING", "remainingBusinessMinutes": 812, "atRisk": false }
      },
      "messageCount": 2,
      "createdAt": "2026-09-20T09:22:05Z",
      "updatedAt": "2026-09-20T09:22:41Z"
    }
  ],
  "pagination": {
    "size": 25,
    "nextCursor": "eyJjIjoiMjAyNi0wOS0yMFQwOToyMjowNVoiLCJpIjo4ODIxM30",
    "hasNext": true
  }
}
```

**Why cursor pagination and not `page`/`offset` — say this unprompted.** Tickets resolve out of a filtered set *while an agent is paging through it*. With `OFFSET`, resolving a ticket on page 1 shifts everything up by one and **page 2 silently skips a ticket the agent never saw.** That is a correctness bug, not a performance one. The cursor encodes `(created_at, id)` and is stable under concurrent mutation. `OFFSET` also degrades linearly.

**Deliberate REST deviation:** the response is *not* a bare array. A wrapper with `data` and `pagination` is used on every list endpoint in the API, consistently.

**Errors:** `400 INVALID_CURSOR` · `400 VALIDATION_ERROR` (bad enum or `size` out of range) · `401` · `403 FORBIDDEN` (a CUSTOMER passing `teamId`)

---

#### GET `/api/v1/tickets/{id}`

**Query parameters:** `include` — comma-separated: `timeline`, `analysis`, `sla`, `incident`. Default: all except `timeline`.

**Success — `200 OK`** (abbreviated; adds to the list shape):
```json
{
  "id": 88213, "reference": "TKT-10428",
  "subject": "Payment deducted but order still pending",
  "body": "I paid via UPI at 14:03 and the money left my account...",
  "status": "ASSIGNED", "priority": "P2", "category": "PAYMENT",
  "priorityRationale": {
    "policyVersion": "v3",
    "computed": "P2",
    "because": ["plan tier PRO", "payment affected", "impact SINGLE_USER"]
  },
  "reopenCount": 0,
  "messages": [
    { "id": 51002, "authorId": 4821, "authorName": "Priya Raman",
      "authorRole": "CUSTOMER", "visibility": "PUBLIC",
      "body": "I paid via UPI at 14:03...", "isFirstResponse": false,
      "fromDraftId": null, "attachments": [],
      "createdAt": "2026-09-20T09:22:05Z" }
  ],
  "sla": {
    "firstResponse": {
      "state": "RUNNING", "targetBusinessMinutes": 240,
      "elapsedBusinessMinutes": 98, "remainingBusinessMinutes": 142,
      "nextDeadlineAt": "2026-09-20T13:22:05Z", "nextRung": 50,
      "escalationsFired": [], "atRisk": false
    },
    "resolution": { "state": "RUNNING", "...": "..." }
  },
  "incident": null,
  "analysisStatus": "READY",
  "latestDraftId": 9912,
  "_etag": "W/\"7\"",
  "createdAt": "2026-09-20T09:22:05Z",
  "updatedAt": "2026-09-20T11:01:12Z"
}
```

**Response headers:** `ETag: W/"7"` — the entity `version`. Required back as `If-Match` on every mutation.

**Field visibility by role — enforced in the response mapper, not the query:**
- `CUSTOMER` never receives `INTERNAL` messages, `priorityRationale`, `analysisStatus`, `latestDraftId`, or the internal SLA detail.
- Only `AGENT+` receives the AI panel fields at all.

**Errors:** `401` · **`404 TICKET_NOT_FOUND`** when the ticket belongs to another tenant, or to another customer — **`404`, never `403`.** A `403` would confirm the ticket exists, which is an information leak. `403` is reserved for "you are authenticated, this resource exists and is in your scope, but this *action* is not permitted."

---

#### POST `/api/v1/tickets/{id}/messages`

**Description:** Add a message. An agent's first `PUBLIC` message **stops the first-response SLA clock in the same transaction.**

**Request:**
```json
{
  "body":          "string (required) — 1–20000 chars",
  "visibility":    "string (optional) — PUBLIC | INTERNAL, default PUBLIC",
  "attachmentIds": "long[] (optional) — max 5",
  "fromDraftId":   "long (optional) — links this message to the AI draft it came from"
}
```

**Validation:**
- `visibility=INTERNAL` rejected with `403` for `CUSTOMER`
- `fromDraftId` must belong to this ticket and be `SHOWN`; rejected otherwise

**Success — `201 Created`:**
```json
{
  "id": 51003, "ticketId": 88213,
  "authorId": 1204, "authorName": "Arjun Mehta", "authorRole": "AGENT",
  "visibility": "PUBLIC", "body": "Thanks Priya — I can see the UPI reference...",
  "isFirstResponse": true,
  "slaEffect": { "firstResponse": "MET", "metAt": "2026-09-20T11:04:19Z" },
  "createdAt": "2026-09-20T11:04:19Z"
}
```

**The `slaEffect` field is the important one.** Writing the message and stopping the clock happen in **one transaction**, which is what closes the race against the SLA poller: an agent replying at the exact instant a breach would fire cannot produce both a first response and a breach. Returning the effect to the client means the UI does not have to re-fetch to find out.

**Errors:** `400` · `401` · `403 FORBIDDEN` (customer attempting `INTERNAL`) · `404` · `409 TICKET_CLOSED` (cannot post to a `CLOSED` ticket) · `422 INVALID_DRAFT_REFERENCE`

---

#### POST `/api/v1/tickets/{id}/assign`

**Description:** Assign a ticket. **This is the endpoint with the concurrency test behind it.**

**Request:**
```json
{
  "assigneeId": "long (optional) — omit or send \"me\" to self-assign",
  "force":      "boolean (optional, default false) — TEAM_LEAD+ only; reassign an already-assigned ticket"
}
```

**Success — `200 OK`:** the updated ticket summary with a new `ETag`.

**Errors:**

| Status | When | `errorCode` |
|---|---|---|
| 403 | An AGENT assigning to someone else | `FORBIDDEN` |
| 404 | Ticket not visible to caller | `TICKET_NOT_FOUND` |
| **409** | **Already assigned and `force` not set** | `ALREADY_ASSIGNED` |
| 409 | `If-Match` ETag stale | `VERSION_CONFLICT` |
| 422 | Target agent at `max_concurrent` or unavailable | `AGENT_AT_CAPACITY` |

**The mechanism behind the `409`:**
```sql
UPDATE ticket
   SET assignee_id = :agentId, status = 'ASSIGNED', version = version + 1
 WHERE id = :ticketId AND assignee_id IS NULL;
-- affected rows = 0 → someone else won → 409 ALREADY_ASSIGNED
```
A conditional update with an affected-row check, not a `SELECT` followed by an `UPDATE`. Two agents clicking "Assign to me" in the same millisecond produce exactly one `200` and one `409`. **There is an integration test that drives 20 concurrent requests and asserts exactly that distribution** — see [07 §Phase 9](07-DEV-PHASES.md).

---

#### POST `/api/v1/tickets/{id}/status`

**Description:** Drive the state machine. Illegal transitions are rejected by the server, not prevented by the UI.

**Request:** `{ "status": "string (required)", "reason": "string (required for WAITING_ON_CUSTOMER and PENDING_THIRD_PARTY)" }`

**The legal transition table** — this *is* the validation rule:

```
OPEN                → TRIAGED, ASSIGNED, CLOSED
TRIAGED             → ASSIGNED, CLOSED
ASSIGNED            → IN_PROGRESS, WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY, RESOLVED, OPEN
IN_PROGRESS         → WAITING_ON_CUSTOMER, PENDING_THIRD_PARTY, RESOLVED, ASSIGNED
WAITING_ON_CUSTOMER → IN_PROGRESS, RESOLVED, CLOSED          ← SLA clock PAUSES on entry
PENDING_THIRD_PARTY → IN_PROGRESS, RESOLVED                  ← SLA clock PAUSES on entry
RESOLVED            → CLOSED, OPEN (reopen)
CLOSED              → (terminal; reopen creates a transition to OPEN via /reopen)
```

**Success — `200 OK`:**
```json
{
  "id": 88213, "status": "WAITING_ON_CUSTOMER",
  "slaEffect": {
    "resolution": { "state": "PAUSED", "pausedAt": "2026-09-20T11:30:00Z",
                    "reason": "WAITING_ON_CUSTOMER",
                    "elapsedBusinessMinutesAtPause": 128 }
  },
  "_etag": "W/\"9\""
}
```

**Errors:** `409 ILLEGAL_TRANSITION` with the permitted targets in the problem detail:
```json
{
  "type": "https://resolveai.dev/errors/illegal-transition",
  "title": "Illegal status transition",
  "status": 409,
  "errorCode": "ILLEGAL_TRANSITION",
  "detail": "Cannot move from CLOSED to IN_PROGRESS",
  "allowedTransitions": [],
  "instance": "/api/v1/tickets/88213/status"
}
```

Returning the allowed set means a client can recover without hard-coding the state machine.

---

### 3.3 Triage & AI

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| GET | `/api/v1/tickets/{id}/analysis` | Triage signals + status | AGENT+ |
| POST | `/api/v1/tickets/{id}/retriage` | Re-drive after failure | AGENT+ |
| GET | `/api/v1/tickets/{id}/priority-rationale` | Signals → policy → result | AGENT+ |
| POST | `/api/v1/tickets/{id}/priority-override` | Override with a reason | AGENT+ |
| POST | `/api/v1/tickets/{id}/drafts` | Request a draft → `202` | AGENT+ |
| GET | `/api/v1/drafts/{id}` | Claims, verdicts, citations | AGENT+ |
| POST | `/api/v1/drafts/{id}/action` | Record what the agent did | AGENT+ |

---

#### GET `/api/v1/tickets/{id}/analysis`

**Description:** The async status resource that `POST /tickets` points at.

**Success — `200 OK`, still processing:**
```json
{ "ticketId": 88213, "status": "PROCESSING", "enqueuedAt": "2026-09-20T09:22:05Z",
  "retryAfterSeconds": 3 }
```

**Success — `200 OK`, ready:**
```json
{
  "ticketId": 88213, "status": "READY",
  "signals": {
    "category": "PAYMENT",
    "reportedImpact": "SINGLE_USER",
    "serviceDownClaimed": false,
    "dataLossClaimed": false,
    "paymentAffected": true,
    "linguisticUrgency": "HIGH",
    "extractedEntities": { "paymentMethod": "UPI", "orderRef": "«ORDER_1»" }
  },
  "modelConfidence": 0.91,
  "promptVersion": "triage@7",
  "modelId": "claude-haiku-4-5",
  "tokensIn": 1187, "tokensOut": 168,
  "costMicros": 412, "latencyMs": 2840,
  "completedAt": "2026-09-20T09:22:09Z"
}
```

**Success — `200 OK`, unavailable:**
```json
{
  "ticketId": 88213, "status": "UNAVAILABLE",
  "reason": "PROVIDER_ERROR",
  "attempts": 3,
  "lastError": "upstream 503 after fallback",
  "manualTriageRequired": true
}
```

**`200`, never `404` or `503`, for the `UNAVAILABLE` case.** The *analysis resource* exists and its state is "we tried and failed." That is a successful read of a legitimate state, and the ticket remains fully workable. Returning an error status here would make a degraded AI feature look like a broken API.

**Note the `extractedEntities.orderRef` value:** `«ORDER_1»`. The order reference was PII-redacted before the text left for the model, so the model never saw the real value and the placeholder is what comes back. Rehydration happens only when rendering for an authorised human.

---

#### GET `/api/v1/tickets/{id}/priority-rationale`

**Description:** Why this ticket has this priority. **The endpoint that makes the AI's role auditable.**

**Success — `200 OK`:**
```json
{
  "ticketId": 88213,
  "computedPriority": "P2",
  "policyVersion": "v3",
  "decidedAt": "2026-09-20T09:22:09Z",
  "inputs": {
    "fromModel": {
      "category": "PAYMENT", "reportedImpact": "SINGLE_USER",
      "paymentAffected": true, "linguisticUrgency": "HIGH",
      "serviceDownClaimed": false, "confidence": 0.91
    },
    "fromSystem": {
      "planTier": "PRO", "linkedIncident": null,
      "reopenCount": 0, "contractualOverride": null
    }
  },
  "rules": [
    { "rule": "BASE_FROM_IMPACT", "matched": true,  "effect": "P3",
      "note": "SINGLE_USER impact starts at P3" },
    { "rule": "PAYMENT_BUMP",     "matched": true,  "effect": "+1",
      "note": "paymentAffected raises one level" },
    { "rule": "PLAN_TIER_BUMP",   "matched": false, "effect": "none",
      "note": "PRO does not bump; ENTERPRISE would" },
    { "rule": "INCIDENT_INHERIT", "matched": false, "effect": "none" },
    { "rule": "REOPEN_BUMP",      "matched": false, "effect": "none",
      "note": "requires reopenCount >= 2" }
  ],
  "humanReadable": "P2 because the customer reports a payment problem affecting them individually, on a PRO plan.",
  "overridable": true
}
```

**Why this endpoint exists at all:** the `inputs` object separates `fromModel` from `fromSystem`, and `rules` shows the deterministic evaluation. When an agent overrides a priority, this structure makes it possible to tell whether **the model misread the ticket** or **the policy is wrong** — two completely different bugs with two completely different fixes. A design where the LLM simply returns `"priority": "P2"` makes that distinction unrecoverable.

---

#### POST `/api/v1/tickets/{id}/drafts`

**Description:** Request a grounded resolution draft. Async.

**Request:** `{ "instruction": "string (optional, max 500) — steer the draft, e.g. 'explain the refund timeline'" }`

**Success — `202 Accepted`:**
```json
{ "draftId": 9912, "status": "PENDING",
  "links": { "self": "/api/v1/drafts/9912" },
  "estimatedSeconds": 18 }
```

**Errors:**

| Status | When | `errorCode` |
|---|---|---|
| 409 | A draft is already pending for this ticket | `DRAFT_IN_PROGRESS` |
| 422 | Knowledge base empty for this tenant | `NO_KNOWLEDGE_BASE` |
| 429 | 10/min per agent | `RATE_LIMITED` |
| **402** | Tenant monthly AI budget exhausted | `AI_BUDGET_EXHAUSTED` |
| 503 | `external_model_allowed=false` and no local model configured | `AI_DISABLED_BY_POLICY` |

**`402 Payment Required`** is the honest code for a budget cap. It is rarely used, it is semantically exact, and it is distinguishable from `429` — which matters, because a client should retry after a `429` and must not after a `402`.

---

#### GET `/api/v1/drafts/{id}`

**Description:** The draft with per-claim verdicts. **The most important response body in the API.**

**Success — `200 OK`, shown:**
```json
{
  "id": 9912, "ticketId": 88213, "status": "SHOWN",
  "coverage": 1.0,
  "claims": [
    {
      "ordinal": 1,
      "text": "UPI payments that are debited without an order confirmation are auto-reversed within 5–7 business days.",
      "verdict": "SUPPORTED",
      "kept": true,
      "citations": [
        { "chunkId": 33401, "documentId": 1204, "documentTitle": "UPI payment failures",
          "source": "RUNBOOK", "charStart": 412, "charEnd": 578,
          "snippet": "...debited without a corresponding order are reversed by the PSP within 5–7 business days..." }
      ]
    },
    {
      "ordinal": 2,
      "text": "You can request a manual reversal by sharing the UPI reference number.",
      "verdict": "SUPPORTED", "kept": true,
      "citations": [ { "chunkId": 33409, "documentTitle": "Manual reversal process", "source": "ARTICLE", "...": "..." } ]
    },
    {
      "ordinal": 3,
      "text": "Your refund of ₹2,499 will be credited by 24 September.",
      "verdict": "FAILED_NUMERIC_CHECK",
      "kept": false,
      "rejectionReason": "Values ₹2,499 and 24 September do not appear in any cited span",
      "citations": []
    }
  ],
  "assembledText": "Hi Priya,\n\nUPI payments that are debited without an order confirmation are auto-reversed within 5–7 business days. You can request a manual reversal by sharing the UPI reference number.\n\n— Arjun",
  "unresolvedAspects": [
    "The customer asked whether they will be compensated for the delay — not covered by the knowledge base."
  ],
  "sources": [
    { "documentId": 1204, "title": "UPI payment failures", "source": "RUNBOOK", "tier": "AUTHORITATIVE" },
    { "documentId": 1290, "title": "Manual reversal process", "source": "ARTICLE", "tier": "AUTHORITATIVE" }
  ],
  "promptVersion": "draft@4", "modelId": "claude-sonnet-5",
  "tokensIn": 4102, "tokensOut": 288, "costMicros": 2840, "latencyMs": 19400,
  "createdAt": "2026-09-20T11:12:00Z"
}
```

**Three things to point at in this body:**

1. **Claim 3 is returned, not hidden.** It was dropped from `assembledText` (`kept: false`) but the agent can see that the model tried to invent a refund amount and a date, and why it was rejected. **Hiding rejections would make the verification mechanism invisible and therefore untrustworthy.** The rejection cost zero LLM calls — the numeric pre-filter caught it.

2. **`unresolvedAspects` is the most useful field for the agent.** A support agent's real question is not "what does the KB say" but *"what am I going to have to figure out myself?"* This also feeds knowledge-gap mining in v2.

3. **`tier: "AUTHORITATIVE"`** distinguishes a runbook from a resolved ticket. The agent should weigh *"the runbook says"* differently from *"someone did this once."*

**Success — `200 OK`, suppressed:**
```json
{
  "id": 9913, "ticketId": 88250, "status": "SUPPRESSED_LOW_COVERAGE",
  "coverage": 0.33,
  "claims": [ { "ordinal": 1, "verdict": "NOT_SUPPORTED", "kept": false, "...": "..." } ],
  "assembledText": null,
  "suppressionReason": "Only 1 of 3 claims was supported by retrieved evidence (coverage 0.33 < threshold 0.80). Escalating rather than showing a weakly-grounded draft.",
  "unresolvedAspects": ["No knowledge base coverage for refunds on cancelled subscriptions."],
  "recommendation": "ESCALATE_TO_HUMAN"
}
```

**`assembledText` is `null`, not a hedged draft.** The design position: **a confidently wrong answer is worse than no answer.** The refusal suite in CI asserts this path fires 100% of the time on the no-coverage fixtures.

---

#### POST `/api/v1/drafts/{id}/action`

**Description:** Record what the agent did. **This is the quality-measurement endpoint** and it must be called even on discard.

**Request:**
```json
{
  "action":    "string (required) — SENT_AS_IS | EDITED | DISCARDED",
  "finalText": "string (required when EDITED) — what was actually sent"
}
```

**Success — `201 Created`:**
```json
{ "draftId": 9912, "action": "EDITED", "editDistance": 47,
  "createdAt": "2026-09-20T11:14:02Z" }
```

`editDistance` is computed server-side (Levenshtein, `assembledText` vs `finalText`) — never trusted from the client.

**Errors:** `409 ACTION_ALREADY_RECORDED` (one terminal action per draft, `uq_draft_action`) · `422` (`finalText` missing for `EDITED`)

---

### 3.4 SLA

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| GET | `/api/v1/tickets/{id}/sla` | Both clocks with segments | AGENT+ |
| POST | `/api/v1/tickets/{id}/sla/pause` | Pause — idempotent | AGENT+ |
| POST | `/api/v1/tickets/{id}/sla/resume` | Resume — idempotent | AGENT+ |
| GET | `/api/v1/sla/at-risk` | Predicted breaches, ranked | AGENT+ |

---

#### GET `/api/v1/tickets/{id}/sla`

**Success — `200 OK`:**
```json
{
  "ticketId": 88213,
  "calendar": { "timezone": "Asia/Kolkata", "workingDays": [1,2,3,4,5],
                "dayStart": "09:00", "dayEnd": "18:00" },
  "clocks": [
    {
      "kind": "FIRST_RESPONSE",
      "state": "MET",
      "policyVersion": "v3",
      "targetBusinessMinutes": 240,
      "elapsedBusinessMinutes": 102,
      "metAt": "2026-09-20T11:04:19Z",
      "segments": [
        { "state": "RUNNING", "startedAt": "2026-09-20T09:22:09Z",
          "endedAt": "2026-09-20T11:04:19Z", "businessMinutes": 102, "pauseReason": null }
      ],
      "escalationsFired": []
    },
    {
      "kind": "RESOLUTION",
      "state": "PAUSED",
      "targetBusinessMinutes": 960,
      "elapsedBusinessMinutes": 128,
      "remainingBusinessMinutes": 832,
      "nextDeadlineAt": null,
      "nextRung": 50,
      "segments": [
        { "state": "RUNNING", "startedAt": "2026-09-20T09:22:09Z",
          "endedAt": "2026-09-20T11:30:00Z", "businessMinutes": 128, "pauseReason": null },
        { "state": "PAUSED", "startedAt": "2026-09-20T11:30:00Z",
          "endedAt": null, "businessMinutes": 0, "pauseReason": "WAITING_ON_CUSTOMER" }
      ],
      "escalationsFired": [],
      "prediction": {
        "predictedResolutionBusinessMinutes": 410,
        "basis": "p75 over 90 days for (PAYMENT, P2, Payments team), n=214",
        "atRisk": false
      }
    }
  ]
}
```

**The `segments` array is the whole design, exposed.** `elapsedBusinessMinutes` is a `SUM()` over `RUNNING` segments — there is no stored counter anywhere. `nextDeadlineAt` is `null` while paused, which is exactly why the poller (`WHERE state = 'RUNNING'`) skips it.

**The `prediction.basis` string is deliberate.** It names the statistic and the sample size, so the number is inspectable rather than magical. **This is not an LLM output** — it is a p75 window function over historical resolution times. Being able to say *"I considered using the model here and a percentile over 90 days of history is strictly better — faster, cheaper, more accurate, and explainable"* is one of the stronger moments available in an interview.

---

#### POST `/api/v1/tickets/{id}/sla/pause`

**Request:** `{ "reason": "string (required) — WAITING_ON_CUSTOMER | PENDING_THIRD_PARTY", "kinds": "string[] (optional, default [RESOLUTION])" }`

**Success — `200 OK`** with the updated clocks.

**Idempotency:** pausing an already-paused clock returns `200` with the existing state, **not an error**. The operation is naturally idempotent and the client should not have to check first.

**Errors:** `409 SLA_ALREADY_TERMINAL` (cannot pause a `MET` or `BREACHED` clock) · `422 INVALID_PAUSE_REASON`

**What happens underneath:** close the open `RUNNING` segment (`ended_at = NOW()`), insert a new `PAUSED` segment, set `state='PAUSED'` and `next_deadline_at=NULL` — all in one transaction. The partial unique index `uq_segment_open` means two concurrent pause requests cannot both insert an open segment; the loser gets a constraint violation which the service maps to a `200` replay, because the desired end state was reached either way.

---

#### GET `/api/v1/sla/at-risk`

**Query:** `teamId`, `withinBusinessMinutes` (default 120), `size` (default 25), `cursor`

**Success — `200 OK`:**
```json
{
  "data": [
    { "ticketId": 88190, "reference": "TKT-10405", "subject": "Cannot log in after password reset",
      "priority": "P1", "assignee": { "id": 1207, "fullName": "Neha Kulkarni" },
      "clockKind": "RESOLUTION",
      "remainingBusinessMinutes": 34,
      "predictedResolutionBusinessMinutes": 180,
      "riskScore": 0.94,
      "reason": "Predicted resolution (180 min, p75 for this class) exceeds remaining budget (34 min)",
      "nextDeadlineAt": "2026-09-20T12:06:00Z" }
  ],
  "pagination": { "size": 25, "nextCursor": null, "hasNext": false }
}
```

---

### 3.5 Incidents

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| GET | `/api/v1/incidents` | Board | AGENT+ |
| GET | `/api/v1/incidents/{id}` | Detail with linked tickets | AGENT+ |
| POST | `/api/v1/incidents/{id}/confirm` | `PROPOSED` → `CONFIRMED` | TEAM_LEAD+ |
| POST | `/api/v1/incidents/{id}/reject` | Reject with a reason | TEAM_LEAD+ |
| POST | `/api/v1/incidents/{id}/tickets` | Manually link | TEAM_LEAD+ |
| DELETE | `/api/v1/incidents/{id}/tickets/{ticketId}` | Detach | TEAM_LEAD+ |
| POST | `/api/v1/incidents/{id}/updates` | Publish → fan-out | TEAM_LEAD+ |
| POST | `/api/v1/incidents/{id}/resolve` | Resolve incident + tickets | TEAM_LEAD+ |

---

#### GET `/api/v1/incidents/{id}`

**Success — `200 OK`:**
```json
{
  "id": 412, "reference": "INC-204",
  "title": "UPI and card payment failures at checkout",
  "summary": "Multiple customers report payments debited without order confirmation, starting 14:02 IST.",
  "status": "CONFIRMED",
  "detection": {
    "method": "CLUSTER",
    "clusterSizeAtDetection": 38,
    "arrivalRateMultiple": 12.6,
    "baselineNote": "Baseline 3 tickets for Friday 14:00, 4-week average",
    "gateThresholds": { "minClusterSize": 5, "minRateMultiple": 3.0, "windowMinutes": 30 },
    "firstTicketAt": "2026-09-20T14:02:11Z",
    "detectedAt":    "2026-09-20T14:02:48Z",
    "timeToDetectSeconds": 37
  },
  "titleGeneratedBy": "claude-haiku-4-5",
  "confirmedBy": { "id": 1198, "fullName": "Sana Qureshi" },
  "confirmedAt": "2026-09-20T14:04:02Z",
  "linkedTicketCount": 38,
  "linkedTickets": [
    { "ticketId": 88301, "reference": "TKT-10461", "subject": "card declined at checkout",
      "linkConfidence": 0.91, "linkedAt": "2026-09-20T14:02:48Z", "linkedBy": null }
  ],
  "updates": [
    { "id": 77, "body": "We have identified an issue with our payment gateway...",
      "visibility": "PUBLIC", "authorName": "Sana Qureshi",
      "publishedAt": "2026-09-20T14:09:00Z",
      "delivery": { "total": 38, "sent": 38, "pending": 0, "failed": 0 } }
  ],
  "_etag": "W/\"4\""
}
```

**The `detection` object exposes the deterministic gate's evidence.** `arrivalRateMultiple: 12.6` against `minRateMultiple: 3.0` is the record of *why* this was proposed — and it makes clear that a statistical check, not a model, made that call. `titleGeneratedBy` is a separate field precisely to show the model's contribution was the title and nothing else. When it is `null`, the title was templated because the LLM was unavailable.

`timeToDetectSeconds: 37` is a headline metric and it comes straight out of the schema.

---

#### POST `/api/v1/incidents/{id}/confirm`

**Description:** Confirm a proposed incident. **Also writes a label to the evaluation set.**

**Request:** `{ "pauseResolutionClocks": "boolean (optional, default true)" }`

**Success — `200 OK`:**
```json
{
  "id": 412, "status": "CONFIRMED",
  "effects": {
    "ticketsLinked": 38,
    "resolutionClocksPaused": 38,
    "firstResponseClocksUnaffected": 38,
    "evalLabelRecorded": true
  },
  "_etag": "W/\"5\""
}
```

**`firstResponseClocksUnaffected` is a deliberate product decision, surfaced in the response.** When a ticket joins an incident, the *resolution* clock pauses — resolution now depends on the incident, not the agent. The *first-response* clock keeps running, because **the customer still deserves an acknowledgement even during an outage.** There is no universally correct answer here; the response makes the choice visible rather than hiding it, and it is a good thing to be asked about.

**Errors:** `409 INVALID_INCIDENT_STATE` (only `PROPOSED` can be confirmed) · `409 VERSION_CONFLICT` · `403`

---

#### POST `/api/v1/incidents/{id}/updates`

**Description:** Publish an update. Fans out to every linked ticket as N independent, individually-retryable deliveries.

**Headers:** `Idempotency-Key` **required** — this sends messages to real customers.

**Request:** `{ "body": "string (required) 1–5000", "visibility": "PUBLIC | INTERNAL (default INTERNAL)" }`

**Success — `202 Accepted`:**
```json
{
  "updateId": 77, "incidentId": 412, "visibility": "PUBLIC",
  "fanout": { "total": 38, "status": "QUEUED" },
  "links": { "deliveries": "/api/v1/incidents/412/updates/77/deliveries" },
  "publishedAt": "2026-09-20T14:09:00Z"
}
```

**`202` because the fan-out is queued**, not complete. The `deliveries` link is the status resource.

**`GET /incidents/{id}/updates/{updateId}/deliveries` — `200 OK`:**
```json
{
  "updateId": 77,
  "summary": { "total": 38, "sent": 37, "pending": 0, "failed": 1 },
  "failures": [
    { "ticketId": 88322, "attempts": 3, "lastError": "notification insert failed: deadlock detected" }
  ]
}
```

**Per-ticket delivery rows are what make this endpoint honest.** Delivery 23 failing means ticket 88322 retries — the other 37 customers are not re-notified. `uq_delivery (incident_update_id, ticket_id)` guarantees that a redelivered outbox event cannot double-message a customer.

---

### 3.6 Knowledge Base

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/knowledge/documents` | Create → `202` async index | ADMIN |
| GET | `/api/v1/knowledge/documents` | List, paginated | AGENT+ |
| GET | `/api/v1/knowledge/documents/{id}` | Detail with chunks | AGENT+ |
| DELETE | `/api/v1/knowledge/documents/{id}` | Soft delete + deindex | ADMIN |
| POST | `/api/v1/knowledge/reindex` | Re-embed changed documents | ADMIN |
| GET | `/api/v1/knowledge/search` | Hybrid search — also the retrieval debugger | AGENT+ |

---

#### GET `/api/v1/knowledge/search`

**Description:** Hybrid retrieval. Used by the agent panel **and as the retrieval debugging tool** — the score breakdown is the reason it returns more than a ranked list.

**Query:** `q` (required, 1–500) · `k` (default 10, max 50) · `source` (filter by tier) · `explain` (boolean, default false)

**Success — `200 OK` with `explain=true`:**
```json
{
  "query": "upi payment debited no order",
  "kbVersion": 41,
  "strategy": "HYBRID_RRF",
  "timings": { "lexicalMs": 8, "vectorMs": 14, "fusionMs": 1, "totalMs": 23 },
  "results": [
    {
      "chunkId": 33401, "documentId": 1204,
      "documentTitle": "UPI payment failures", "source": "RUNBOOK", "tier": "AUTHORITATIVE",
      "text": "Payments debited without a corresponding order are reversed by the PSP within 5–7 business days...",
      "charStart": 412, "charEnd": 578,
      "scores": {
        "lexicalRank": 1, "lexicalScore": 0.412,
        "vectorRank": 3,  "vectorScore": 0.871,
        "rrfScore": 0.0325,
        "tierBoost": 1.15,
        "finalScore": 0.0374
      }
    },
    {
      "chunkId": 33409, "documentId": 1290,
      "documentTitle": "Manual reversal process", "source": "ARTICLE", "tier": "AUTHORITATIVE",
      "scores": { "lexicalRank": 7, "lexicalScore": 0.201,
                  "vectorRank": 1, "vectorScore": 0.903,
                  "rrfScore": 0.0303, "tierBoost": 1.15, "finalScore": 0.0348 }
    }
  ],
  "cacheHit": false
}
```

**The score breakdown is the point.** Chunk 33401 ranked **1st lexically** but only **3rd by vector**; 33409 was the reverse. Neither retrieval method alone would have surfaced both at the top — which is precisely the argument for hybrid search, visible as data rather than asserted. When an interviewer asks *"why not just use semantic search?"*, this response is the answer: **exact tokens like "UPI" and error codes are lexical signals that embeddings blur, and paraphrases are semantic signals that keyword matching misses.**

`kbVersion` is in the response because it is part of the retrieval cache key — any KB edit makes previously cached results unreachable automatically, with no explicit eviction.

---

#### POST `/api/v1/knowledge/reindex`

**Request:** `{ "force": "boolean (optional, default false) — re-embed even unchanged documents" }`

**Success — `202 Accepted`:**
```json
{ "jobId": "reindex-2026-09-20T14:40:00Z",
  "documentsQueued": 12, "documentsSkipped": 48,
  "skipReason": "content_sha256 unchanged",
  "estimatedCostMicros": 14200 }
```

**`documentsSkipped` with the reason is deliberate.** Reindex cost is proportional to *changed* documents because `content_sha256` lets the indexer skip the rest. `force: true` exists but reports its estimated cost up front, because a forced full reindex on a large corpus is the single easiest way to burn the monthly AI budget by accident.

---

### 3.7 Admin

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| GET / PUT | `/api/v1/admin/sla-policies` | Effective-dated SLA policy | ADMIN |
| GET / PUT | `/api/v1/admin/calendar` | Working hours, timezone, **and the holiday list** — one payload, since business-hours arithmetic needs both together | ADMIN |
| GET / PUT | `/api/v1/admin/ai-policy` | External model, budget, retention | ADMIN |
| GET | `/api/v1/admin/ai/usage` | Tokens and cost by prompt version | ADMIN |
| GET | `/api/v1/admin/eval/runs` | Accuracy over prompt versions | ADMIN |
| GET | `/api/v1/admin/audit` | Filterable audit trail | ADMIN |
| GET | `/api/v1/admin/users` | User and team management | ADMIN |
| GET | `/api/v1/admin/outbox/dead` | Dead-letter queue | ADMIN |
| POST | `/api/v1/admin/outbox/{id}/retry` | Re-drive a dead event | ADMIN |

---

#### PUT `/api/v1/admin/sla-policies`

**Description:** Update an SLA policy. **Creates a new effective-dated row — never mutates the existing one.**

**Request:**
```json
{
  "priority": "P1", "planTier": "ENTERPRISE",
  "firstResponseMinutes": 30, "resolutionMinutes": 240,
  "escalationRungs": [50, 75, 90, 100],
  "effectiveFrom": "2026-10-01T00:00:00Z"
}
```

**Success — `200 OK`:**
```json
{
  "created": { "id": 91, "versionLabel": "v4", "effectiveFrom": "2026-10-01T00:00:00Z",
               "effectiveTo": null },
  "superseded": { "id": 74, "versionLabel": "v3", "effectiveTo": "2026-10-01T00:00:00Z" },
  "note": "Existing SLA records keep policy v3. Only tickets created after the effective date use v4."
}
```

**The `note` field states the behaviour that would otherwise surprise an admin.** Existing `sla_record` rows snapshot `target_minutes` and `policy_version` at creation, so tightening an SLA does not retroactively breach tickets that were already in flight. That is a correctness property of effective dating, and telling the admin about it prevents a support ticket about your support system.

**Errors:** `409 OVERLAPPING_POLICY` (enforced by `uq_sla_policy_live`) · `422 EFFECTIVE_DATE_IN_PAST`

---

#### GET `/api/v1/admin/ai/usage`

**Query:** `from`, `to`, `groupBy` (`promptVersion` | `model` | `day`)

**Success — `200 OK`:**
```json
{
  "period": { "from": "2026-09-01", "to": "2026-09-20" },
  "budget": { "monthlyMicros": 5000000, "spentMicros": 1842300, "remainingPct": 63.2,
              "projectedMonthEndMicros": 2763450, "status": "OK" },
  "totals": { "calls": 4182, "tokensIn": 5120400, "tokensOut": 611200,
              "costMicros": 1842300, "cacheHitRate": 0.34 },
  "breakdown": [
    { "promptVersion": "triage@7", "model": "claude-haiku-4-5", "calls": 3011,
      "costMicros": 412800, "avgLatencyMs": 2740, "escalationRate": 0.06 },
    { "promptVersion": "draft@4", "model": "claude-sonnet-5", "calls": 604,
      "costMicros": 1189200, "avgLatencyMs": 18900, "suppressionRate": 0.11 },
    { "promptVersion": "entailment@2", "model": "claude-haiku-4-5", "calls": 1812,
      "costMicros": 186400, "avgLatencyMs": 810, "cacheHitRate": 0.61 }
  ]
}
```

This endpoint is where several README numbers come from: cost per ticket, cache hit rate, escalation rate, and draft suppression rate.

---

#### GET `/api/v1/admin/eval/runs`

**Success — `200 OK`:**
```json
{
  "data": [
    {
      "id": 88, "suite": "GROUNDING", "promptVersion": "draft@4",
      "model": "claude-sonnet-5", "gitSha": "a3f19c2...",
      "passed": true,
      "metrics": {
        "claimLevelGroundedness": 0.94,
        "responseLevelGroundedness": 0.99,
        "citationPrecision": 0.91,
        "claimsEvaluated": 412
      },
      "baseline": { "claimLevelGroundedness": 0.91 },
      "delta": { "claimLevelGroundedness": "+0.03" },
      "startedAt": "2026-09-19T22:10:00Z", "finishedAt": "2026-09-19T22:18:41Z"
    },
    {
      "id": 87, "suite": "REFUSAL", "promptVersion": "draft@4",
      "passed": true,
      "metrics": { "refusalRate": 1.0, "casesEvaluated": 20 },
      "note": "Gate requires 1.0 — a confidently wrong answer is worse than no answer."
    }
  ],
  "pagination": { "size": 25, "nextCursor": null, "hasNext": false }
}
```

**Reporting both `claimLevelGroundedness: 0.94` and `responseLevelGroundedness: 0.99` side by side is the point.** The gap between them *is* the finding — response-level averaging hides unsupported claims inside otherwise-good answers, which is exactly the failure mode documented in the 2026 RAG-faithfulness research (a metric reading 0.92 while a claim-level audit found 30% hallucination). Showing both makes the measurement honest and makes the design decision legible.

---

### 3.8 Attachments, Notifications, Teams & Agent Profile

> **Added in Phase 2 Task 2.** The [design trace](../design-trace.md) found four tables
> reachable from no endpoint: `attachment` was referenced by `attachmentIds` in three
> request bodies but never obtainable; `notification` was written by the SLA poller and the
> fan-out worker and read by nobody, while the UI showed an unread bell; `team.skills[]`
> drove routing but could only be changed by editing the database; and `agent_profile.is_available`
> was read on every routing decision and writable by no one.

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/v1/attachments` | Reserve an attachment, get a presigned upload URL | Any |
| POST | `/api/v1/attachments/{id}/complete` | Confirm upload; server verifies magic bytes and size | Any |
| GET | `/api/v1/attachments/{id}/url` | Short-TTL presigned download URL | Any (ownership-checked) |
| GET | `/api/v1/notifications` | Own notifications, cursor-paginated, `unreadOnly` filter | Any |
| GET | `/api/v1/notifications/unread-count` | Badge count — cheap, polled | Any |
| POST | `/api/v1/notifications/read` | Mark listed ids, or all, as read | Any |
| GET | `/api/v1/admin/teams` | List teams with skills and member counts | AGENT+ |
| POST | `/api/v1/admin/teams` | Create a team | ADMIN |
| PUT | `/api/v1/admin/teams/{id}` | Update name, `skills[]`, default flag | ADMIN |
| PUT | `/api/v1/agents/me/availability` | Agent sets own `is_available` and shift | AGENT+ |
| PUT | `/api/v1/admin/agents/{userId}/capacity` | Admin sets `max_concurrent` | ADMIN |

#### POST `/api/v1/attachments`

**Request:** `{ "filename": "string (required, ≤255)", "mimeType": "string (required)", "byteSize": "long (required, ≤10485760)" }`

**Success — `201`:**
```json
{
  "id": 9021,
  "uploadUrl": "https://minio.local/resolveai-attachments/a3f1…?X-Amz-Expires=300",
  "storageKey": "a3f19c2e-7b41-4d0a-9e88-1c2f3d4e5a6b",
  "expiresInSeconds": 300
}
```

**Two security properties, both non-negotiable:**
- `storageKey` is a **generated UUID**, never the user's filename. A filename-derived path
  invites traversal, and a guessable one invites IDOR.
- The declared `mimeType` is **not trusted**. `/complete` re-reads the object and verifies
  **actual magic bytes**; a `.exe` renamed `.png` is rejected there, not here.

**Errors:** `400 VALIDATION_ERROR` · `413 ATTACHMENT_TOO_LARGE` · `415 UNSUPPORTED_MEDIA_TYPE` (extension allow-list) · `429`

#### POST `/api/v1/attachments/{id}/complete`

No body. Server `HEAD`s the object, verifies size and magic bytes, computes `content_sha256`,
marks the row usable. Only then may the id appear in `attachmentIds`.

**Errors:** `409 ATTACHMENT_NOT_UPLOADED` · `415 CONTENT_TYPE_MISMATCH` (magic bytes disagree with the declared type) · `413`

#### GET `/api/v1/notifications`

**Query:** `cursor`, `size` (default 20, max 50), `unreadOnly` (default `false`), `kind`

**Success — `200`:**
```json
{
  "data": [
    { "id": 8812, "kind": "SLA_ESCALATION",
      "title": "TKT-10405 is at 75% of its resolution SLA",
      "body": "34 business minutes remaining. Assigned to Neha Kulkarni.",
      "linkUrl": "/tickets/88190", "readAt": null,
      "createdAt": "2026-09-20T11:32:00Z" }
  ],
  "pagination": { "size": 20, "nextCursor": "eyJj…", "hasNext": true }
}
```

Scoped to `recipient_id = me`, always — a notification is never visible to another user,
including an admin. Served by `idx_notification_unread`.

#### GET `/api/v1/notifications/unread-count`

`{ "unreadCount": 3 }`. Separate from the list because the bell polls it every 30s and must
not pay for row fetches. Backed by the partial index, so cost is proportional to **unread**
rows rather than total.

#### POST `/api/v1/notifications/read`

`{ "ids": [8812, 8813] }` or `{ "all": true }` → `204`. Idempotent: re-marking a read
notification is a no-op, not an error.

#### PUT `/api/v1/agents/me/availability`

**Request:** `{ "isAvailable": true, "shiftStart": "09:00", "shiftEnd": "18:00" }`

An agent may set only their **own** availability — `403` otherwise, even for `TEAM_LEAD`.
Going unavailable does **not** reassign existing tickets; it removes the agent from future
`claimLeastLoadedAgent` selection only. Reassignment stays a deliberate human action.

#### PUT `/api/v1/admin/teams/{id}`

**Request:** `{ "name": "Payments", "skills": ["PAYMENT","BILLING"], "isDefault": false }`

**Validation:** every skill must be a valid `Category`; **at most one team per tenant may be
`isDefault`** — enforced by `idx_team_default` plus a service check that clears the previous
default in the same transaction. A tenant with **no** default team leaves unroutable tickets
unassigned rather than erroring ([11 T24](../planning/11-TASK-BREAKDOWN-PHASE-6.md)), so
`isDefault: false` on the last default is permitted and warned about, not rejected.

---

### 3.9 Tables with no endpoint — deliberate

| Table | Why |
|---|---|
| `tenant` | No self-serve tenant signup in scope. Onboarding is an operational procedure, not a public endpoint. |
| `prompt_version` | **Prompts ship with the code.** A prompt change is a reviewable diff and a CI-gated deploy ([11 T14](../planning/11-TASK-BREAKDOWN-PHASE-6.md)), not a runtime edit. A UI to edit prompts would let someone bypass the evaluation gate, which is the one thing that must not be bypassable. |
| `eval_case` | Test fixtures. They arrive from migrations, hand-labelling, and `/incidents/{id}/confirm`+`reject`. A UI to edit your own test data defeats the point of having it. |
| `tenant_sequence`, `pii_redaction_map`, `idempotency_record`, `outbox_event`* | Internal mechanics. (*`outbox_event` is readable through `/admin/outbox/dead` only.) |

---

## STEP 4 — API-Wide Standards

### Versioning

```
Base: /api/v1/
Every endpoint is prefixed. The whole API is versioned, never individual endpoints.
Breaking change → /api/v2/, with v1 supported for 6 months.

Non-breaking (no version bump): adding an optional request field,
adding a response field, adding an endpoint, adding an enum value
a client may safely ignore.

Breaking (version bump): removing or renaming a field, changing a type,
adding a required request field, changing a status code,
removing an enum value a client may receive.
```

### Pagination

**Two strategies, chosen per endpoint rather than uniformly.** Uniformity would be simpler; correctness matters more.

**Cursor** — for `GET /tickets`, `/incidents`, `/sla/at-risk`, `/admin/audit`:
```
GET /api/v1/tickets?cursor=eyJjIjoi...&size=25
```
```json
{ "data": [...], "pagination": { "size": 25, "nextCursor": "eyJjIjoi...", "hasNext": true } }
```
The cursor is base64 `{"c":"<created_at>","i":<id>}`. **Stable under concurrent mutation**, which offset is not: resolving a ticket on page 1 shifts everything up and `OFFSET` silently skips a row the agent never sees. Also does not degrade at high offsets. No `totalElements` — counting a filtered set on every page is expensive and nobody uses the number.

**Offset** — for `/knowledge/documents`, `/admin/users`, `/admin/eval/runs`:
```json
{ "data": [...], "pagination": { "page": 1, "size": 20, "totalElements": 143,
                                 "totalPages": 8, "hasNext": true, "hasPrevious": false } }
```
Small, slow-changing, admin-facing sets where a page count is genuinely useful and drift is not a concern. Spring `Pageable` maps directly.

**Say this in an interview:** *"I used cursor pagination on the queues and offset on the admin lists. Offset on a live queue isn't just slow — it's wrong, because rows leave the filtered set while you're paging."*

### Standard error format — RFC 7807

Every error, across the entire API:

```json
{
  "type":      "https://resolveai.dev/errors/version-conflict",
  "title":     "Version conflict",
  "status":    409,
  "errorCode": "VERSION_CONFLICT",
  "detail":    "Ticket 88213 was modified by another user. Reload and retry.",
  "instance":  "/api/v1/tickets/88213/assign",
  "timestamp": "2026-09-20T11:42:07Z",
  "traceId":   "0af7651916cd43dd8448eb211c80319c"
}
```

`Content-Type: application/problem+json`. Validation errors add an `errors` array:

```json
{
  "type": "https://resolveai.dev/errors/validation",
  "title": "Validation failed", "status": 400, "errorCode": "VALIDATION_ERROR",
  "instance": "/api/v1/tickets", "timestamp": "...", "traceId": "...",
  "errors": [
    { "field": "subject", "code": "NotBlank", "message": "Subject is required" },
    { "field": "body", "code": "Size", "message": "Body must be 1–20000 characters",
      "rejectedValue": "<27431 chars>" }
  ]
}
```

- **`errorCode` is the machine-readable contract.** Clients switch on it, never on `detail`, which is human prose and may change.
- **`traceId` is the OpenTelemetry trace id**, so a user reporting an error gives you a string that finds the exact request across the async worker boundary.
- **`rejectedValue` is truncated and redacted** — never echo a 27KB body or anything that might contain PII back into an error response or a log.

Implemented with one `@RestControllerAdvice`. **No raw Spring Boot error page ever reaches a client** — including on `500`, where `detail` becomes a generic string and the real cause goes to the log with the same `traceId`.

### Common headers

**Requests:**
```
Content-Type:    application/json
Authorization:   Bearer <jwt>              all protected endpoints
Idempotency-Key: <uuid>                    POST /tickets, POST /incidents/{id}/updates
If-Match:        W/"7"                     all ticket and incident mutations
```

**Responses:**
```
Content-Type:    application/json  |  application/problem+json
ETag:            W/"7"                     on any versioned resource
X-Request-Id:    <uuid>
traceparent:     00-<trace>-<span>-01      W3C trace context
Retry-After:     <seconds>                 on 429 and 503
RateLimit-Limit / RateLimit-Remaining / RateLimit-Reset
Cache-Control:   no-store                  on everything — this is all tenant data
```

**Tokens are never accepted as query parameters.** They end up in access logs, browser history and `Referer` headers. `Authorization` header only.

### DTO rules

**No JPA entity ever leaves a controller.** Returning an entity leaks the schema, triggers lazy-loading exceptions after the transaction closes, and makes every field addition a breaking API change.

```
CreateTicketRequest      → in,  POST /tickets
UpdateTicketRequest      → in,  PATCH /tickets/{id}
TicketSummaryResponse    → out, list — no body, no messages
TicketDetailResponse     → out, single — full thread, SLA, AI panel
TicketCustomerResponse   → out, single, CUSTOMER role — internal fields absent
```

**Note the last one.** Role-based field visibility is a **different DTO**, not conditional null-setting on a shared one. A shared DTO with `if (role != CUSTOMER) dto.setInternalNotes(...)` is one forgotten branch away from leaking internal notes to a customer. Separate types make the leak a compile error.

MapStruct for the mapping — already on the resume, and the generated code is inspectable.

### Rate limiting

| Scope | Limit | Failure mode |
|---|---|---|
| `POST /auth/register` | 5/hour per IP | 429 |
| `POST /auth/login` | 10/min per IP, 5/min per email | 429 |
| `POST /tickets` | 20/min per user, 200/min per tenant | 429 |
| `POST /tickets/{id}/drafts` | 10/min per agent | 429 |
| `POST /knowledge/reindex` | 2/hour per tenant | 429 |
| All other authenticated | 300/min per user | 429 |

Sliding window in Redis. **Fails open** — if Redis is down, requests pass. For a support system, availability beats throttling; the alternative is an outage in your outage-management tool. `RateLimit-*` headers on every response so a client can self-throttle rather than discovering the limit by hitting it.

---

## STEP 5 — REST Convention Checklist

| Convention | Status | Notes |
|---|---|---|
| URLs use nouns, not verbs | ⚠️ **Deviation — accepted** | See below |
| URLs use plural nouns | ✅ | `/tickets`, `/incidents`, `/drafts` |
| Nesting ≤ 1 level | ✅ | `/incidents/{id}/updates/{uid}/deliveries` is 2 — accepted, since a delivery has no identity outside its update |
| HTTP method matches the operation | ✅ | |
| `201` for creates | ✅ | `/auth/register`, `/messages`, `/drafts/{id}/action` |
| `202` for async | ✅ | `POST /tickets`, `/drafts`, `/incidents/{id}/updates`, `/knowledge/reindex` |
| `204` for deletes | ✅ | `DELETE /knowledge/documents/{id}`, `DELETE /incidents/{id}/tickets/{tid}` |
| `401` vs `403` used correctly | ✅ | And `404` for cross-tenant, deliberately — see below |
| Query params for filter/sort/page | ✅ | Never path segments |
| Consistent JSON casing | ✅ | `camelCase` everywhere, including enum-valued strings as `SCREAMING_SNAKE` |
| ISO 8601 dates, UTC, `Z` suffix | ✅ | Never epoch millis, never `dd/mm/yyyy` |

### Deviation 1 — verb-like sub-resources on tickets

`POST /tickets/{id}/assign`, `/status`, `/resolve`, `/reopen`, `/retriage`, `/sla/pause`, `/incidents/{id}/confirm`.

**Strict REST would say** these are state changes, therefore `PATCH /tickets/{id}` with a body.

**Rejected, for three concrete reasons:**

1. **Distinct authorization.** `assign` is `AGENT+`, `confirm` is `TEAM_LEAD+`, `retriage` is `AGENT+`. Collapsing them into one `PATCH` means one method-security annotation that has to branch on which field is present — the kind of authorization logic that grows a hole.
2. **Distinct side effects.** `/status` may pause an SLA clock; `/assign` takes a row lock on an agent profile; `/resolve` queues the ticket for knowledge-base indexing. These are not field updates, they are operations with transactional consequences.
3. **Distinct error vocabularies.** `409 ALREADY_ASSIGNED` and `409 ILLEGAL_TRANSITION` are different failures with different client recovery paths. One endpoint returning both makes the contract vaguer, not cleaner.

**The rule applied:** a sub-resource `POST` when the operation has its own authorization, side effects and failure modes; `PATCH` for plain field updates. `PATCH /tickets/{id}` exists and handles exactly that — subject, category, team.

This is a common, defensible pattern (Stripe, GitHub and Kubernetes all do it), and being able to state *why* rather than apologising for it is the point.

### Deviation 2 — `404` where `403` might be expected

A `CUSTOMER` requesting another customer's ticket, or any user requesting another tenant's ticket, receives **`404 TICKET_NOT_FOUND`**.

`403` would confirm the resource exists, turning every ID into a probe. `403` is reserved for "you are authenticated, this resource is in your scope, but this *action* is not permitted" — e.g. an `AGENT` calling `/incidents/{id}/confirm`.

### Deviation 3 — list responses are wrapped, not bare arrays

`{ "data": [...], "pagination": {...} }` rather than a top-level array. Pagination metadata has to go somewhere, headers are awkward for clients, and a top-level JSON array is a (historically) awkward response body. Applied consistently to every list endpoint.

### One genuine violation, fixed

**Found:** an early draft had `GET /api/v1/tickets/{id}/getAnalysis`.
**Fixed to:** `GET /api/v1/tickets/{id}/analysis`. `GET` already means "get"; the verb was redundant.

---

## STEP 6 — Next Steps

> **API design complete ✅**
>
> **Right now (Design Phase):**
> - Check every MVP feature in [02 §3](02-PROJECT-PLAN.md) against this contract — each one must have the endpoints it needs
> - Write these into an OpenAPI 3.1 spec (`springdoc-openapi` generates it from annotations, but write the schema examples by hand — generated examples are useless to a frontend developer)
> - Import into Postman and build the collection now. It becomes your smoke-test suite in Phase 10.
>
> **When you start Phase 4 (Auth):**
> - Implement `/auth/register`, `/auth/login`, `/auth/refresh` and the JWT filter **first**. Every other endpoint depends on them.
> - Build the `@RestControllerAdvice` at the same time — every subsequent endpoint should return RFC 7807 from its first line of code, not be retrofitted.
>
> **When you start Phases 5–8:**
> - One feature group at a time, top to bottom: tickets, then SLA, then triage, then knowledge, then incidents
> - **Create the DTOs before the controller logic.** The role-specific response types are not optional — they are what makes a field-visibility leak a compile error.
> - Use the Spring Boot mapping notes on each endpoint
>
> **Next:** run the **ui-ux-designer skill** → [06 — UI/UX Design](06-UI-UX-DESIGN.md). Every screen maps directly to the endpoints above.
