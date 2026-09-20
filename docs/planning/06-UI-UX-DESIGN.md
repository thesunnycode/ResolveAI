# 06 — UI/UX Design

> Skill: `ui-ux-designer` · Input: [02 — Project Plan](02-PROJECT-PLAN.md) + [05 — API Contract](05-API-CONTRACT.md)
> Output: every screen, state, token and component decided before any JSX is written

---

## 1. PROJECT UI ANALYSIS

**App type:** An internal operations dashboard with a small attached customer portal. Two audiences with opposite needs sharing one codebase.

**Primary user goal:** An agent opens the app and needs to answer one question in under three seconds — *"what do I work on next, and is it part of something bigger?"* Everything else in the interface is secondary to that.

**Key design challenge — and it is the whole brief:**

> The agent's ticket detail screen has to show **five competing things at once**: the conversation, the SLA clocks, the AI triage panel, the draft with its per-claim verdicts, and the incident banner. Each has a legitimate claim on attention. Get the hierarchy wrong and it becomes an unusable wall of panels — the exact failure mode that makes internal tools hated.

The resolution is a strict three-tier hierarchy, applied without exception: **the conversation is the page**, SLA and incident status are **persistent thin strips** (always visible, never large), and the AI panel is a **collapsible right rail** that is present but never competes with the thread.

**Design direction:** Dense, calm, professional. This is a tool someone stares at for eight hours. That rules out large type, generous whitespace, decorative colour, and animation. It rules *in* high information density, muted surfaces, and colour used **only** to encode meaning — priority, SLA risk, claim verdict. If something is coloured, it means something.

Explicit non-goal: this should not look like a marketing site. No hero sections, no gradients, no illustrations.

**Screens derived:** **13 total.** Arrived at by taking every `GET` in [05](05-API-CONTRACT.md) that returns a list or a detail (9 screens), adding the auth screens (2), adding the two forms that have no natural host screen (2). Of these, **9 are MVP** and 4 are deferred — marked throughout.

**Scope discipline, stated up front:** the frontend exists to demonstrate the backend. The budget is Tailwind, no component library, no animation, no design system beyond the tokens in §5. Every hour spent here is an hour not spent on the thing you will be interviewed about.

---

## 2. USER FLOW MAP

```
                              [Login]  ◄──── unauthenticated redirect
                                 │              (with ?next= return URL)
                    ┌────────────┼────────────┐
                    │            │            │
              "Register"    submit ok    "Forgot?" ──→ [Reset Request] ⏳
                    │            │
                    ▼            │
              [Register]         │
                    │            │
              submit ok ─────────┤
                                 │
                    ┌────────────┴────────────────────┐
                    │        role routing             │
                    ▼                                 ▼
        ═══ CUSTOMER ═══                    ═══ AGENT / LEAD / ADMIN ═══

      🔒 [My Tickets]                        🔒 [Agent Queue]  ◄── default
             │                                      │
             ├─ "New ticket" ──→ 🔒 [New Ticket]     ├─ click row ──┐
             │                        │              │              │
             │                   submit ok           ├─ nav ──→ 🔒 [Incident Board]
             │                        │              │              │  │
             └─ click row ────────────┤              │              │  └─ click ──→ 🔒 [Incident Detail]
                                      ▼              │              │                     │
                        🔒 [Ticket Detail (Customer)] │              │        ┌────────────┤
                                      │              │              │        │            │
                                  reply ─┘            │              │   "Confirm"   "Publish update"
                                                      │              │        │            │
                                                      │              │        └──→ (stays on page,
                                                      │              │              fan-out status)
                                                      ▼              │
                                        🔒 [Ticket Detail (Agent)] ◄─┘
                                                      │
                            ┌─────────────────────────┼──────────────────────┐
                            │                         │                      │
                     "Suggest reply"            "Assign to me"         incident banner
                            │                         │                      │
                            ▼                    (inline, stays)             ▼
                   right rail: draft                                🔒 [Incident Detail]
                   with claim verdicts
                            │
                    Send / Edit / Discard
                            │
                            └──→ (stays on page, thread updates)

        ═══ ADMIN only ═══
      🔒👑 [Knowledge Base] ──"Add"──→ 🔒👑 [KB Document Form]
      🔒👑 [Settings]  (SLA policy · calendar · AI policy — three tabs)
      🔒👑 [Eval Dashboard]
      🔒👑 [Audit Log] ⏳

      All roles:  🔒 [Profile / Password]  (from the avatar menu)

  🔒 = requires auth    👑 = ADMIN only    ⏳ = deferred, not MVP
```

**Error and edge paths:**
- Failed login → **stays on `/login`**, inline error under the form, password field cleared, email retained. Never a redirect, never a toast.
- Logged-out user hitting a protected URL → `/login?next=/tickets/88213`, and after login lands on the ticket they wanted, not the default queue.
- `403` on a role-gated route (an agent typing `/admin/settings`) → a dedicated "Not available for your role" page with a link back to the queue. **Not** a redirect — a silent bounce makes users think the app is broken.
- `404` on a ticket → "Ticket not found" page. Deliberately identical whether the ticket does not exist or belongs to another tenant, mirroring the API's `404`-not-`403` decision.
- Token expiry mid-session → the API client catches `401`, silently calls `/auth/refresh`, and replays the original request. Only if the refresh fails does the user see the login screen, with `?next=` preserved.

---

## 3. SCREEN INVENTORY

### Public screens

**Login** — MVP
- **Purpose:** authenticate and land on the right home screen for the role
- **Who sees it:** public only (authenticated users are redirected away)
- **Triggered by:** `/login`, or any protected route while unauthenticated
- **Primary action:** submit credentials
- **API calls:** `POST /auth/login`

**Register** — MVP
- **Purpose:** customer self-registration under a tenant
- **Who sees it:** public only
- **Triggered by:** `/register`, link from Login
- **Primary action:** create account
- **API calls:** `POST /auth/register`, then `POST /auth/login`

### Customer screens

**My Tickets** — MVP
- **Purpose:** see my tickets and their honest status
- **Who sees it:** 🔒 CUSTOMER
- **Triggered by:** post-login for customers; `/my-tickets`
- **Primary action:** open a ticket, or start a new one
- **API calls:** `GET /tickets` (server-scoped to `requester = me`)

**New Ticket** — MVP
- **Purpose:** describe a problem in the customer's own words
- **Who sees it:** 🔒 all roles (agents can use `onBehalfOf`)
- **Triggered by:** "New ticket" from My Tickets
- **Primary action:** submit
- **API calls:** `POST /tickets` (with `Idempotency-Key`)

**Ticket Detail — Customer view** — MVP
- **Purpose:** read the thread, reply, see status
- **Who sees it:** 🔒 CUSTOMER (requester only)
- **Triggered by:** row click in My Tickets; `/tickets/{id}`
- **Primary action:** reply
- **API calls:** `GET /tickets/{id}`, `POST /tickets/{id}/messages`
- **Critical:** this view **must not render** internal notes, the AI panel, the priority rationale, or internal SLA detail. Enforced by the API returning a different DTO — the frontend is the second line of defence, not the first.

### Core agent screens

**Agent Queue** — MVP · *the most important screen in the app*
- **Purpose:** decide what to work on next
- **Who sees it:** 🔒 AGENT, TEAM_LEAD, ADMIN
- **Triggered by:** post-login default; `/queue`
- **Primary action:** open a ticket (secondary: assign to self without leaving)
- **API calls:** `GET /tickets` (cursor-paginated, role-scoped), `GET /sla/at-risk`, `POST /tickets/{id}/assign`

