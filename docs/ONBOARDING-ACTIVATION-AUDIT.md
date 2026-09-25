# ResolveAI — First-Run & Activation Audit

> Scope: only the path from "first time opening ResolveAI" to "first real value". Other screens,
> backend logic and general UI polish are out of scope unless they sit on that path.
>
> **Method (2026-09-25):**
> 1. **Code read** of every route and state a new user can reach: `App.tsx`, the auth feature, each role's landing page, the empty states, the AI Assist rail, and the IAM and knowledge seeders.
> 2. **Live walkthrough** on the local stack: Docker services, Spring Boot with the `local` profile, and Vite on `:5176`, driven in a browser at 1440×900, 1180×800 and 375×812. I did it as a brand-new customer (self-registered), then as a seeded agent (`arjun@acme.com`) and a seeded admin (`admin@acme.com`).
>
> Every finding is tagged **✅ Verified live** (seen in the browser or API) or **📄 Code-confirmed** (not observable in this environment; reason given).
>
> **Caveat about the database:** the local database was not fresh. It held 34 tickets and 4 incidents from earlier `demo/storm.sh` runs. The "empty queue on first login" findings are therefore code-confirmed rather than seen, because dropping the database was out of bounds.

---

## Resolution status (2026-09-25, branch `fix/onboarding-audit`)

All 29 findings are closed. "Verified" means exercised on the running stack (browser, API or database), not only compiled. Backend suite: **609 tests, all passing** (602 existing + 7 new; existing assertions updated for V16, the suppression wording and the re-tuned `tau`). `SlaRaceTest` hung once on Windows in one full run and passed 70/70 on re-run — the known intermittent socket issue, unrelated to these changes. Frontend: `tsc -b`, lint and `vite build` are clean for every changed file.