**Ticket Detail — Agent view** — MVP · *the hardest screen to lay out*
- **Purpose:** resolve the ticket
- **Who sees it:** 🔒 AGENT+
- **Triggered by:** queue row click; `/tickets/{id}`
- **Primary action:** reply (send, or send an edited draft)
- **API calls:** `GET /tickets/{id}?include=timeline`, `GET /tickets/{id}/analysis`, `GET /tickets/{id}/sla`, `GET /tickets/{id}/priority-rationale`, `POST /tickets/{id}/messages`, `POST /tickets/{id}/status`, `POST /tickets/{id}/assign`, `POST /tickets/{id}/drafts`, `GET /drafts/{id}`, `POST /drafts/{id}/action`, `POST /tickets/{id}/priority-override`

**Incident Board** — MVP · *the differentiator, and the demo screen*
- **Purpose:** see proposed and live incidents; confirm or reject
- **Who sees it:** 🔒 AGENT+ (confirm/reject is TEAM_LEAD+)
- **Triggered by:** nav; `/incidents`
- **Primary action:** confirm a proposed incident
- **API calls:** `GET /incidents`, `POST /incidents/{id}/confirm`, `POST /incidents/{id}/reject`

**Incident Detail** — MVP
- **Purpose:** manage one incident — linked tickets, updates, fan-out status
- **Who sees it:** 🔒 AGENT+
- **Triggered by:** board card click; `/incidents/{id}`
- **Primary action:** publish an update
- **API calls:** `GET /incidents/{id}`, `POST /incidents/{id}/updates`, `GET /incidents/{id}/updates/{uid}/deliveries`, `DELETE /incidents/{id}/tickets/{tid}`, `POST /incidents/{id}/resolve`

### Admin screens

**Knowledge Base** — MVP
- **Purpose:** manage the corpus that grounds every draft
- **Who sees it:** 🔒👑 ADMIN (read: AGENT+)
- **Triggered by:** nav; `/admin/knowledge`
- **Primary action:** add a document
- **API calls:** `GET /knowledge/documents`, `DELETE /knowledge/documents/{id}`, `POST /knowledge/reindex`, `GET /knowledge/search`

**KB Document Form** — MVP
- **Purpose:** create or edit a knowledge document
- **Who sees it:** 🔒👑 ADMIN
- **Primary action:** save → triggers async indexing
- **API calls:** `POST /knowledge/documents`

**Settings** — MVP (three tabs)
- **Purpose:** SLA policy, business calendar, AI governance and budget
- **Who sees it:** 🔒👑 ADMIN
- **Triggered by:** nav; `/admin/settings`
- **Primary action:** save a policy (creates a new effective-dated version)
- **API calls:** `GET/PUT /admin/sla-policies`, `GET/PUT /admin/calendar`, `GET/PUT /admin/ai-policy`

**Eval Dashboard** — MVP · *low build cost, disproportionate interview value*
- **Purpose:** accuracy over prompt versions, and token spend
- **Who sees it:** 🔒👑 ADMIN
- **Triggered by:** nav; `/admin/evaluation`
- **Primary action:** read — it is a reporting screen
- **API calls:** `GET /admin/eval/runs`, `GET /admin/ai/usage`

### Deferred (⏳ — not MVP)

| Screen | Why deferred |
|---|---|
| Audit Log viewer | `GET /admin/audit` works; a table UI adds nothing to the demo |
| Password reset flow | Needs email delivery, which is v2 |
| Profile / Settings (user) | Password change only; a modal from the avatar menu, not a screen |
| Customer status page | v2 feature |

---

## 4. SCREEN-BY-SCREEN WIREFRAMES

Full treatment for the four screens that matter; compact treatment for the rest.

---

### 4.1 Agent Queue

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ResolveAI    Queue  Incidents  Knowledge  Settings          🔔3   AM ▾        │
├──────────────────────────────────────────────────────────────────────────────┤
│ ⚠  INC-204  UPI and card payment failures — 38 tickets linked      [View] ✕   │ ← incident strip
├──────────────────────────────────────────────────────────────────────────────┤
│  Queue                                             9 open · 2 at risk        │
│                                                                              │
│  [ Search subject or body…              ]  [Status ▾] [Priority ▾] [Team ▾]  │
│  ( All )( Mine 9 )( Unassigned 4 )( At risk 2 )( Breached 0 )                │ ← segmented, not a dropdown
├──────────────────────────────────────────────────────────────────────────────┤
│ ▏P1 │ TKT-10405  Cannot log in after password reset                          │
│ ▔▔▔▔│ Neha Kulkarni · AUTH · 3 messages                                      │
│     │ ⏱ Resolution 34m left  ⚠ at risk        [Open]                         │
├──────────────────────────────────────────────────────────────────────────────┤
│ ▏P2 │ TKT-10428  Payment deducted but order still pending      🔗 INC-204     │
│ ▔▔▔▔│ Arjun Mehta · PAYMENT · 2 messages                                     │
│     │ ⏱ First response 2h 22m left                            [Open]         │
├──────────────────────────────────────────────────────────────────────────────┤
│ ▏P3 │ TKT-10431  Invoice PDF is missing line items                           │
│ ▔▔▔▔│ Unassigned · BILLING · 1 message                                       │
│     │ ⏱ First response 6h 10m left        [Assign to me] [Open]              │
├──────────────────────────────────────────────────────────────────────────────┤
│ ▏—  │ TKT-10436  App crashes on export                                       │
│ ▔▔▔▔│ Unassigned · ⟳ analysing…                                              │ ← triage in flight
│     │ ⏱ SLA starting…                                         [Open]         │
├──────────────────────────────────────────────────────────────────────────────┤
│                        [ Load more ]                                         │ ← cursor, not page numbers
└──────────────────────────────────────────────────────────────────────────────┘
```

**Interaction notes:**
- Row click anywhere → `/tickets/{id}`. The whole row is the target, not just the subject.
- `[Assign to me]` appears **only on unassigned rows**, on hover and on keyboard focus. Optimistic update; on `409 ALREADY_ASSIGNED` the row reverts and shows *"Taken by Neha"* inline for 4 seconds. **No modal** — losing an assignment race is a normal event, not an error.
- `🔗 INC-204` chip → Incident Detail. It is on the row, not buried in the ticket, because *"is this part of something bigger"* is a queue-level question.
- The at-risk chip reads *"34m left"*, not a percentage. Agents think in minutes.
- Search is debounced 350 ms → `GET /tickets?q=`.
- Filters are URL query params, so a filtered queue is a shareable link and survives refresh.
- **Polling:** every 20 s while the tab is visible, paused when hidden (`visibilitychange`). New rows fade in; **existing rows never reorder while the pointer is over the list** — an agent about to click must not have the target move.
- `⟳ analysing…` polls `GET /tickets/{id}/analysis` every 3 s, backing off to 10 s after 30 s.

**4 screen states:**

| State | What is shown | Trigger |
|---|---|---|
| Default | Rows with data | Normal load |
| Loading | 5 skeleton rows at real row height | Initial fetch. **Never a centred spinner** — it collapses the layout and the page jumps when data lands. |
| Empty | *"Nothing in your queue."* + *"Tickets assigned to you or your team appear here."* + `[View all unassigned]` | Filter or scope returns zero |
| Error | Inline banner *"Couldn't load the queue"* + `[Retry]`, **with the last successful data still rendered beneath, dimmed** | `GET /tickets` fails |

**UX principles applied:**
- **Information scent over chrome.** Every row answers priority, subject, who, category, SLA and incident linkage without a click. A row is dense on purpose.
- **Colour encodes, never decorates.** The 3px left bar is the only colour on a row. P1 red, P2 amber, P3 blue, P4 grey, untriaged hollow.
- **Preserve the last good state on error.** A stale queue is far more useful than an error page.

---

### 4.2 Ticket Detail — Agent view

The hardest layout. Three tiers, held strictly.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ResolveAI    Queue  Incidents  Knowledge  Settings          🔔3   AM ▾        │
├──────────────────────────────────────────────────────────────────────────────┤
│ ← Queue                                                                      │
│ TKT-10428  Payment deducted but order still pending                          │
│ ▏P2 ⓘ  PAYMENT   Priya Raman   Assigned: Arjun Mehta ▾   [Status: Assigned ▾]│
├──────────────────────────────────────────────────────────────────────────────┤
│ ⏱ First response  MET in 1h 42m ✓   │  Resolution  13h 52m left  ▓▓▓░░░░ 13% │ ← tier 2: thin strip
├──────────────────────────────────────────────────────────────────────────────┤
│ 🔗 Linked to INC-204 · resolution clock paused · 38 tickets       [View] ✕    │ ← tier 2: only when linked
├────────────────────────────────────────────┬─────────────────────────────────┤
│                                            │  AI ASSIST                 ⌄    │
│  ┌──────────────────────────────────────┐  │ ───────────────────────────────  │
│  │ Priya Raman · customer · 09:22       │  │  Triage            triage@7      │
│  │ I paid via UPI at 14:03 and the      │  │  Category   PAYMENT              │
│  │ money left my account but the order  │  │  Impact     SINGLE_USER          │
│  │ still shows pending. Order #88213.   │  │  Payment    yes                  │
│  │ 📎 screenshot.png                     │  │  Urgency    HIGH                 │
│  └──────────────────────────────────────┘  │  Confidence 0.91                 │
│                                            │                                  │
│  ┌──────────────────────────────────────┐  │  ─── Suggested reply ──────────  │
│  │ Arjun Mehta · agent · 11:04    FIRST │  │                                  │
│  │ Thanks Priya — I can see the UPI     │  │  Coverage 3/3 ✓                  │
│  │ reference. Checking with payments.   │  │                                  │
│  └──────────────────────────────────────┘  │  ① UPI payments debited without  │
│                                            │    an order confirmation are     │
│  ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐  │    auto-reversed in 5–7 business │
│  │ Arjun Mehta · internal note · 11:06  │  │    days.                         │
│  │ PSP dashboard shows the debit but no │  │    ✓ Runbook · UPI failures      │
│  │ capture. Escalating to payments.     │  │                                  │
│  └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘  │  ② Request a manual reversal by  │
│      ↑ dashed border = internal            │    sharing the UPI reference.    │
│                                            │    ✓ Article · Manual reversal   │
│  ┌──────────────────────────────────────┐  │                                  │
│  │ [ Reply to customer | Internal note ]│  │  ✕ Your refund of ₹2,499 will be │
│  │ ┌──────────────────────────────────┐ │  │    credited by 24 September.     │
│  │ │                                  │ │  │    Dropped — ₹2,499 and 24 Sep   │
│  │ │                                  │ │  │    appear in no cited source     │
│  │ └──────────────────────────────────┘ │  │                                  │
│  │ 📎 Attach            [ Send reply ]   │  │  ⚠ Not covered by the KB:        │
│  └──────────────────────────────────────┘  │    compensation for the delay    │
│                                            │                                  │
│                                            │  [Use reply] [Edit] [Discard]    │
└────────────────────────────────────────────┴─────────────────────────────────┘
```

**Interaction notes:**
- **`[Use reply]`** copies `assembledText` into the composer, focuses it, and records `SENT_AS_IS` **only when the message is actually sent unmodified**. If the agent types a character first, it becomes `EDITED` with a server-computed edit distance. `[Discard]` records `DISCARDED`. **All three are recorded** — discards are the most informative signal and are the easiest to forget to capture.
- **The dropped claim ③ stays visible.** Struck through, muted, with the rejection reason. Hiding it would make the verification mechanism invisible and therefore untrustworthy. This is the single most important interaction decision on the screen: *the agent must be able to see that the system caught the model inventing a refund amount.*
- Hovering a `✓ Runbook · UPI failures` citation opens a popover with the exact cited span, character offsets highlighted.
- The `ⓘ` beside `P2` opens the priority rationale popover — the rules table from `GET /priority-rationale`, with an `[Override]` link that requires a typed reason.
- Status dropdown offers **only legal transitions**, read from the server's `allowedTransitions`. Selecting `WAITING_ON_CUSTOMER` opens a small reason prompt, then shows *"Resolution clock paused"* inline in the SLA strip.
- The AI rail collapses to a 40px edge tab; the state persists in `localStorage`. Some agents will never use it and must not be forced to look at it.
- Optimistic send: the message appears immediately, greyed, with a spinner; on failure it turns red with `[Retry]` and the text is **never lost**.

**4 screen states:**

| State | What is shown | Trigger |
|---|---|---|
| Default | Thread + SLA strip + AI rail populated | Normal |
| Loading | Header skeleton, 2 message skeletons, rail shows *"Loading analysis…"* | Initial fetch |
| Empty | Not applicable — a ticket always has at least one message. **The AI rail has its own empty state:** *"No analysis yet"* / *"Triage failed — triage manually"* / *"AI disabled for this tenant"* | — |
| Error | Thread fails → full error page with `[Retry]`. **AI rail fails → only the rail shows an error.** | Partial failure |

**That last row is the important one.** The rail failing must never take the page down. It is the UI expression of the architectural rule that AI features degrade while ticketing continues.

**UX principles applied:**
- **Strict visual hierarchy.** Conversation = page. SLA and incident = thin persistent strips. AI = collapsible rail. Nothing competes.
- **Show the machine's failures, not just its successes.** The dropped claim builds more trust than three accepted ones.
- **Internal vs public is encoded twice** — dashed border *and* an explicit label. Sending an internal note to a customer is the worst mistake this UI could enable, so redundancy is correct here.

---

### 4.3 Incident Board

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ResolveAI    Queue  Incidents  Knowledge  Settings          🔔3   AM ▾        │
├──────────────────────────────────────────────────────────────────────────────┤
│  Incidents                          ( Proposed 1 )( Live 1 )( Resolved 12 )   │
├──────────────────────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ ⚠ PROPOSED                                    detected 37s after first  │  │
│  │ UPI and card payment failures at checkout                               │  │
│  │                                                                         │  │
│  │ 38 tickets · arrival rate 12.6× baseline · window 18 min               │  │
│  │ gate: ≥5 tickets AND ≥3× baseline — both passed                        │  │
│  │                                                                         │  │
│  │ TKT-10461 card declined at checkout                             0.91    │  │
│  │ TKT-10462 money deducted no order                               0.89    │  │
│  │ TKT-10463 UPI not going through                                 0.87    │  │
│  │ +35 more                                                                │  │
│  │                                                                         │  │
│  │                                        [ Reject ]  [ Confirm incident ] │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │ ● LIVE  INC-201                                        started 2h ago   │  │
│  │ Email notifications delayed                                             │  │
│  │ 14 tickets · last update 25 min ago                       [ Open ]      │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Interaction notes:**
- **The gate line is shown on the card, not hidden in a detail view.** *"38 tickets · arrival rate 12.6× baseline"* plus *"gate: ≥5 AND ≥3× — both passed"* is the evidence a human needs to confirm in two seconds. It also makes visible, in the product itself, that a statistical check and not a model decided this — which is the argument the whole feature rests on.
- `[Confirm incident]` → a confirmation dialog stating the consequences plainly: *"Link 38 tickets, pause 38 resolution clocks. First-response clocks keep running."* Confirming an incident is a high-blast-radius action; a bare button is not enough.
- `[Reject]` requires a typed reason. Both confirm and reject write evaluation labels — the UI says so in small text: *"Your decision improves detection accuracy."*
- Board polls every 30 s. A newly proposed incident slides in **and** fires a notification, because the whole value is speed of detection.