| # | Finding | What changed | Verified |
|---|---|---|---|
| D0 | Browser login/register 403 | `5176` added to CORS in `.env.example`, `application-local.yml` and the local `.env`; `api-client.ts` turns body-less errors into readable ones | Browser login 200 through the proxy, no override |
| D1 | Prod has no data | `demo` package: `DemoSeeder` builds a `demo` tenant in any profile when `DEMO_ENABLED=true` (`TenantBootstrapper` shared with `IamSeeder`) | Fresh tenant: 35 tickets, 75 indexed docs, 144 clocks, 2 drafts, an incident |
| D2 | No demo entry | `POST /api/v1/demo/login` plus "Try the demo" role buttons on the login and register pages | One click → `/queue` as the agent |
| D3 | Empty or all-red first screen | Seeded queue through the real services; 24/7 demo calendar; "Simulate a payment outage" (`POST /demo/storm`, one at a time, 10-minute cooldown); `DEMO_RESET_CRON` rotation | Fresh queue "Breached 0"; storm → INC-1004 proposed; scheduled reset retired and reseeded the tenant |
| D4 | Register leads nowhere useful | Subtitle says who sign-up is for; demo block and "or create a customer account" divider | Browser |
| D5 | KB corpus never loaded | `KnowledgeCorpusLoader` writes inside `TenantScope`, counts only duplicates as skips and logs real failures; seeder `@Order(20)` after IAM | 75 docs per tenant; the audit's refund question now gets a cited draft |
| M1 | Workspace jargon, one error at a time | Helper text; plain workspace error shown inline; client-side password check before the round trip | Browser: "We couldn't find a workspace called 'ledgerly'…" |
| M2 | AI rail collapsed <1280px | Open by default except below 768px or after the user collapses it; one-line explainer; primary button | Open at 880px |
| M3 | Draft errors swallowed | `try/catch` plus a toast per error code (budget, in progress, disabled, rate-limited) | Forced a 409 → toast |
| M4 | Suppressed draft dead end | Backend copy in plain language (no false "Escalating"); UI "ResolveAI didn't guess" with Reply manually, Add article (admin), a covered example and Try again | Browser; Reply manually focuses the reply box |
| M5 | Customer sees only "Open" | `responseTarget` on the customer DTO; `ResponsePromise` banner; polling until it arrives | Browser: promise shown ~8 s after submit, no reload |
| M6 | README run command fails | `local` profile pinned in the `spring-boot:run` plugin config (not app-wide, so prod can't run the seeders by accident) | Plain `./mvnw spring-boot:run` starts |
| M7 | Row showed the wrong SLA clock | `primarySlaClock` (first response until met), labelled "1st reply" / "Resolve"; also used by the queue tiles | Browser: "1st reply 30m left" |
| H1 | No guided first action | `DemoTour` card: 3 live deep links, tick-offs, dismissible; `GET /demo/showcase` | Browser: all 3 links resolve |
| H2 | No value prop on mobile | One-line description below `lg`; clickable preview → demo | 375px |
| H3 | Incidents board can't show the feature | Storm button in the header and empty state; clearer agent hint | Browser |
| H4 | Every visit starts with an empty rail | `latestDraftId` now populated (`LatestDraftLookup`); showcase drafts pre-generated at seed; Regenerate | Browser: draft shown on open |
| N1 | Password rule learned by failing | Rule shown up front and checked locally | Browser |
| N2 | No forgot-password | "Forgot password?" → explains that admins reset passwords | Browser |
| N3 | KB empty state has no CTA | "Add your first article" for admins; who to ask for everyone else | Code (no empty-KB tenant exists now) |
| N4 | Eval dead end, usage 404 | `POST /admin/eval/runs` (single-flight) + `GET /admin/ai/usage`; page shows a run button, budget and breakdown | Run started, duplicate → 409; usage shows $0.20 / 136 calls |
| N5 | "1747s" | `formatSeconds` on the card and detail page | "29 min" |
| N6 | Repeated citation chips | One chip per document (and ① ② numbering fixed) | Browser |
| N7 | Full reload from the role page | `navigate('/')` | Same document after the click |
| ND1–ND4 | Unmeasured hypotheses | `V16__product_event`, `POST /api/v1/events` (allow-listed, no free text, rate-limited), `track()` across the funnel, `GET /admin/analytics/funnel` | Events recorded from demo click to reply sent |
| ND5 | Grounded-but-contradicting drafts | Replies now send `fromDraftId`, so the server records edit distance (`agent_draft_action`) — the data needed to decide | Draft 16 recorded `EDITED`, distance 68 |

**Also fixed on the way** (found while verifying):
- Seeded tenants had **no SLA policies**, so no clock could start on a fresh database. They are now seeded, and existing databases are backfilled.
- In-flight polls (draft pending, triage, customer promise) stalled in background tabs.

**Follow-ups, also closed:**
- **Correlation never detected a real varied outage.** `tau` was re-tuned on real embeddings, from 0.82 to **0.68**. `demo/storm.json`, `demo/no-storm.json` and the 200 starter tickets went through the live pipeline; their vectors are committed as a fixture and swept by the new `CorrelationRealEmbeddingTuningTest`, which asserts:
  - 100% storm detection;
  - ≤1% false alarms on the unrelated burst;
  - that 0.82 detected 0%.

  Live, the original varied storm now proposes an incident with 7 linked tickets, and `no-storm.json` proposes nothing. The demo button uses the unmodified `demo/storm.json` again. Limit: the incident links the storm's tight core (~7 tickets), not all 38.
- **API contract.** `docs/openapi.yaml` now describes the 9 new operations (`/demo`, `/demo/login`, `/demo/showcase`, `GET`/`POST /demo/storm`, `/events`, `/admin/analytics/funnel`, `POST /admin/eval/runs`, `/admin/eval/runs/status`). `getAiUsage` is corrected to what is implemented, and the customer ticket shape (`TicketCustomerDetail` with `responseTarget`) is documented. The Postman collection has a `Demo & Analytics` folder plus 3 Admin requests. Results:
  - Redocly lint clean (0 warnings);
  - `verify-openapi` 74 operations and 430/430 `$ref`s;
  - `verify-postman` 74/74 covered, none off-contract.

---

## 0. Who the first-time user actually is

ResolveAI is a **portfolio/demo product**: a fictional helpdesk for "Ledgerly", with synthetic data. There are two first-time users:

| Persona | How they arrive | Patience | What they must see |
|---|---|---|---|
| **A. Reviewer** (recruiter, hiring manager, interviewing engineer) | Clicks the live URL in the README or résumé, or clones the repo | ~60–120 seconds | At least one of the three "not a chatbot" mechanisms working |
| **B. In-product user** (customer, agent, lead, admin) | Registers or is given credentials | Normal SaaS patience | Their role's core job, done once |

Persona A drives almost every outcome that matters here. The planning doc sets the bar itself:
*"click into a working demo within two minutes"* (`docs/planning/13-TASK-BREAKDOWN-PHASE-9-10.md`).

---

## 1. Activation Definition & Current Funnel Summary

### Activation definitions

| Persona | Activation event (explicit) |
|---|---|
| **A. Reviewer** | Opens an agent ticket and sees **AI triage plus a cited draft with per-claim verdicts**, OR sees a **PROPOSED incident** that grouped a ticket burst |
| B. Customer | Submits a first ticket **and sees it acknowledged with a status and a response target** |
| B. Agent | **Sends a first reply built from a cited AI draft** (Use reply → Send) |
| B. Admin | Adds a knowledge document that later grounds a draft |

### What the live walkthrough actually showed

**On the planned production deploy: 0 paths reach activation** 📄. All three seeders are `@Profile("local")`, and there is no production seed script. A fresh prod database has no tenants, so login fails for every input and register fails for every workspace slug.

**On the documented local setup: 0 paths reach activation out of the box** ✅. Two separate blockers stop it:
1. `./mvnw spring-boot:run` **does not start the app** as the README says. No profile is active, `.env` is never imported, and startup fails on `Could not resolve placeholder 'resolveai.auth.jwt-secret'` (**M6**).
2. After working around that, **every sign-in and sign-up in the browser returns HTTP 403**. The UI shows only "Something went wrong. Try again." Vite serves on `:5176`, but `.env` allows CORS from `http://localhost:5173` only (**D0**).

**With both worked around** (`-Dspring-boot.run.profiles=local` and a process-only `CORS_ALLOWED_ORIGINS` override), the reviewer path is:

| # | Step | Measured / observed |
|---|---|---|
| 1–3 | Install toolchain, `.env` with LLM keys, `docker compose up`, start the backend **with an undocumented flag** | Startup 28 s |
| 4 | Find credentials in the backend console table | Only place they exist |
| 5–7 | Workspace `acme`, email, password | Workspace isn't explained anywhere in the UI |
| 8 | Land on `/queue` | Here: 25+ rows, **24 marked BREACHED**, mostly the same subject. On a fresh DB this would be empty (📄 no ticket seeder). |
| 9 | Open a ticket | AI rail open at 1440 px, **collapsed at 1180 px** ✅ |
| 10 | Click **Draft a reply** | Returned in **~4.4 s** ✅ |
| 11a | Ticket about a 502 error → cited draft → **activated** | 1 claim, 4 citations with identical titles |
| 11b | Almost any other ticket (e.g. refunds) → **suppressed** with jargon | Not activated. The KB corpus was never loaded (**D5**). |

**Minimum truly necessary:** 2 clicks (live URL → "Explore as Agent" → seeded showcase ticket with a draft already generated).

### Where I actually got stuck (live)

1. **Signing in with correct seeded credentials** → "Something went wrong. Try again." No clue why (CORS 403, empty body). A first-time local user stops here.
2. **Register with workspace `ledgerly`** (the product name in the README) and a 9-character password → first a password error, then only after fixing it, *"No active tenant with slug 'ledgerly'."* Two round trips, and "tenant" and "slug" are jargon.
3. **After registering (with `acme`) → "My tickets", empty.** The empty state is good. But I'm a customer, so the AI, SLA and incident features are all out of reach.
4. **After submitting a ticket as the customer** → the page shows **"Open"** and my message, nothing else. On the server, triage had already finished in 3.3 s: **P1 · PAYMENT · 30-minute first-response target**. I stayed on the page 25 s with no update and no promise shown.
5. **As an agent, the first queue screen is a wall of red "BREACHED"**, the opposite of "Every promise kept". My new ticket's row said **"4h left"** while its first-response clock actually had **30m left**.
6. **As an agent, asking for a draft on a refund question** → *"Only 0 of 0 claim(s) were supported by retrieved evidence (coverage 0.00 < threshold 0.80). Escalating rather than showing a weakly-grounded draft."* No next step. Nothing visibly escalated.

---

## 2. Findings by Severity

### 🚨 Drop-off Risk

#### D0. Local dev: every browser login/register fails with a 403 and a generic error — ✅ Verified live
- **Location:** `/login`, `/register` (and every other POST from the SPA)
- **Cause:** `frontend/vite.config.ts` serves on port **5176**. `.env` sets `CORS_ALLOWED_ORIGINS=http://localhost:5173`. The Vite proxy forwards the browser's `Origin: http://localhost:5176`, and Spring's CORS filter rejects it with **403 and an empty body**. `api-client.ts` only builds an `ApiError` when there's a JSON body, so the pages fall through to "Something went wrong. Try again."
- **Evidence:** `POST /api/v1/auth/login → 403` from the browser; the same request with curl and no `Origin` returns 200. Once 5176 was allowed, login worked immediately.
- **Why it matters:** Anyone who clones the repo to evaluate it (Persona A's technical variant) cannot sign in at all, and the message gives them nothing to debug.
- **Fix:** (1) Make the ports agree: either set Vite to 5173 or add `http://localhost:5176` to `.env.example` and the `application-local.yml` default. (2) In `api-client.ts`, map a body-less 403/5xx to a real message (*"The server refused this request (403). Check CORS_ALLOWED_ORIGINS."* in dev; *"We couldn't reach ResolveAI. Try again in a moment."* in prod).
- **Effort:** Low
- **Tradeoffs:** None. Keep CORS strict in prod.

#### D1. Production has no tenants, users or data — 📄 Code-confirmed (not deployed yet)
- **Location:** Every entry point on the deployed URL
- **Cause:** `IamSeeder`, `KnowledgeCorpusSeeder` and `EvalCaseSeeder` are all `@Profile("local")`. No prod seed script exists in `ops/`.
- **Fix:** A guarded `demo` profile or flag that seeds **one** demo tenant: users, SLA calendar, AI policy (`AiPolicyService` fails closed without one), the KB corpus (fixed per D5), ~100 tickets from `seed/generated/tickets-starter.json` in a *mix* of SLA states, and one PROPOSED incident. Idempotent, with its own password, switchable off.
- **Effort:** Medium
- **Tradeoffs:** Visitors will pollute a public tenant. Add a nightly reset. Use a demo-only password, never the shared `local` one.

#### D2. No demo entry: visitors must know a workspace, an email and a password — ✅ Verified live
- **Location:** `login-page.tsx`, `auth-shell.tsx`
- **Cause:** Three required fields, no hint that demo accounts exist, credentials printed only in the backend console.
- **Fix:** A **"Try the demo"** block above the form: *Explore as Agent / Team Lead / Customer*. Each is one click and calls `login('demo', …)`. Put it behind `VITE_DEMO_MODE` so real deployments can't ship it.
- **Effort:** Low (once D1 exists)

#### D3. The agent's first screen is empty (fresh DB) or a wall of BREACHED (used DB) — 📄 empty / ✅ breached
- **Location:** `/queue`, `/board`, `/incidents`
- **Cause:** No ticket seeder exists. The 200-ticket starter corpus is committed but never loaded. Storms need `demo/storm.sh` (bash, jq, curl). On this machine's used database, **24 of 25** visible tickets were BREACHED, from storms run days earlier, with "At risk 0".
- **Why it matters:** An empty queue reads as "broken". A red wall reads as "the SLA engine doesn't work". Both contradict the pitch on the auth page.
- **Fix:** Seed tickets with **fresh timestamps relative to now**, so the clocks show a realistic spread (mostly on track, a few at risk, one or two breached, one paused in WAITING_ON_CUSTOMER). Pre-seed one PROPOSED incident. Add an admin-only **"Simulate an outage"** button that posts `demo/storm.json` server-side. Reset the demo tenant nightly.
- **Effort:** Medium
- **Tradeoffs:** Pre-computed triage isn't "live". Keep the storm button for the live moment, rate-limited against `monthly-budget-micros`.

#### D4. Self-registration makes you a CUSTOMER, who never sees the product's value — ✅ Verified live
- **Location:** `register-page.tsx` ("Join an existing workspace")
- **Cause:** Registration always creates a CUSTOMER. That's the correct security design (no `role` field). But for a reviewer, "Create an account" leads to a portal with no AI, SLA or incident features visible.
- **Fix:** Keep the backend rule. In demo mode, relabel the link *"New here? Try the demo"* → D2 buttons. On `/register`, add a line: *"Customer accounts only. Agents are invited by an admin."*
- **Effort:** Low

#### D5. The knowledge-base corpus is never loaded, so most drafts are suppressed — ✅ Verified live (new)
- **Location:** AI Assist → "Draft a reply" on any ticket not about a 502 error
- **Cause:** `KnowledgeCorpusSeeder.run()` calls `documents.create(...)` **with no tenant scope**. `IamSeeder` wraps its writes in `tenantScope.inTenant(...)`; this seeder doesn't. It also catches **every** `RuntimeException` and counts it as "already present". The startup log says `Knowledge corpus seed: 0 created, 75 already present`, but the database holds **5 documents in total**: 1 billing-login runbook and 4 duplicate `Card payment 502 errors (<random id>)` test documents. None of the 75 corpus documents exists in any tenant.
- **Evidence:** A customer ticket asking *"How long does a refund take?"* got the suppression message (coverage 0.00), even though the corpus contains "Refund processing timeline". The 502-error ticket got a cited draft whose 4 citations all read "Card payment 502 errors (mud…)".
- **Why it matters:** Cited drafting is one of the three headline mechanisms. With the real corpus missing, a reviewer who picks almost any ticket sees a refusal, not the feature.
- **Fix:** Run the seeder per tenant inside `tenantScope.inTenant(tenantId, …)`, after `IamSeeder` (use `@Order` or make it depend on the tenants existing). Catch **only** the duplicate-content error; log and count everything else as a failure. Add a startup assertion or integration test that the corpus count is 75 for `acme`. Delete the 4 ad-hoc test documents from the demo tenant.
- **Effort:** Low–Medium
- **Tradeoffs:** Indexing 75 docs × 3 tenants costs a one-off embedding spend. Seed only the demo tenant if the budget is tight.

### 🚀 Must Fix

#### M1. "Workspace" is unexplained, and errors use jargon and arrive one at a time — ✅ Verified live
- **Location:** `login-page.tsx`, `register-page.tsx`, `RegistrationService`
- **Observed:** Register with workspace `ledgerly` and a 9-character password: first *"Password must be 10-128 characters"*; after fixing it, *"No active tenant with slug 'ledgerly'."* (Bean validation runs before the tenant lookup.)
- **Fix:** Helper text: *"Your company's ResolveAI address, e.g. `acme`."* Rewrite the error: *"We couldn't find a workspace called 'ledgerly'. Check the name with your support team."* Show the password rule up front (N1). Longer term, resolve the tenant from a subdomain or `?workspace=` so the field disappears.
- **Effort:** Low
- **Tradeoffs:** Login must stay generic so it doesn't reveal which tenants exist. Only register gets the specific workspace error.

#### M2. The AI Assist rail starts collapsed below 1280 px — ✅ Verified live
- **Location:** `ai-assist-panel.tsx:174–178`
- **Observed:** At 1180×800 the rail is a thin vertical "AI Assist" strip. At 1440 it's open.
- **Fix:** Open it on a user's first ticket visit, even on narrower screens (save "collapsed" only once they collapse it themselves). Add one line above the button: *"Drafts only include claims cited from your knowledge base. Unsupported claims are dropped."*
- **Effort:** Low

#### M3. Draft request failures are silently swallowed — 📄 Code-confirmed (couldn't force a failure)
- **Location:** `ai-assist-panel.tsx` `handleRequestDraft`
- **Cause:** No `try/catch` around `mutateAsync`. A 429, 5xx or exhausted budget stops the spinner and shows nothing.
- **Fix:** Toast the ProblemDetail, the same pattern as `agent-queue-page`. Special-case budget and rate-limit errors with a friendly demo message.
- **Effort:** Low

#### M4. A suppressed draft is a jargon dead end at the activation moment — ✅ Verified live
- **Location:** `DraftPanel`, `SUPPRESSED_*` branch
- **Observed copy:** *"Not enough grounded evidence to draft a reply. Only 0 of 0 claim(s) were supported by retrieved evidence (coverage 0.00 < threshold 0.80). Escalating rather than showing a weakly-grounded draft."*
- **Problems:** "0 of 0 claim(s)", "coverage 0.00 < threshold 0.80" and "retrieved evidence" are engineering terms. "Escalating" promises an action the UI never shows. There's no next step.
- **Fix:** Headline: *"ResolveAI didn't find enough in the knowledge base to answer this, so it isn't guessing."* One sentence of why, then actions: *"Reply manually"* (focus the composer), for admins *"Add a knowledge article"*, and in demo mode *"See a ticket the KB covers"*. Move the numbers into a "Details" disclosure. Say who the ticket was escalated to, or drop the word.
- **Effort:** Low

#### M5. The customer never sees the triage result or the response promise — ✅ Verified live
- **Location:** `ticket-detail-page.tsx` (customer branch); `useTicket` has no `refetchInterval`
- **Observed:** After submitting, the page shows "TKT-1034 · **Open**" and the message. The server had finished triage in **3.3 s** (P1, 30-minute first response, 4-hour resolution). After 25 s the page had made no further requests and still showed "Open".
- **Why it matters:** The previous screen promised *"Every ticket carries a response target."* This screen doesn't show it, and that promise is the customer's activation moment.
- **Fix:** A confirmation banner on arrival: *"Received. We aim to reply within 30 business minutes (by 10:30 Mon)."* This needs a customer-safe first-response target on `TicketCustomerResponse`. Poll while the status is OPEN.
- **Effort:** Medium

#### M6. The README's run command doesn't start the app — ✅ Verified live (new)
- **Location:** README "Running the application": `./mvnw spring-boot:run  # defaults to the local profile`
- **Observed:** `No active profile set, falling back to 1 default profile: "default"` → `.env` isn't imported (only `application-local.yml` imports it) → `Could not resolve placeholder 'resolveai.auth.jwt-secret'` → exit 1.
- **Fix:** Add `spring.profiles.default: local` to `application.yml` (prod sets `SPRING_PROFILES_ACTIVE=prod` explicitly, so this is safe), or change the README to `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`. Note that `SPRING_PROFILES_ACTIVE=local` in `.env` can never take effect, because `.env` is only read once `local` is already active.
- **Effort:** Low

#### M7. The queue row shows the wrong SLA clock — ✅ Verified live (new)
- **Location:** `ticket-row.tsx:19`: `primaryClock = ticket.sla?.resolution ?? ticket.sla?.firstResponse`
- **Observed:** New P1 ticket: the queue row says **"4h left"**; the ticket header says **First response 30m left**, Resolution 4h left.
- **Why it matters:** The queue page tells agents to *"work the oldest at-risk ticket first"*, but the row hides the clock that runs out first. A new agent's first choice of ticket can be wrong.
- **Fix:** Show the running clock with the least time left, and label it ("1st reply 30m" or "Resolve 4h").
- **Effort:** Low

### ⭐ High Impact

#### H1. No guided first action for reviewers — ✅ Verified live
- **Fix:** A dismissible **"Tour the three mechanisms"** card on the first visit to `/queue` (demo mode only), deep-linking to ① a ticket with a paused SLA clock, ② a ticket with a good cited draft, ③ the PROPOSED incident. Tick items off as they're visited. This doubles as the activation checklist.
- **Effort:** Medium (needs stable seeded references, from D1)

#### H2. Value props disappear below `lg` — ✅ Verified live
- **Observed:** At 375 px the login page is just the form, with no statement of what ResolveAI is.
- **Fix:** A one-line value prop above the form on small screens. In demo mode, make the SLA preview card clickable ("See this live").
- **Effort:** Low

#### H3. The Incidents board needs a way to trigger the feature — ✅ Verified live
- **Observed:** The PROPOSED card is very clear (*"gate: size ✓ ≥5 AND rate ✓ >3× — both passed"*, *"40.0× baseline"*). But an AGENT only sees *"Waiting for a team lead to confirm or reject"* and can't act, and on a fresh DB the board is empty.
- **Fix:** In demo mode, a "Simulate a payment outage" CTA (D3), and a note for agents: *"Team leads confirm incidents. Sign in as a lead to try it."*
- **Effort:** Low (once the endpoint exists)

#### H4. Pre-generate drafts for showcase tickets — ✅ Verified live (need confirmed)
- **Observed:** The draft is fast (~4.4 s) but depends on the ticket topic (D5) and can be suppressed (M4).
- **Fix:** Seed a completed draft on each showcase ticket, with "Regenerate" to watch it live.
- **Effort:** Medium

### 💡 Nice to Have

| # | Finding | Status | Fix | Effort |
|---|---|---|---|---|
| N1 | Password rule is only learned from a failure ("At least 10 characters" on blur, the server error on submit) | ✅ | Show the rule as helper text up front | Low |
| N2 | No "Forgot password?" link (planned in doc 06 §2) | ✅ | Add it, even if it only says "ask your admin" for now | Low |
| N3 | The KB empty state has no CTA | 📄 | "Add your first article" for admins | Low |
| N4 | Eval page: *"Run the classification suite from the backend"* dead end, and **Token spend says "No usage reported yet" because `GET /admin/ai/usage` returns 404**, even though triage and drafts spent tokens | ✅ | Seed one eval run; build the usage endpoint or hide the card until it exists | Low / Medium |
| N5 | Incident card says *"detected 1747s after first report"* | ✅ | Format as "29 min" | Low |
| N6 | Draft citations list 4 identical titles ("Card payment 502 errors (mud…)") | ✅ | Resolves with D5; also dedupe citations by document | Low |
| N7 | The "Not available for your role" page uses `window.location.assign('/')` (full reload) | 📄 | `navigate('/')` | Low |

**What already works well (verified; keep it):** the "My tickets" empty state and its CTA · the form keeps every value after a server error · triage completes in ~3 s and the signal tiles are easy to read · the draft shows per-claim citations plus a "Not covered by the knowledge base" list · "Use reply" prefills greeting, claims and sign-off · the incident card explains the statistical gate in plain terms.

### 🧭 Needs Data

| # | Hypothesis | What to measure first |
|---|---|---|
| ND1 | Reviewers prefer the agent role over the customer role | Demo-button clicks by role |
| ND2 | A live storm button convinces more than a pre-seeded incident | Sessions reaching incident detail, with vs without the button |
| ND3 | Enough mobile reviewers to design for | Viewport width at first load (the agent view is desktop-first; the rail overlays the thread below `xl`) |
| ND4 | Suppressed drafts put reviewers off, or build trust | Next action after `SUPPRESSED_*` vs `SHOWN` |
| ND5 | **A grounded claim that contradicts the customer hurts trust** ✅ seen live | The customer said *money was deducted*; the kept, cited claim said *"no money is actually taken for a failed 502 attempt."* It's true to the runbook, but a reply built from it reads as dismissive. Measure how often agents edit or discard drafts ("Use reply" then heavy edits) before deciding whether the entailment step should also check claims against the ticket. |

---

## 3. Stage-by-Stage Findings

### Signup & account creation
- **Fields:** Register asks for 4 (Workspace, Full name, Email, Password). All are justified for a real customer; none are needed for a reviewer → **D2, D4**.
- **Verification:** None. Registration signed me straight in and redirected to `/my-tickets` ✅. That's good for time to value.
- **Errors:** Arrive one at a time and in jargon → **M1**. In the default local setup every error is "Something went wrong" → **D0**.
- **Value proposition:** Strong on desktop, missing on mobile → **H2**.

### First-run experience
- No welcome flow. Redirects by role work (customer → `/my-tickets`, agent/admin → `/queue`) ✅. No wizard is needed, but nothing points the user anywhere → **H1**.
- Admin setup (SLA targets P1 30/240 … P4 240/2880, calendar, AI policy) is pre-seeded locally ✅ and must be pre-seeded in prod too → **D1**.

### Empty states
| Screen | Seen | Verdict |
|---|---|---|
| My tickets | ✅ "No tickets yet" + **Create your first ticket** | ✅ Good |
| Queue (all) | 📄 "Your queue is clear. New tickets land here within seconds…" | ❌ No action, and on a fresh tenant the promise is false (D3) |
| Board | 📄 "No tickets yet…" | ⚠️ No action (D3) |
| Incidents | 📄 "An empty board is the healthy state." | ⚠️ Right for prod, wrong for the demo (H3) |
| Knowledge base | 📄 "Runbooks and articles you add here…" | ⚠️ No CTA (N3) |
| Evaluation | ✅ "Run the classification suite from the backend…" + usage 404 | ❌ Dead end (N4) |
| AI draft suppressed | ✅ "Only 0 of 0 claim(s)… coverage 0.00 < threshold 0.80…" | ❌ Dead end at the activation moment (M4) |

### Guidance & education
- `PriorityRationale`, `CitationPopover` and the incident gate line are the best teaching surfaces already built. H1 should point users at them rather than add a generic tour library.
- There are no in-app links to the docs or README. In demo mode, a "How this works" link per mechanism would suit technical reviewers.

### Time to value (measured on the working local stack)
| Moment | Time |
|---|---|
| Backend startup | 28 s |
| Register → landed on My tickets | ~2 s |
| Ticket submit → server triage READY | **3.3 s** (not visible to the customer, M5) |
| Draft requested → draft SHOWN | **~4.4 s** |
| Customer page updates after triage | **Never** without a reload |

### Drop-off risk points (in order a user hits them)
1. The app doesn't start with the README command (M6)
2. Sign-in/sign-up returns 403 with "Something went wrong" (D0)
3. No data, users or tenant in prod (D1)
4. Workspace, email and password all needed before any value (D2, M1)
5. The obvious "Create an account" path leads to the role that can't see the AI (D4)
6. First queue screen is empty or all red (D3)
7. Draft suppressed on most topics because the KB was never loaded (D5), with a jargon dead end (M4)

### Re-engagement after onboarding
- **There is no outbound email path.** Mailhog runs in compose, but nothing in `src/main/java` sends mail. A customer who submits a ticket and leaves never hears about the reply.
- After activation, guidance stops. H1's checklist chains draft → incident → SLA pause.

### Product-specific categories
**Demo/portfolio product:** a known-good showcase path matters more than breadth (deterministic seed, fixed references, pre-generated drafts). **Cold start** on a Heroku Eco dyno: the auth spinner should say *"Waking the demo server (~10 s)…"* after 2 s. **Cost safety:** the public Draft and Storm buttons need friendly budget-exhausted messages (M3).

**Developer-evaluator** (a reviewer who clones the repo): D0 and M6 are this persona's whole first run. Add a `make demo` or `./demo/up.sh` that starts everything with the right profile and ports, then prints the URL and demo logins.

**B2B multi-role:** admin and end-user onboarding differ completely, and neither has a first-run path. There's no invite flow for agents (out of scope for the demo).

---

## 4. Time-to-Value Map (Reviewer persona)

| Stage | Today (local, as documented) | Today (local, with 2 workarounds) | Today (prod) | Minimum | How |
|---|---|---|---|---|---|
| App running | ❌ Fails to start (M6) | 3 CLI steps + LLM key + a flag | — | 0 | Live URL (D1) / one script |
| Credentials | Console log | Console log | Impossible | 0 | Demo buttons (D2) |
| Sign in | ❌ 403 (D0) | 3 fields | Fails | 1 click | D2 |
| Data to look at | — | Stale storm data (all breached) / empty on a fresh DB | — | 0 | Fresh-timestamp seed (D3) |
| Find a showcase ticket | — | Guess | — | 1 click | Tour card (H1) |
| See AI value | — | Expand rail (<1280 px), Draft, ~4 s, **works only on 502 tickets** | — | 0 | M2 + D5 + H4 |
| **Total** | **Blocked** | **~14 steps, 10–20 min, ~1 topic works** | **Blocked** | **2 clicks, <30 s** | |

---

## 5. Quick Wins (Low effort)

1. **Fix the CORS/port mismatch** and show a real message for body-less errors (D0)
2. **`spring.profiles.default: local`**, or correct the README command (M6)
3. **Load the KB corpus inside the tenant scope** and stop swallowing seeder errors (D5)
4. **Show the soonest-expiring SLA clock on queue rows** (M7)
5. **Demo role buttons** on login, and "Try the demo" instead of "Create an account" in demo mode (D2, D4)
6. **Catch and toast draft errors** (M3)
7. **Rewrite the suppressed-draft copy** with next steps (M4)
8. **Open the AI rail on first visit** at every width (M2)
9. **Workspace helper text**, friendlier workspace error, password rule shown up front (M1, N1)
10. **Format "1747s" as minutes**, dedupe citations, hide or fix the 404 usage card (N4–N6)

---

## 6. Analytics Gaps

There is **no product analytics** in the frontend. The backend exposes Prometheus/Grafana system metrics only. Because of that, none of the funnel above (including the D0 failure, which only shows up as a 403 in logs) can be measured.

Minimum event set, self-hosted or cookieless so the demo needs no consent banner:

| Event | Properties | Answers |
|---|---|---|
| `auth_page_viewed` | page, viewport width, demo_mode | Top of funnel; mobile share (ND3) |
| `demo_login_clicked` | role | Role preference (ND1) |
| `auth_failed` | endpoint, HTTP status, error code (incl. **body-less 403**) | Catches D0-class breakage and M1 |
| `register_submitted` / `register_failed` | error field(s) | Signup friction |
| `first_page_loaded` | route, rows visible, % breached, ms since auth | Empty or red-wall exposure (D3), cold start |
| `ticket_opened` | role, is_showcase, rail_open | Reach of the activation surface (M2) |
| `draft_requested` / `draft_result` | status, latency, coverage, kb_docs_in_tenant | Activation success; catches D5 (suppression rate) |
| `draft_used` / `reply_sent_from_draft` | edit distance from the draft | **Agent activation**; ND5 |
| `incident_viewed` | via, status, role | Mechanism reach (ND2) |
| `customer_ticket_created` | ms since register | **Customer activation**, time to value |
| `tour_item_completed` | item | Reviewer activation depth (H1) |

Backend health checks to add alongside (these would have caught D5 on day one): startup log/metric for `knowledge_documents{tenant}`, and an alert when `draft_result=SUPPRESSED_*` exceeds ~30% on the demo tenant.

**Funnel dashboard:** `auth_page_viewed → login_success → first_page_loaded(rows>0) → ticket_opened → draft_result=SHOWN | incident_viewed`

---

## 7. Onboarding Roadmap

### Phase 1: Remove blocking friction (before the live URL goes on the résumé)
- D0 CORS/port fix + readable network errors
- M6 default profile
- D5 KB seeding fix + seeder failure logging
- D1 production demo seeder (tenant, users, calendar, AI policy, KB, fresh-timestamp tickets, one incident)
- D2 demo role buttons; D4 register entry in demo mode
- M3 draft error handling; cold-start message
- **Exit criterion:** From a logged-out browser on the live URL, one click lands on a queue with triaged tickets in a realistic mix of SLA states, and a draft works on at least 5 different ticket topics.

### Phase 2: Shorten time to value
- D3 "Simulate an outage" button; H3 agent hint on PROPOSED incidents
- H4 pre-generated drafts on showcase tickets
- M2 rail open on first visit; M4 suppressed-draft rewrite; M7 correct SLA clock on rows
- M5 customer confirmation, response target and polling
- **Exit criterion:** A reviewer sees a cited draft **and** an incident in under 60 s without the README. A new customer sees their response target within 5 s of submitting.

### Phase 3: Add guidance and re-engagement
- H1 "Tour the three mechanisms" checklist (dismissible, demo mode only)
- H2 value prop on small screens; M1 / N1–N7 copy and empty-state CTAs
- Customer email on a public agent reply (the first real outbound mail path)
- Nightly demo-tenant reset
- **Exit criterion:** Every empty and suppressed state has an action. The tour chains all three mechanisms.

### Phase 4: Instrument and keep optimizing
- §6 events + a Grafana funnel panel + the KB/suppression health alerts
- Answer ND1–ND5 and change the product based on the results (e.g. drop the customer demo button if unused; extend entailment if ND5 shows heavy draft edits)
- **Exit criterion:** Activation rate (sessions reaching `draft_result=SHOWN` or `incident_viewed` ÷ `auth_page_viewed`) reviewed weekly.

---

### Appendix: how the live run was set up (for reproducing it)
- Docker services were already up (`docker compose ps`: postgres, redis, minio, ollama, mailhog, prometheus, grafana).
- Backend: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"` with `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5176` set **in the process environment only**. `.env` was not modified.
- Frontend: `npm run dev --prefix ResolveAI/frontend` (port 5176).
- Test data created during the audit: customer `audit.visitor@example.com` (acme); tickets TKT-1034 and TKT-1035; draft #14 and one suppressed draft.