**4 states:** Default (cards) · Loading (2 skeleton cards) · **Empty (*"No incidents. Correlated ticket bursts will appear here automatically."* — an empty board is the healthy state and should read as reassuring, not broken)** · Error (banner + retry).

---

### 4.4 My Tickets — Customer view

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Acme Support                                            Priya Raman ▾         │
├──────────────────────────────────────────────────────────────────────────────┤
│  My tickets                                            [ + New ticket ]      │
├──────────────────────────────────────────────────────────────────────────────┤
│  TKT-10428   Payment deducted but order still pending                        │
│  In progress · updated 2 hours ago                                           │
│  ⚠ We're aware of a payment issue affecting several customers.               │
├──────────────────────────────────────────────────────────────────────────────┤
│  TKT-10390   How do I change my billing address?                             │
│  Resolved · 3 days ago                                                       │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Interaction notes:**
- **No priority, no SLA countdown, no assignee, no category.** A customer shown *"P3 · 6h 10m remaining"* will either be alarmed or will treat it as a promise. Internal operational data stays internal.
- The incident banner is the **only** system-generated text a customer sees, and it exists to prevent the *"nobody is looking at my problem"* feeling during an outage.
- Status vocabulary is translated: `ASSIGNED`/`IN_PROGRESS` → "In progress", `WAITING_ON_CUSTOMER` → **"Waiting for your reply"** (actionable, in the accent colour), `RESOLVED`/`CLOSED` → "Resolved".

**4 states:** Default · Loading (3 skeletons) · Empty (*"No tickets yet"* + `[Create your first ticket]`) · Error (retry).

---

### 4.5 Remaining screens — compact

**Login** — centred 400px card. Fields: tenant slug, email, password with a show/hide toggle. Inline errors under fields; a single form-level error above the submit for `401`. States: default · submitting (button spinner, form disabled) · error · *(no empty state)*.

**Register** — same shell. Adds full name and a password-strength hint that appears **on blur, not on keystroke** — live validation while someone is still typing their password is hostile.

**New Ticket** — single column, max 720px. Subject, body (8 rows, character counter appearing past 18,000 of 20,000), attachments. On submit → `202` → redirect to the ticket detail, which shows *"We're analysing your ticket"*. States: default · submitting · error (**form contents preserved — never make someone retype a long description**) · *(no empty)*.

**Ticket Detail — Customer** — as §4.4's row, expanded: thread with public messages only, a reply composer, status text. No rail, no strips beyond the incident banner.

**Incident Detail** — header (title, status, gate evidence), three panels: linked tickets (detachable), update timeline with per-update delivery counts (`38 sent · 0 failed`), and a publish composer with a visibility toggle. Publishing a `PUBLIC` update requires a confirmation dialog naming the customer count — *"This sends to 38 customers."*

**Knowledge Base** — table: title, source badge (`RUNBOOK` / `ARTICLE` / `RESOLVED_TICKET`), chunk count, indexed-at, actions. Header has `[+ Add]` and `[Reindex]`. **Reindex shows the estimated cost before running** — *"12 documents changed, 48 unchanged, est. ₹1.2"* — because a forced full reindex is the easiest way to burn the AI budget by accident. A search box at the top runs `GET /knowledge/search?explain=true` and renders the score breakdown; it is a debugging tool disguised as a search box, and it is the fastest way to answer *"why did retrieval miss this?"*.

**KB Document Form** — title, source dropdown, body textarea (monospace), optional URI. Save → `202` → back to the list with a *"Indexing…"* row state.

**Settings** — three tabs. *SLA Policy*: a grid of priority × plan tier with two number inputs each; saving shows the effective-dating note from the API verbatim. *Calendar*: timezone select, working-day checkboxes, start/end times, holiday list. *AI Policy*: external-model toggle, provider checkboxes, monthly budget with a live spend bar, retention days. The external-model toggle shows an explicit consequence line when switched off: *"Tickets will be processed by the local model only. Triage accuracy may be lower."*

**Eval Dashboard** — two sections. *Accuracy over prompt versions*: a simple line chart per suite, with a horizontal baseline line and pass/fail badges. *Token spend*: the `GET /admin/ai/usage` breakdown as a table, plus the budget bar. Low build cost, disproportionate interview value — it is the screen that proves the system measures itself.

---

## 5. DESIGN SYSTEM

### Colour tokens

```
--bg            #F7F8FA   page background — near-white, warm-neutral
--surface       #FFFFFF   cards, rows, panels
--surface-2     #F1F3F6   inset areas, table headers, disabled fields
--border        #E3E6EB   hairlines, dividers, input borders
--border-strong #C9CFD8   hover borders, focused dividers

--text          #1A1D23   primary body text
--text-muted    #5C6470   labels, metadata, timestamps
--text-subtle   #8B93A1   placeholders, disabled text

--primary       #2563EB   primary actions, links, focus rings
--primary-hover #1D4ED8
--primary-bg    #EFF6FF   selected rows, info panels

--success       #047857   met SLA, supported claim, delivered
--success-bg    #ECFDF5
--warning       #B45309   at-risk SLA, proposed incident, partial claim
--warning-bg    #FFFBEB
--danger        #B91C1C   breached SLA, P1, dropped claim, destructive
--danger-bg     #FEF2F2

--p1            #B91C1C
--p2            #B45309
--p3            #2563EB
--p4            #8B93A1
--untriaged     transparent, 1px --border-strong outline
```

**Contrast verification (WCAG AA):**

| Pair | Ratio | Verdict |
|---|---|---|
| `--text` on `--bg` | 15.8:1 | ✅ AAA |
| `--text-muted` on `--bg` | 6.4:1 | ✅ AA |
| `--text-subtle` on `--bg` | 3.4:1 | ⚠️ **Fails AA for body text.** Restricted to placeholders and disabled controls only — never for information a user must read. |
| White on `--primary` | 6.3:1 | ✅ AA |
| White on `--danger` | 7.1:1 | ✅ AAA |
| White on `--warning` | 4.8:1 | ✅ AA |
| White on `--success` | 5.6:1 | ✅ AA |
| `--danger` on `--danger-bg` | 8.4:1 | ✅ AAA |

The greens, ambers and reds are darkened well past their Tailwind defaults specifically to clear 4.5:1 on white. `bg-green-500` with white text is 2.5:1 and fails — a mistake worth not making.

**Dark mode: not in MVP.** Doubling the token set and re-verifying every contrast pair is a day of work that demonstrates nothing new about the backend. Stated as a deliberate cut, not an oversight.

### Typography scale

```
Display   28px / 600 / 1.2    — never used in MVP; reserved
H1        22px / 600 / 1.3    — page titles ("Queue", "Incidents")
H2        18px / 600 / 1.4    — panel headings ("AI ASSIST", "Suggested reply")
H3        15px / 600 / 1.4    — card titles, ticket subject in detail header
Body      14px / 400 / 1.6    — message bodies, form values, descriptions
Small     13px / 400 / 1.5    — row metadata, labels, claim citations
Caption   12px / 400 / 1.4    — timestamps, helper text, chip labels
Mono      13px / 400 / 1.5    — references (TKT-10428), JSON, KB body editor
```

**Body text is 14px, not 16px.** A deliberate departure from the usual advice. This is a dense operations tool where an agent needs eight rows visible without scrolling; 16px body would cost roughly two rows per screen. 14px at 1.6 line-height on a 15.8:1 contrast pair remains comfortable. The customer portal uses **16px** — different audience, different session length, different answer.

```
Heading font:  Inter        — designed for UI, excellent at small sizes,
                              unambiguous digits (critical: "34m left" must be
                              instantly legible), tight vertical metrics
Body font:     Inter        — one family, three weights. A second family would
                              add a network request and visual noise for nothing.
Mono font:     JetBrains Mono — disambiguated 0/O and 1/l/I, which matters when
                              an agent reads a ticket reference aloud on a call
Both from Google Fonts, subset to latin, weights 400/500/600 only.
```

### Spacing scale

```
xs    4px   icon↔label, chip padding
sm    8px   related items, badge padding
md   12px   row vertical padding, form field gap      ← the workhorse
lg   16px   card padding, panel gap
xl   24px   between major sections
2xl  32px   page top padding
```

**Note the deviation:** the standard scale jumps 8 → 16. A 12px step is inserted and becomes the most-used value, because at this density 8px is cramped and 16px wastes a row every three rows. **Only these six values are used** for every margin, padding and gap — consistency of spacing is most of what makes a UI feel deliberate rather than assembled.

### Component states

**Button — primary**
```
Default   bg --primary, white text, 6px radius, 8px 14px padding, 14px/500
Hover     bg --primary-hover
Active    bg --primary-hover, translateY(1px)
Focus     2px --primary outline, 2px offset   ← visible, never removed
Disabled  bg --surface-2, --text-subtle, cursor default
Loading   12px spinner replaces the label, width LOCKED to the default width
```
**Width locking matters.** A button that shrinks when its label becomes a spinner makes the layout jump, and the pointer that was over the button is suddenly over whatever is behind it.

**Button — secondary:** `--surface` bg, `--border` 1px, `--text`. Hover → `--surface-2` + `--border-strong`.
**Button — danger:** `--danger` bg, white. Used only for Reject, Detach, Delete.
**Button — ghost:** transparent, `--text-muted`. Used for row-level `[Assign to me]`.

**Input field**
```
Default   1px --border, --surface bg, 8px 12px, 6px radius, 14px
Hover     border --border-strong
Focus     border --primary + 3px --primary-bg ring, no outline suppression
Error     border --danger + --danger message below with role="alert"
Disabled  --surface-2 bg, --text-subtle
Success   border --success — used only on the password-strength field
```

**Row (queue / list)**
```
Default   --surface bg, --border bottom hairline
Hover     --surface-2 bg, row actions fade in over 100ms
Focus     2px inset --primary ring   ← keyboard navigation is first-class
Selected  --primary-bg bg, 2px --primary left border
Loading   skeleton at IDENTICAL height to a real row
```

**Badge / chip:** 12px, 500 weight, 2px 8px padding, 4px radius, `-bg` fill with matching dark text.

---

## 6. COMPONENT BREAKDOWN

| Component | Displays | Props / data | States handled | Used on |
|---|---|---|---|---|
| `AppShell` | Nav, notification bell, avatar menu, page slot | `user`, `unreadCount` | — | All authenticated screens |
| `NavBar` | Role-filtered nav links with active state | `role`, `currentPath` | — | Inside `AppShell` |
| `TicketRow` | One queue row: priority bar, subject, meta, SLA, incident chip, actions | `ticket`, `onAssign`, `showTeam` | default, hover, focus, loading, optimistic | Agent Queue |
| `PriorityBadge` | 3px colour bar + label | `priority` | incl. `UNTRIAGED` outline | Queue, Detail, Incident Detail |
| `SlaChip` | *"34m left"* / *"MET ✓"* / *"BREACHED"* + at-risk flag | `clock` | running, paused, met, breached, at-risk | Queue, Detail, At-risk list |
| `SlaStrip` | Both clocks with a progress bar | `clocks[]` | all four SLA states, plus paused | Ticket Detail (agent) |
| `IncidentBanner` | Linked-incident strip, dismissible | `incident`, `context` | agent variant, customer variant | Queue, Ticket Detail, My Tickets |
| `MessageBubble` | One message: author, role, time, body, attachments | `message`, `viewerRole` | public, internal (dashed), sending, failed | Both ticket details |
| `MessageComposer` | Tabbed public/internal, textarea, attach, send | `ticketId`, `allowInternal`, `prefill` | idle, typing, submitting, error | Both ticket details |
| `AiAssistPanel` | The whole right rail | `analysis`, `draft` | loading, ready, unavailable, budget-held, disabled-by-policy, collapsed | Ticket Detail (agent) |
| `TriageSignals` | Signal key/value grid | `signals`, `confidence` | ready, unavailable | Inside `AiAssistPanel` |
| `ClaimCard` | One claim: text, verdict icon, citations | `claim` | supported, partial, **dropped (struck through + reason)** | Inside `AiAssistPanel` |
| `CitationPopover` | Cited span with offsets highlighted | `citation` | loading, loaded, chunk-deleted | Inside `ClaimCard` |
| `PriorityRationale` | Inputs split model/system + rules table | `rationale` | — | Popover on Ticket Detail |
| `IncidentCard` | Board card incl. **gate evidence line** | `incident`, `onConfirm`, `onReject` | proposed, live, resolved | Incident Board |
| `DeliveryStatus` | `38 sent · 0 failed` with failure expansion | `summary`, `failures[]` | all sent, partial, all failed | Incident Detail |
| `StatusDropdown` | Legal transitions only, from the server | `current`, `allowed[]` | idle, open, submitting | Ticket Detail |
| `CursorPager` | `[Load more]` + count | `hasNext`, `onLoadMore` | idle, loading, exhausted | Queue, Incidents |
| `DataTable` | Generic sortable table with offset pagination | `columns`, `rows`, `page` | default, loading, empty, error | KB, Users, Eval |
| `EmptyState` | Icon, headline, sub, optional CTA | `title`, `description`, `action` | — | Every list |
| `ErrorBanner` | Inline error + retry, preserves stale data | `message`, `onRetry` | — | Every data screen |
| `SkeletonRow` / `SkeletonCard` | Shimmer at exact content height | `height`, `count` | — | Every list |
| `ConfirmDialog` | Destructive/high-blast-radius confirmation | `title`, `consequences[]`, `requireReason` | idle, submitting | Confirm incident, reject, detach, delete, publish public update |
| `Toast` | Transient success / info | `kind`, `message` | — | Global |
| `FieldError` | `role="alert"` message under an input | `message` | — | Every form |

**23 components. Eight of them** — `AppShell`, `NavBar`, `EmptyState`, `ErrorBanner`, `SkeletonRow`, `ConfirmDialog`, `Toast`, `FieldError` — **are shared plumbing and get built first (Tier 1 in §11).** Building them once is what stops the four states in §4 from being reimplemented thirteen times and diverging.

---

## 7. NAVIGATION STRUCTURE

**Navigation type: top navbar.**

Justified against the obvious alternative: a sidebar is conventional for dashboards, but this app has **four destinations**, and a sidebar spends 220px of horizontal width permanently to show four links. The ticket detail screen needs every pixel it can get for the thread-plus-rail layout. A top bar costs 56px of vertical space once and returns the full width to content.

```
Public (unauthenticated):
  [ResolveAI]                                    Login   Sign up

CUSTOMER:
  [Acme Support]                                 Priya Raman ▾
                                                 └ Profile · Log out
  (No nav links at all — My Tickets IS the app for a customer.
   A nav bar with one item is noise.)

AGENT:
  [ResolveAI]  Queue  Incidents  Knowledge       🔔3   AM ▾
                                                 └ Profile · Log out

TEAM_LEAD:
  [ResolveAI]  Queue  Incidents  Knowledge       🔔3   SQ ▾

ADMIN:
  [ResolveAI]  Queue  Incidents  Knowledge  Settings  Evaluation   🔔   RK ▾
```

**Active state:** the current nav item is `--text` with 500 weight and a 2px `--primary` bottom border; inactive items are `--text-muted` with 400 weight. **Two cues, not one** — colour alone is insufficient for colour-blind users and is explicitly called out in §12.

**Mobile (< 768px):** nav collapses to a hamburger opening a full-height slide-out drawer. **The agent screens are not optimised for mobile and this is a stated decision** — an agent queue and a three-panel ticket detail are desktop workflows, and pretending otherwise produces a bad version of both. What *is* made properly responsive is the **customer portal** (My Tickets, New Ticket, Customer Ticket Detail), because customers genuinely do raise tickets from a phone. Agent screens below 900px show a single-column fallback with the AI rail moved below the thread — usable, not optimised.

**Protected route behaviour:**
```
Unauthenticated → protected route
  → redirect /login?next=%2Ftickets%2F88213
  → after login, land on the requested URL, not the role default

Authenticated but wrong role (AGENT → /admin/settings)
  → render a "Not available for your role" page with a link back to the queue
  → NOT a silent redirect: bouncing someone without explanation reads as a bug

Access token expired mid-session
  → API client intercepts 401 → POST /auth/refresh → replay original request
  → transparent to the user; only a failed refresh reaches the login screen
  → and it preserves ?next=
```

---

## 8. FORM DESIGN

### New Ticket — *New Ticket screen*

| Field | Type | Required | Validation |
|---|---|---|---|
| Subject | text | Yes | 1–200 chars, not whitespace-only |
| Description | textarea (8 rows) | Yes | 1–20,000 chars |
| Attachments | file, multi | No | ≤5 files, ≤10 MB each, type allow-list |

- **Submit:** `POST /tickets` with an `Idempotency-Key` **generated once on mount and reused for every retry**. On `202` → redirect to `/tickets/{id}`. On error → stay, show the error, **preserve every field**.
- **Validation timing:** on blur for subject; **on submit only** for description. Validating a long description while someone is mid-sentence is hostile.
- **UX:** character counter appears only past 18,000 (90% of the cap), so it is a warning rather than ambient pressure. Submit disabled with a spinner while in flight. Unsaved-changes warning on navigate-away via `beforeunload`.
- **The idempotency key detail matters:** generating it on mount rather than on click means a double-click, or a retry after a network blip, cannot create two tickets.

### Message Composer — *both ticket detail screens*

| Field | Type | Required | Validation |
|---|---|---|---|
| Visibility | tab (Public / Internal) | Yes | Internal hidden entirely for CUSTOMER |
| Body | textarea (auto-grow 3→12 rows) | Yes | 1–20,000 chars |
| Attachments | file, multi | No | ≤5, ≤10 MB |

- **Submit:** `POST /tickets/{id}/messages`. Optimistic — the bubble appears greyed immediately. On success it solidifies and the SLA strip updates from `slaEffect`. On failure it turns red with `[Retry]` and **the text is never lost**.
- **UX:** `Cmd/Ctrl+Enter` sends. The **Internal tab changes the composer's border to dashed and its background to `--surface-2`** — the same encoding as an internal message bubble, so the visual language is consistent between composing and reading. Sending an internal note to a customer is the worst mistake this UI could enable; redundant encoding is the right call.
- Draft text persists in `localStorage` keyed by ticket id, so a refresh does not lose a half-written reply.

### Priority Override — *popover on Ticket Detail*

| Field | Type | Required | Validation |
|---|---|---|---|
| New priority | select | Yes | P1–P4, must differ from current |
| Reason | textarea | **Yes** | 10–500 chars |

The reason is mandatory and the minimum length is 10 characters, because **this record is training data**. *"wrong"* is not a label; *"customer is enterprise, contract says P1 for any payment issue"* is. The helper text says so: *"Your reason helps improve triage accuracy."*

### Confirm Incident — *dialog on Incident Board*

No fields. A consequence list and a confirm button:
> Linking **38 tickets** to this incident.
> • 38 resolution clocks will pause
> • 38 first-response clocks keep running
> • Customers are not notified until you publish an update

Reject requires a reason (10–500 chars) for the same labelling reason.

### Publish Incident Update — *Incident Detail*

| Field | Type | Required | Validation |
|---|---|---|---|
| Visibility | toggle (Internal / Public) | Yes | Default **Internal** |
| Body | textarea | Yes | 1–5,000 chars |

**Default is Internal, deliberately.** The dangerous action should never be the default. Switching to Public shows a red-bordered notice — *"This will be sent to 38 customers"* — and the submit button changes label to `[Publish to 38 customers]`. Confirmation dialog on top of that. Three layers of friction for an irreversible, high-blast-radius action, which is proportionate.

### SLA Policy — *Settings › SLA tab*

| Field | Type | Required | Validation |
|---|---|---|---|
| First response (minutes) | number | Yes | 1–10,080, integer |
| Resolution (minutes) | number | Yes | > first response |
| Effective from | datetime | Yes | Must be in the future |

- After save, render the API's `note` verbatim: *"Existing SLA records keep policy v3. Only tickets created after the effective date use v4."* **Surfacing that prevents a support ticket about your support system.**
- Inputs accept minutes but display a live helper — *"240 min = 4 business hours"* — because nobody thinks in minutes and everybody types in them.

### Login / Register

Standard. Password field has a show/hide toggle. Register shows strength feedback **on blur only**. A failed login clears the password and retains the email — retyping an email you just typed correctly is a small, constant insult.

---

## 9. API-TO-SCREEN MAPPING

| Screen | Method | Endpoint | When called | Loading treatment |
|---|---|---|---|---|
| Login | POST | `/auth/login` | Submit | Button spinner, form disabled |
| Register | POST | `/auth/register` | Submit | Button spinner |
| *(global)* | POST | `/auth/refresh` | On any `401` | Invisible — request is replayed |
| *(shell)* | GET | `/auth/me` | App mount | Full-page skeleton until resolved |
| Agent Queue | GET | `/tickets?cursor=&size=25&…` | Mount, filter change, poll 20s | 5 skeleton rows; **polls update silently** |
| Agent Queue | GET | `/sla/at-risk` | Mount, poll 60s | Chip count only |
| Agent Queue | POST | `/tickets/{id}/assign` | `[Assign to me]` | Optimistic; revert + inline note on `409` |
| Ticket Detail | GET | `/tickets/{id}?include=timeline` | Mount | Header + 2 message skeletons |
| Ticket Detail | GET | `/tickets/{id}/analysis` | Mount; **poll 3s while `PROCESSING`**, back off to 10s after 30s | Rail shows *"Analysing…"* |
| Ticket Detail | GET | `/tickets/{id}/sla` | Mount, after any status change | Strip skeleton |
| Ticket Detail | GET | `/tickets/{id}/priority-rationale` | **On `ⓘ` click only** — not on mount | Popover spinner |
| Ticket Detail | POST | `/tickets/{id}/messages` | Send | Optimistic bubble |
| Ticket Detail | POST | `/tickets/{id}/status` | Dropdown select | Dropdown disabled; strip updates from response |
| Ticket Detail | POST | `/tickets/{id}/drafts` | `[Suggest reply]` | Rail → *"Drafting…"* with an 18s progress hint |
| Ticket Detail | GET | `/drafts/{id}` | **Poll 2s after `202` until terminal** | Rail progress |
| Ticket Detail | POST | `/drafts/{id}/action` | Send / Edit-send / Discard | Fire-and-forget; failure logged, never blocks the agent |
| Ticket Detail | POST | `/tickets/{id}/priority-override` | Override submit | Button spinner |
| Incident Board | GET | `/incidents?status=` | Mount, poll 30s | 2 skeleton cards |
| Incident Board | POST | `/incidents/{id}/confirm` | Dialog confirm | Dialog spinner; card animates to Live |
| Incident Board | POST | `/incidents/{id}/reject` | Dialog confirm | Dialog spinner |
| Incident Detail | GET | `/incidents/{id}` | Mount, poll 30s | Section skeletons |
| Incident Detail | POST | `/incidents/{id}/updates` | Publish | Button spinner → `202` → delivery poll |
| Incident Detail | GET | `/incidents/{id}/updates/{uid}/deliveries` | **Poll 3s until `pending = 0`** | Live counter `21/38 sent` |
| Incident Detail | DELETE | `/incidents/{id}/tickets/{tid}` | Detach | Row spinner |
| My Tickets | GET | `/tickets` | Mount | 3 skeleton rows |
| New Ticket | POST | `/tickets` | Submit | Button spinner; fields preserved on error |
| Knowledge Base | GET | `/knowledge/documents?page=` | Mount, page change | Table skeleton |
| Knowledge Base | GET | `/knowledge/search?explain=true` | Debounced 350ms | Inline spinner |
| Knowledge Base | POST | `/knowledge/reindex` | `[Reindex]` after cost confirm | Button spinner → `202` |
| KB Form | POST | `/knowledge/documents` | Save | Button spinner |
| Settings | GET/PUT | `/admin/sla-policies` · `/admin/calendar` · `/admin/ai-policy` | Tab mount / Save | Tab skeleton / button spinner |
| Eval Dashboard | GET | `/admin/eval/runs` · `/admin/ai/usage` | Mount | Chart + table skeletons |

**Notes:**

- **Every endpoint except `/auth/login` and `/auth/register` requires `Authorization: Bearer`.** One Axios/fetch interceptor attaches it, and the same interceptor handles the `401` → refresh → replay cycle. Never attach the header per-call.
- **Cursor-paginated (need `[Load more]`, no page numbers):** `/tickets`, `/incidents`, `/sla/at-risk`, `/admin/audit`.
  **Offset-paginated (need numbered pagination):** `/knowledge/documents`, `/admin/users`, `/admin/eval/runs`.
  The two patterns are visually different on purpose — the component signals which kind of list you are in.
- **Genuinely slow endpoints needing extended loading treatment:** `POST /tickets/{id}/drafts` → `GET /drafts/{id}` (10–25 s — show an elapsed counter, not an indefinite spinner, because an indefinite spinner past ~8 seconds reads as frozen), and `POST /knowledge/reindex` (minutes — fire and notify).
- **Mutations requiring `If-Match`:** every ticket and incident mutation. The client caches the `ETag` from the last `GET` and sends it back. A `409 VERSION_CONFLICT` shows *"This ticket changed. Reload to see the latest."* with a `[Reload]` button — **never** a silent overwrite.

---

## 10. UX PRINCIPLES APPLIED

> **1. Show the machine's failures, not only its successes**
> *Applied to: the AI Assist rail on Ticket Detail*
> **Decision:** dropped claims stay visible, struck through, with the rejection reason stated.
> **Implementation:** `ClaimCard` renders `kept: false` claims at 60% opacity with a line-through and a `--danger` reason line. **Rationale:** an agent who sees the system catch the model inventing *"₹2,499 by 24 September"* trusts the two claims it kept. Hide the rejection and the verification mechanism is invisible, which makes it worthless as a trust signal — you would have built the most interesting part of the system and then concealed it.

> **2. Never let a degraded subsystem take down the page**
> *Applied to: Ticket Detail when triage or drafting fails*
> **Decision:** the AI rail has its own independent error boundary.
> **Implementation:** a React error boundary plus a separate query state around `AiAssistPanel`. Thread failure → full page error. Rail failure → *"Analysis unavailable — triage manually"* inside the rail only. **Rationale:** this is the architectural rule of the whole system, made visible in the interface.

> **3. Preserve the last good state on error**
> *Applied to: Agent Queue, Incident Board*
> **Decision:** a failed refresh shows a banner above **dimmed but still-rendered** previous data.
> **Implementation:** keep the previous result in state; render `ErrorBanner` above it at 60% opacity. **Rationale:** a stale queue is far more useful than an error page. Replacing working content with an error is the most common way dashboards become infuriating.

> **4. Colour encodes meaning; it never decorates**
> *Applied to: everywhere*
> **Decision:** every coloured element maps to exactly one semantic axis — priority, SLA risk, or claim verdict.
> **Implementation:** the only colour on a queue row is the 3px priority bar. Neutral surfaces everywhere else. **Rationale:** in a screen an agent scans hundreds of times a day, colour is the fastest channel available — and spending it on decoration destroys it as a signal.

> **5. Confirmation friction proportional to blast radius**
> *Applied to: assign / confirm incident / publish public update*
> **Decision:** three tiers. Reversible + low impact (assign) → **no confirmation**. Irreversible + internal (confirm incident, detach) → **dialog with an explicit consequence list**. Irreversible + reaches customers (publish public update) → **non-default toggle + red notice + count in the button label + dialog**.
> **Implementation:** `ConfirmDialog` takes a `consequences[]` array; the publish form defaults to Internal. **Rationale:** confirming everything trains people to click through dialogs without reading, which is worse than confirming nothing.

> **6. Translate internal vocabulary at the customer boundary**
> *Applied to: My Tickets, Customer Ticket Detail*
> **Decision:** customers see no priority, no SLA countdown, no category, no assignee. Status strings are rewritten.
> **Implementation:** a separate `TicketCustomerResponse` DTO server-side, and a separate React view — not conditional rendering inside the agent view. **Rationale:** `WAITING_ON_CUSTOMER` is a state machine value; *"Waiting for your reply"* is an instruction. And a conditional inside a shared component is one forgotten branch away from leaking an internal note.

> **7. Optimistic UI where the failure is recoverable and visible**
> *Applied to: sending a message, assigning a ticket*
> **Decision:** update immediately, reconcile with the server, fail loudly and locally.
> **Implementation:** greyed bubble → solid on success, red + `[Retry]` on failure with text preserved. Assignment reverts with an inline *"Taken by Neha"*. **Rationale:** the actions are frequent and the failures are rare, visible and recoverable. **Not applied** to publishing an incident update or overriding a priority — where failure is consequential, the user waits for the server.

> **8. Loading states must not change layout**
> *Applied to: every list and every button*
> **Decision:** skeletons at the exact height of real content; buttons lock their width when they become spinners.
> **Implementation:** `SkeletonRow` takes the same height constant as `TicketRow`. **Rationale:** content that jumps when data lands causes mis-clicks — and a mis-click in this app might resolve the wrong ticket.

> **9. An empty state should say what will fill it**
> *Applied to: every list*
> **Decision:** the empty state names the mechanism, not just the absence.
> **Implementation:** Incident Board empty reads *"No incidents. Correlated ticket bursts will appear here automatically."* **Rationale:** an empty incident board is the *healthy* state and should read as reassuring. *"No data"* reads as broken and teaches the user nothing about how the feature works.

> **10. Make the deterministic gate visible in the product**
> *Applied to: Incident Board cards*
> **Decision:** the card shows *"38 tickets · arrival rate 12.6× baseline · gate: ≥5 AND ≥3× — both passed"*.
> **Implementation:** rendered from the `detection` object in `GET /incidents/{id}`. **Rationale:** the human confirming needs evidence, not a claim. It also makes the central architectural argument — *a statistical check decided this, not a model* — visible in the interface rather than buried in a README.

---

## 11. BUILD PRIORITY ORDER

```
TIER 1 — Foundation (nothing works without these)
  1.  Tailwind config: colour tokens, type scale, spacing scale from §5
  2.  AppShell + NavBar with role-filtered links
  3.  API client: base URL, auth interceptor, 401→refresh→replay, RFC 7807 parsing
  4.  Shared plumbing: EmptyState · ErrorBanner · SkeletonRow · ConfirmDialog
                       · Toast · FieldError · Button · Input
  5.  Login screen
  6.  Register screen
  7.  Protected route wrapper + role guard + ?next= handling

TIER 2 — Core agent loop (the demo path)
  8.  PriorityBadge · SlaChip · TicketRow
  9.  Agent Queue: list, filters, cursor pagination, polling
  10. MessageBubble · MessageComposer
  11. Ticket Detail (agent): thread + composer + status dropdown + assign
  12. SlaStrip
  13. AiAssistPanel shell: collapsed/expanded, loading, unavailable
  14. TriageSignals
  15. ClaimCard · CitationPopover → the full draft flow

TIER 3 — The differentiator
  16. IncidentCard with the gate-evidence line
  17. Incident Board: tabs, confirm/reject dialogs
  18. Incident Detail: linked tickets, update composer, DeliveryStatus

TIER 4 — Customer portal (small; entirely separate views)
  19. My Tickets
  20. New Ticket form
  21. Ticket Detail (customer)

TIER 5 — Admin
  22. DataTable
  23. Knowledge Base list + search-with-explain
  24. KB Document Form
  25. Settings (three tabs)
  26. Eval Dashboard

TIER 6 — Polish
  27. Empty states for every list
  28. Error states for every screen
  29. Loading skeletons everywhere
  30. Keyboard navigation pass
  31. Responsive pass — customer portal properly, agent screens to a fallback
  32. Accessibility pass (§12)
```

**Why this order:** auth first because every other screen depends on it. **Shared plumbing before any feature screen** — build `EmptyState` once or build it thirteen times and have thirteen slightly different ones. Core agent loop before incidents, because the incident flow needs `TicketRow` and the ticket detail to link into. Customer portal after agent screens even though it is simpler, because it reuses almost nothing and reveals nothing new. Polish last: get it working, then make it correct, then make it pretty.

**The one deviation worth noting:** the **Eval Dashboard is Tier 5, not Tier 6**, despite being an admin screen. It is roughly two hours of work — two charts and a table — and it is the screen that proves the system measures itself. Cutting it would remove a disproportionate amount of interview value for a very small saving.

---

## 12. ACCESSIBILITY BASICS

Not a full WCAG audit. The specific things that matter for this app.

**Colour contrast** — verified in §5. One flag carried forward: **`--text-subtle` (#8B93A1) is 3.4:1 on `--bg` and fails AA for body text.** It is restricted to placeholder text and disabled controls, never to information a user must read. Every other pair clears 4.5:1.

**Never colour alone.** This is the most likely real accessibility failure in this app, because the whole design leans on colour as a signal:
- Priority: colour bar **+ the text label "P1"**
- SLA risk: colour **+ `⚠` icon + the words "at risk"**
- Claim verdict: colour **+ `✓`/`✕` icon + strike-through on dropped claims**
- Internal message: colour **+ dashed border + the explicit label "internal note"**
- Active nav item: colour **+ 500 weight + bottom border**

Every one of those carries at least two non-colour cues.

**Keyboard navigation.** The agent queue and ticket detail must be fully operable without a mouse — support agents are keyboard-heavy users and this is a usability win, not only an accessibility one.
- Queue rows are `<a>` elements wrapping the row, natively focusable and enter-activatable. Row actions are real `<button>`s inside, reachable by tab.
- `Cmd/Ctrl+Enter` sends from the composer.
- `/` focuses queue search; `Esc` clears it and blurs.
- `j` / `k` move row focus in the queue; `Enter` opens.
- **The focus ring is never removed.** `:focus-visible` with a 2px `--primary` outline at 2px offset, on every interactive element.

**Form labels.** Every input has a visible `<label>` with a `for` attribute. **Placeholder text is never the only label** — it disappears on focus, which strands anyone who was relying on it. Required fields are marked with both an asterisk and `aria-required="true"`.

**Images and icons.** Avatars use initials in a coloured circle rather than images, so there is no alt-text problem. Decorative icons get `aria-hidden="true"`. Icon-only buttons (dismiss, collapse) get `aria-label`.

**Modals and popovers.** On open, focus moves to the first interactive element inside. Focus is trapped while open. `Esc` closes. On close, focus returns to the trigger. `role="dialog"` with `aria-modal="true"` and `aria-labelledby` pointing at the title.

**Error announcements.** `FieldError` renders `role="alert"`, so screen readers announce validation failures without the user having to hunt. `ErrorBanner` uses `role="alert"` too. Toasts use `aria-live="polite"` — a success toast should not interrupt.

**Loading regions.** Sections being fetched carry `aria-busy="true"`. Skeletons are `aria-hidden="true"` so a screen reader announces the busy region rather than reading out placeholder shapes.

**Polling and live regions.** The queue polls every 20 s. **New rows are not announced** — an `aria-live` region that fires every twenty seconds is torture with a screen reader. Instead, an unobtrusive *"3 new tickets — refresh"* control appears, focusable and announced once when it appears.

---

## Close

> **✅ UI/UX design complete!**
>
> **During the Design Phase:**
> - Put §5 into `tailwind.config.js` **first**. Every colour, size and spacing value in the app comes from there — no arbitrary values, no one-off hex codes.
> - Check §9 against [05 — API Contract](05-API-CONTRACT.md): every screen's data needs are covered by an endpoint that exists.
>
> **During frontend development (Tiers 1–6):**
> - Use §4 wireframes as the blueprint for each screen
> - Use §6 to build shared components **once**, before the screens that need them
> - Use §8 for every form — especially the idempotency-key-on-mount detail and the preserve-fields-on-error rule
> - **Implement all four states on every screen — default, loading, empty, error.** Not just the happy path. This is the single thing that separates a portfolio frontend that looks finished from one that looks like a demo.
>
> **Build in the order from §11** — design system first, auth second, core agent loop third, incidents fourth, polish last.
>
> **And the scope reminder, one more time:** the frontend exists to demonstrate the backend. If Tier 6 slips, ship without it. If Tier 5 slips, ship the Eval Dashboard and cut the rest. **Nothing in this document is worth a week of the eight.**
>
> **Next:** [07 — Development Phases](07-DEV-PHASES.md).
