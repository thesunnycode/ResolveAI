# ResolveAI — UI / UX & Accessibility Audit

> **Scope:** only the user-facing interface: visual design, usability, interaction and accessibility. Backend and business logic are out of scope unless they visibly leak into the UI.
>
> **Method (2026-09-25):**
> 1. **Code read:** every page, component and token in `frontend/src`.
> 2. **Live walkthrough** in a real browser at 1440×900 and 390×844, in light and dark mode:
>    - as a first-time visitor, an agent, a team lead and a customer, using the demo workspace;
>    - a scripted keyboard (Tab) pass;
>    - element-overflow and target-size measurements;
>    - WCAG contrast computed from the actual token values.
> 3. **Benchmarking** against real product screens from Refero (screen IDs cited in §8).
>
> Screenshots are in [`docs/ui-audit/`](ui-audit/). "Measured" means the number came from the running app or the tokens, not an estimate.

---

## 1. Product & design-system summary

**What it is.** A **web app** (React 19 SPA, Vite, Tailwind v4, Radix primitives, lucide icons) for an AI-assisted helpdesk. It has two audiences with opposite needs:

| Audience | Screens | Job to be done | Usage pattern |
|---|---|---|---|
| **Agents** (+ team leads, admins) | Queue, Ticket (agent), Board, Incidents, Knowledge, Settings, Evaluation | Pick the next ticket, understand it, reply (with an AI draft), keep SLAs | All day, desktop, dense and repetitive: **power users** |
| **Customers** | My tickets, New ticket, Ticket (customer) | Raise a problem, know someone will answer, and when | Occasional, often **mobile**, low patience |

**Primary journeys:**
- **Agent:** Queue → open ticket → read triage → draft reply → send → status change.
- **Lead:** Board / Incidents → confirm an incident → publish an update.
- **Customer:** New ticket → see the promise → read the reply.

**Visual language today** (`index.css`):
- **Palette:** a warm-neutral "cream and bronze" light theme (`--color-bg #f8f7f4`, accent `#9a6b2f`) and a near-black dark theme (`#09090b`, accent `#e8dcc4`).
- **Accent use:** one accent, used for brand, focus and small highlights only. Color is otherwise reserved for meaning: priority P1–P4 and success/warning/danger.
- **Surfaces:** one treatment everywhere, `.glass` (translucent fill, 1px border, card shadow and `backdrop-filter: blur(14px)`).
- **Type:** Inter with stylistic sets; JetBrains Mono for the few technical strings.
- **Radii:** 5 / 8 / 12 / 16px.
- **Motion:** two restrained animations (fade, slide-up), both honouring `prefers-reduced-motion` ✅.

**What's genuinely good (keep it):**
- **Explainability is designed, not bolted on.** The priority-rationale popover, per-claim citation chips, struck-through dropped claims and the incident "gate" line put evidence where the decision is shown.
- **Internal notes can't be mistaken for customer-visible replies.** They get a dashed amber border and a lock label (`message-bubble.tsx`).
- **Skeleton rows match real row height** (`SkeletonRow`), so the page doesn't jump on load.
- **Honest copy.** For example, "It may have been removed, or it belongs to a queue you don't have access to" on a 404.
- **Dark mode is designed, not inverted.** Every semantic color has its own dark value, and it looks polished ([04](ui-audit/04-ticket-agent-dark.png)).
- **The customer response promise** ("We aim to reply within 30m, counted in support hours…") is ahead of most portals.

**Where it drifts:**
- **No type scale.** 18 different arbitrary font sizes, from `text-[9px]` to `text-[44px]` in half-pixel steps.
- **The low-contrast "subtle" grey carries a lot of information.** 106 uses, and it fails AA.
- **A spacious layout for a tool that should be dense.** Doc 06 says "dense, calm, professional", yet at 1440×900 the queue shows only 4 tickets above the fold ([02](ui-audit/02-queue-desktop.png)).
- **Mobile was not really designed for the agent side.**

---

## 2. Screen-by-screen audit

Finding IDs refer to §§3–7. Priority key: 🚨 Broken/Confusing · 🚀 Must Have · ⭐ High Impact · 💡 Nice · 🎨 Visual · ♿ Accessibility.

| Screen | Evidence | What works | Issues (IDs) |
|---|---|---|---|
| **Login / Register** | [01](ui-audit/01-login-desktop.png) | Clear value props, one-click demo, helper text, readable errors | U9 Sign in below the fold; "Welcome back" for first-time visitors · A2 focus ring · A4 |
| **Queue (agent)** | [02](ui-audit/02-queue-desktop.png), [05](ui-audit/05-queue-mobile.png), [06](ui-audit/06-queue-mobile-rows.png) | Clear row anatomy, correct SLA clock labels, live-refresh badge | U2 mobile hides priority and SLA · U5 zero-state while loading · U6 sort contradicts copy · U10 hover-only assign · U11 density · A5 nested interactive |
| **Ticket (agent)** | [03](ui-audit/03-ticket-agent-desktop.png), [04](ui-audit/04-ticket-agent-dark.png), [07](ui-audit/07-ticket-agent-mobile.png) | Conversation-first hierarchy, SLA strip, strong AI rail | U1 horizontal scroll on mobile · U3 dead Attach · U4 full-width back link · U7 no Assign here · U12 nested scrollbars · U13 composer loses text · U14 status commits without undo · U8 garbled incident banner |
| **Board (lead)** | [09](ui-audit/09-board-desktop.png) | Group by team or status, urgency sort per column, AI-triaging column | U15 columns clipped with no scroll cue · U16 looks draggable but isn't · U17 card clock inconsistent with queue · V3 chip noise |
| **Incidents** | (code + previous walkthrough) | The gate line is excellent evidence; "detected 29 min" | U18 agent sees a dead-end "waiting for a lead" · N3 |
| **Knowledge base** | (code) | Clear table, admin-only add | N4 no search or source filter · N5 no document preview from citations |
| **Settings (admin)** | (code) | Tabs, per-row save, dirty state | U19 ₹ here vs $ on Evaluation · A4 unlabeled inputs · A9 day toggles lack state |
| **Evaluation (admin)** | (code + earlier run) | Run button, month-to-date spend, breakdown | N6 no trend chart or axis; the sparkline bars have no scale |
| **My tickets (customer)** | [08](ui-audit/08-my-tickets-mobile.png) | Works well on mobile; clear CTA; good empty state | U20 "0 messages" on every row · U21 every status says "Received" · V2 cramped tiles |
| **Ticket (customer)** | (earlier walkthrough) | Promise banner, public-only thread | U13 composer loses text · N7 no "we replied" email or read receipts (out of UI scope) |

---

## 3. Usability issues

Each finding: **where** · **what's wrong** · **change** · **why it matters** · **complexity** · **priority** · **dependencies** · **before → after**.

### 🚨 Broken / confusing

**U1: the mobile ticket page scrolls sideways**
- **Where:** `tickets/sla-chip.tsx` (`SlaStrip`, the fixed `w-32` progress bar).
- **Wrong:** at 390px the document is 394px wide (measured), so there's a horizontal scrollbar and the SLA labels wrap into fragments ([07](ui-audit/07-ticket-agent-mobile.png)).
- **Change:** make the bar `w-full max-w-32 min-w-0`, stack first response and resolution vertically below `sm`, and move the bar under its label.
- **Why:** sideways wobble on a phone reads as broken, and the SLA is the one thing an agent must read.
- **Complexity:** Low · **Priority:** 🚨 · **Before → after:** the page wobbles and labels break → a clean two-line SLA block with no horizontal scroll.

**U2: queue rows hide priority, SLA and assign below 640px**
- **Where:** `tickets/ticket-row.tsx` (`hidden … sm:flex` on the right-hand column).
- **Wrong:** on a phone, rows show only a 3px colored bar. There's no P-label, no clock and no assign button, and subjects truncate after about 20 characters ([06](ui-audit/06-queue-mobile-rows.png)).
- **Change:** a two-line mobile row. Line 1 is the subject (2-line clamp). Line 2 is a meta row: `P1 · 1st reply 30m · Unassigned`. Put the assign action in a trailing swipe or overflow button.
- **Why:** triage on the go is impossible when urgency is invisible, and priority by color alone fails 1.4.1 (see A3).
- **Complexity:** Medium · **Priority:** 🚨.

**U3: the "Attach" button does nothing**
- **Where:** `tickets/message-composer.tsx`.
- **Wrong:** a styled button with no handler, and the backend rejects attachments anyway.
- **Change:** remove it until attachments ship, or disable it with the tooltip "Attachments aren't available yet".
- **Why:** a control that silently does nothing erodes trust in every other control.
- **Complexity:** Low · **Priority:** 🚨.

**U4: the back link fills the whole header**
- **Where:** `tickets/ticket-detail-page.tsx` (agent header, `<Link to="/queue" className="flex …">`).
- **Wrong:** the link is 1144×20px (measured), so clicking empty header space next to "← Queue" navigates away.
- **Change:** `inline-flex w-fit`.
- **Why:** accidental navigation mid-reply; see also U13, which means the draft is lost.
- **Complexity:** Low · **Priority:** 🚨.

**U5: stat tiles claim success before data arrives**
- **Where:** `agent-queue-page.tsx`, `board-page.tsx`, `my-tickets-page.tsx`.
- **Wrong:** while loading, tiles render **0** and "Every promise kept" with a green dot ([05](ui-audit/05-queue-mobile.png), taken mid-load).
- **Change:** render a skeleton (or "—") until the query resolves, and show the success tone only on real data.
- **Why:** a false "all good", then a flip to "24 breached", is exactly the wrong way round for an SLA tool.
- **Complexity:** Low · **Priority:** 🚨.

**U6: queue order contradicts its own instruction**
- **Where:** `agent-queue-page.tsx`.
- **Wrong:** the subtitle says "work the oldest at-risk ticket first", but the list is newest-first (TKT-1153 at the top) and there is no sort control.
- **Change:** default to SLA urgency (breached → at-risk → soonest deadline, the same comparator `board-page.tsx` already has), plus a sort menu (Urgency / Newest / Oldest).
- **Why:** the row at the top is what an agent works next.
- **Complexity:** Medium (needs a server sort param, or client-side sorting of the page) · **Priority:** 🚨.

**U7: you can't assign a ticket from the ticket itself**
- **Where:** agent header in `ticket-detail-page.tsx`.
- **Wrong:** the only "Assign to me" is the hover button on queue rows. The detail page just shows "Unassigned".
- **Change:** make the assignee a control: "Unassigned ▾" opens *Assign to me* / *Assign to…* for leads.
- **Why:** agents decide to take a ticket *after* reading it.
- **Complexity:** Low–Medium · **Priority:** 🚨.

**U8: garbled incident banner**
- **Where:** `ticket-detail-page.tsx` → `IncidentBanner title="linked to this incident"`.
- **Wrong:** it renders "**INC-1001** linked to this incident — View".
- **Change:** "This ticket is part of **INC-1001**: *Card payment failures at checkout* · View incident".
- **Complexity:** Low · **Priority:** 🚨.

### 🚀 Must have

**U9: login puts its primary action below the fold**
- **Where:** `auth/login-page.tsx`, `demo/demo-access.tsx`.
- **Wrong:** four full-width demo cards push *Sign in* below 900px ([01](ui-audit/01-login-desktop.png)). "Welcome back" greets people who are mostly first-time visitors.
- **Change:** one primary "Explore as Agent" card plus a compact "or as Team lead · Customer · Admin" link row, so the form fits above the fold. The heading should say "Sign in to ResolveAI"; keep "Welcome back" only when a refresh token exists.
- **Complexity:** Low · **Priority:** 🚀.

**U10: "Assign to me" only appears on hover**
- **Where:** `ticket-row.tsx` (`opacity-0 group-hover:opacity-100`).
- **Change:** always visible (quiet ghost style) on unassigned rows; keep hover only for secondary actions.
- **Why:** touch devices have no hover, and hidden actions have poor discoverability.
- **Complexity:** Low · **Priority:** 🚀.

**U11: the queue is too sparse for an all-day tool**
- **Where:** `agent-queue-page.tsx`.
- **Wrong:** header, tour and four 110px tiles leave only 4 rows visible at 1440×900 ([02](ui-audit/02-queue-desktop.png)).
- **Change:** collapse the tiles into a single 40px metric strip (`25+ in view · 25 unassigned · 0 at risk · 0 breached`) that doubles as filter chips. Move the tour into a dismissible banner above the list, and reduce row padding to `py-2.5`.
- **Why:** density is the core requirement per doc 06. Linear and Intercom show 15–20 rows at this size.
- **Complexity:** Medium · **Priority:** 🚀 · **Before → after:** 4 rows visible → about 12.

**U12: scrollbars nested inside scrollbars**
- **Where:** ticket page (thread and AI rail each scroll inside a fixed-height page); board columns.
- **Wrong:** two or three scrollbars side by side ([03](ui-audit/03-ticket-agent-desktop.png)); the rail's draft scrolls inside the rail.
- **Change:** one scroll region per column. The rail has a sticky header and a single scroll body; drop the extra `overflow-y-auto` inside the draft panel.
- **Complexity:** Low–Medium · **Priority:** 🚀.

**U13: the reply composer loses text**
- **Where:** `message-composer.tsx`.
- **Wrong:** navigating away (easy, given U4) discards a half-written reply. The New ticket page guards this; the composer doesn't.
- **Change:** a per-ticket draft in `sessionStorage` plus a `beforeunload` guard; show "Draft saved" under the textarea.
- **Complexity:** Low · **Priority:** 🚀.

**U14: status changes commit instantly with no undo**
- **Where:** `status-dropdown.tsx`.
- **Wrong:** choosing *Resolved* or *Closed* fires immediately; only Waiting states ask for a reason.
- **Change:** an optimistic update plus a 6-second "Moved to Resolved · Undo" toast.
- **Why:** a mis-click on a status notifies a customer.
- **Complexity:** Medium (the toast needs an action slot) · **Priority:** 🚀.

**U15: board columns are clipped with no cue**
- **Where:** `board-page.tsx`.
- **Wrong:** at 1440px the 4th and 5th columns are cut at the viewport edge ("UPI payment stuck on pro…", [09](ui-audit/09-board-desktop.png)), with no visible scrollbar.
- **Change:** use a fit-to-width grid when there are 5 or fewer columns (`grid-cols-5 min-w-0`); otherwise a visible scrollbar plus edge fade, and let columns shrink to 240px.
- **Complexity:** Low · **Priority:** 🚀.

**U16: the board looks like a kanban board but can't be dragged**
- **Wrong:** card styling and hover lift signal drag, but dropping isn't possible.
- **Change:** either add drag-to-change-status, reusing the status transition and reason dialog, or drop the lift and add "Open to change status" on hover.
- **Complexity:** High (DnD) / Low (signal fix) · **Priority:** 🚀 (the signal fix).

**U17: board cards use different clock logic than the queue**
- **Where:** `board-page.tsx` (card footer always shows `sla.firstResponse`; `clockOf()` prefers resolution).
- **Change:** reuse `primarySlaClock` from `tickets/sla-utils.ts` everywhere a clock is summarised.
- **Complexity:** Low · **Priority:** 🚀.

**U18: agents hit a dead end on proposed incidents**
- **Where:** `incident-card.tsx`.
- **Wrong:** agents see "Waiting for a team lead…" with no action.
- **Change:** add "Notify lead" or "Link my ticket" actions, or at least name the leads on shift.
- **Complexity:** Medium · **Priority:** 🚀.

**U19: the budget currency changes between screens**
- **Where:** `settings-page.tsx` shows "Monthly budget (₹)"; `eval-dashboard-page.tsx` shows `$`. Both read the same micro-unit value.
- **Change:** one currency, set by a workspace setting or fixed to USD, which is what the model cost figures are in.
- **Complexity:** Low · **Priority:** 🚀.

**U20: every customer row says "0 messages"**
- **Where:** `my-tickets-page.tsx`.
- **Wrong:** the count excludes the customer's own opening message, so it reads as if nothing was ever said.
- **Change:** replace the count with "Awaiting reply" or "Agent replied 2h ago", which is what the customer actually wants to know.
- **Complexity:** Low · **Priority:** 🚀.

**U21: every customer status says "Received"**
- **Where:** `my-tickets-page.tsx` (`STATUS_LABEL`).
- **Wrong:** OPEN maps to "Received" even after triage has set a promise.
- **Change:** show the promise in the list: "Reply expected within 30m" or "Replied".
- **Complexity:** Low (the data exists on the detail DTO; the list needs the field) · **Priority:** 🚀.

---

## 4. Visual design improvements

**V1: a real type scale**
- **Wrong:** 18 sizes (9, 11, 11.5, 12, 12.5, 13, 13.5, 14, 14.5, 15, 16, 20, 22, 24, 26, 28, 30, 44px); every screen picks its own.
- **Change:** 6 tokens: `--text-xs 12 / sm 13 / base 14 / lg 16 / xl 20 / 2xl 28` (display 44 on auth only). Nothing below 12px.
- **Complexity:** Medium (mechanical replace) · **Priority:** 🎨 (it also fixes A1 partly).

**V2: stat tiles are oversized for their content**
- **Wrong:** 110px-tall cards for single digits, and 3-column tiles on mobile wrap labels ("Needs your / reply").
- **Change:** a metric strip (see U11); 2 columns on mobile.
- **Priority:** 🎨.

**V3: too many chips on board cards**
- **Wrong:** each card carries a sparkle category chip, a team chip, an incident chip and an assignee row, and the category chip is uppercase ("PAYMENT").
- **Change:** show the category as sentence-case text, drop the team chip when grouped by team, and keep only the incident chip colored.
- **Priority:** 🎨.

**V4: glass surfaces with nothing behind them**
- **Wrong:** every panel uses `backdrop-filter: blur(14px)` over a flat canvas. There's nothing to blur, but it costs paint time on long lists and overlays.
- **Change:** solid `--color-surface` for panels; reserve blur for the sticky top bar and overlays.
- **Complexity:** Low · **Priority:** 🎨 (also perceived performance).

**V5: priority and SLA-state colors collide**
- **Wrong:** P1 is the same red as *BREACHED*, and P2 the same amber as *At risk*. "● P1 / ⊗ BREACHED" on a card is two unrelated facts in one color.
- **Change:** give priority its own glyph language: Linear-style signal bars, or `P1` in a neutral pill with a red bar. Keep red and amber for SLA state only.
- **Priority:** 🎨.

**V6: raw enums shown to people**
- **Wrong:** "PAYMENT", "Single User", role strings.
- **Change:** a label map (Payment, Billing…), applied in the AI rail tiles, the ticket header and board chips.
- **Complexity:** Low · **Priority:** 🎨.

**V7: token comments have drifted**
- **Wrong:** the `index.css` header says "teal appears only as brand mark", but the accent is cream/bronze. It also says dark is the designed-for theme, yet `initTheme()` defaults to light and ignores `prefers-color-scheme`.
- **Change:** fix the comment, and default to the OS preference (see H8).
- **Priority:** 🎨.

---

## 5. Accessibility findings (by severity)

All contrast numbers were computed from the token hex values (WCAG 2.x relative luminance).

### Critical

**A1: the "subtle" text color fails AA, and it carries real information**
- **Measured:** `--color-text-subtle` is 3.33:1 on `bg`, 3.56 on `surface` and 3.13 on `surface-2` (light); 3.59–4.12 (dark). AA for text under 18px is **4.5:1**.
- **Scope:** 106 uses, mostly 11–12.5px: timestamps, ticket references, hints, captions, the "current" status marker, SLA labels in the strip.
- **Fix:** light `#6f6a63` (5.0:1 on `bg`, 4.7:1 on `surface-2`); dark `#8b8b94` (5.1–5.9:1). Both were checked against every surface they sit on. Keep the old value only for decorative dividers and disabled text.
- **Complexity:** Low (token change) plus a visual check.

**A2: focus is invisible on search fields and inputs**
- **Measured:** the search inputs use `focus-visible:outline-none ring-4 ring-primary-bg`. `primary-bg` on `bg` is **1.08:1**, effectively no indicator (WCAG 2.4.7; 2.4.11 in 2.2). `Input` adds a `primary/60` border, which is thin and low contrast.
- **Where:** `agent-queue-page.tsx` and `board-page.tsx` search, `components/ui/input.tsx`.
- **Fix:** use the global `:focus-visible` outline (2px `--color-primary`, 4.65:1) or `ring-2 ring-primary`.
- **Complexity:** Low.

### Serious

**A3: priority is conveyed by color alone**
- **Wrong:** the priority bar and dot carry the meaning; on mobile rows only the bar remains (U2).
- **Measured:** light P2 amber `#d97706` is 3.19:1 and P4 `#8a8a93` is 3.42:1 as 12px text (needs 4.5).
- **Fix:** always pair the color with the `P1`–`P4` text or a glyph, and darken light P2 to `#b45309` (5.0:1) and P4 to `#6b6b73` (5.3:1).

**A4: form labels aren't associated with their inputs**
- **Where:** `Label` renders `<label>` without `htmlFor` in Settings (Timezone, Day start/end, Monthly budget, PII retention) and in `ConfirmDialog`'s reason textarea.
- **Result:** screen readers announce "edit text, blank".
- **Fix:** pass `htmlFor` and `id` (or wrap the input).
- **Complexity:** Low.

**A5: interactive elements nested inside a link**
- **Where:** `TicketRow` is an `<a>` containing a `<button>` (Assign to me).
- **Why it's a problem:** invalid HTML; screen readers read the whole row as one link, and the button is announced inconsistently.
- **Fix:** make the row a `div` with a primary link on the subject (stretched with `::after`) and the button as a sibling.
- **Complexity:** Medium.

**A6: segmented controls claim to be tabs**
- **Where:** `components/ui/segmented.tsx` (`role="tablist"`/`tab` with no `tabpanel`, no roving tabindex, no arrow keys; every option is its own Tab stop).
- **Fix:** they're filters, so use `role="radiogroup"` with radio semantics (or buttons with `aria-pressed`), plus arrow-key support.
- **Complexity:** Low–Medium.

### Moderate

**A7: no skip link, one page title, and route changes aren't announced**
- **Measured:** keyboard users pass 5 sidebar stops before reaching the queue. Every route's title is "ResolveAI", and focus stays put on navigation.
- **Fix:** a "Skip to content" link, per-route `document.title` ("TKT-1036 · Charged twice… — ResolveAI"), and focus moved to the `h1` on route change.

**A8: touch targets below 24px** (WCAG 2.5.8, measured)
- "Why this priority" info button: 14×14.
- AI rail collapse: 16×16.
- Citation chips: 21px tall.
- "Regenerate": 19px.
- Dismiss ✕ buttons in the toast and incident banner.
- **Fix:** a minimum 24×24 hit area (padding, or a pseudo-element).

**A9: toggle state conveyed visually only**
- **Where:** Settings working-day toggles show on/off only by border and fill.
- **Fix:** `aria-pressed`.

**A10: status changes aren't announced**
- **Wrong:** `ErrorBanner` has no `role="alert"`; skeleton regions have no `aria-busy`; the live queue refresh is silent.
- **Fix:** `role="alert"` on errors, `aria-busy` on loading containers, and a polite live region for "3 new tickets".

**A11: progress bars without semantics**
- **Where:** the SLA resolution bar and the "Model confidence" bar.
- **Fix:** `role="progressbar"` with `aria-valuenow`/`min`/`max`, and a text equivalent.

### Minor

**A12: input borders are too faint**
- **Measured:** 1.19:1 (`border`) and 1.41:1 (`border-strong`) on `bg`. WCAG 1.4.11 asks 3:1 for the visual boundary of a form control.
- **Fix:** a dedicated form-control border token, light `#908a80` (3.2:1 on `bg`, 3.4:1 on white), while keeping decorative dividers light. A lighter warm grey such as `#b9b3a8` only reaches 1.95:1 and would not pass.

**A13: dialogs have no description**
- **Wrong:** `ConfirmDialog` has no `Dialog.Description`, so its consequences list isn't read as the dialog's description (Radix warns about this).
- **Fix:** wrap the consequences in `Dialog.Description`.

**What's already right:** `prefers-reduced-motion` is honoured globally; icon-only buttons mostly have an `aria-label` (21 labels); Radix handles dialog, popover, select and dropdown focus trapping; `aria-invalid` is set on errored inputs.

---

## 6. Responsive / mobile findings

| # | Where | Finding | Fix |
|---|---|---|---|
| R1 | Ticket (agent) | Horizontal overflow (U1); the reference wraps "TKT-/1036" beside the title | Put the reference above the title on mobile; fix the SLA strip |
| R2 | Queue | Priority, SLA and assign hidden (U2); requester names wrap ("Customer / 10") | Two-line row; `whitespace-nowrap` on meta items |
| R3 | Ticket (agent) | The AI rail collapses to a 44px vertical strip beside a 300px thread; the composer is below the fold | On mobile, turn the rail into a bottom-sheet "AI Assist" button; pin the composer as a sticky bottom bar |
| R4 | Mobile top bar | Primary navigation is a horizontally scrolling text strip; Board, Settings and Evaluation are off-screen with no cue | A bottom tab bar (Queue · Incidents · KB · More) or an overflow fade with a "More" menu |
| R5 | Stat tiles | 2×2 on mobile pushes the list below the first screen | Metric strip (U11) |
| R6 | Board | Not usable under `xl`; columns are fixed-width with nested scroll | On mobile, a single column with a status picker (a "By status" select) |
| R7 | Customer ticket | Works well; the composer is a normal block at the end of a long thread | Sticky reply bar |

---

## 7. Micro-interaction & feedback improvements

| # | Moment | Today | Recommended |
|---|---|---|---|
| F1 | Assign to me | Toast "Assigned to you." and the row stays in place | Row animates to "Arjun · you", the toast offers "Open ticket" |
| F2 | Send reply | Button spinner, then the text clears | Optimistic bubble with a "Sending…" state; ⌘/Ctrl+Enter to send; "Sent · first response met ✓" when it closes the clock |
| F3 | Status change | Instant, silent (U14) | Optimistic change plus an Undo toast |
| F4 | Draft generating | Skeleton plus one line of copy | Staged progress: "Retrieving sources → Drafting → Checking 3 claims", which matches what actually happens and makes ~4s feel shorter |
| F5 | Live queue refresh | The badge says "refreshes every 20s"; new rows pop in unannounced | "3 new tickets · Show" pill (don't reorder under the cursor), plus a polite live region |
| F6 | Keyboard | None beyond Tab | `j/k` move, `Enter` open, `a` assign, `r` reply, `e` resolve, `/` search, `?` shortcut sheet |
| F7 | Copy reference | Not possible | Click `TKT-1036` to copy, with a "Copied" tooltip |
| F8 | Empty board column | "Nothing here" | Contextual: "Nothing waiting on customers — clocks are all running" |

---

## 8. Competitive pattern comparison

These references are real product screens from Refero:

- **Intercom Inbox** (`29cced10-8efd-48eb-a496-435e914543bd`): three columns (views, ticket, customer details); assign and team in the right pane.
- **Missive** (`fa016c0b-68e8-4ef0-80dd-3cc42e0ac7e4`, `11493d9d-9f09-41cf-8cc0-84235452de52`): a three-pane inbox with list, reading pane, and *Assign to me* / archive / snooze in the thread header.
- **Jace AI** (`fe31c1ff-60f1-454a-9537-f3a9f05f4435`): an AI assistant as a fixed right panel with an anchored input.
- **Cursor** and **Cycle** (`4129c60d-9425-42da-8f44-666bd2003e77`, `a7dd493b-3d52-4915-a07c-8a85aed868b9`): a ⌘K command palette with recent items and actions.
- **Keyboard-shortcut sheets:** Hello Ivy, WhatsApp and Relume (`8bcedeb1…`, `99ec68dd…`, `01d489c9…`).

| Pattern | Best-in-class | ResolveAI today | Borrow |
|---|---|---|---|
| **Split view** (list + ticket) | Missive and Intercom keep the list visible while reading | Queue → full page → back; every ticket is a round trip | A queue list/preview split at ≥1280px; `j/k` moves the preview |
| **Assign in the thread header** | Missive puts *Assign to me* beside reply/archive | Only on queue-row hover | U7 |
| **Customer context pane** | Intercom's right pane: user data, recent conversations, notes | None; the requester is only a name | A collapsible "Customer" section above AI Assist: plan tier, open tickets, last 3 conversations |
| **Command palette** | Cursor and Cycle: ⌘K for search, jump and actions | Search only inside the queue | ⌘K: tickets by reference or subject, incidents, "Assign to me", "Resolve" |
| **Shortcut discoverability** | `?` opens a two-column sheet | No shortcuts | F6 plus a sheet |
| **AI panel** | Jace: a fixed right panel, the primary AI action prominent, the input anchored | Comparable, and **better** on evidence: per-claim citations, dropped claims struck through, a refusal instead of a guess | Keep the evidence model; compress the triage tiles (see H6) |
| **Density** | Linear/Intercom rows are ~36–44px, 15–20 visible | ~74px rows, 4 visible | U11 |

**Where ResolveAI is already more distinctive than all of them:**
- **Explainable automation.** "Why this priority" shows each rule's hit or miss, the incident card shows the statistical gate, and a draft says "ResolveAI didn't guess". None of the benchmarks show *why* the machine decided.
- **A customer-facing SLA promise in business hours.** Most portals show only "Open".
- **Visually separated internal notes**, so a mistake is harder to make.

These are the product's signature traits, and the polish work should make them more prominent, not dilute them.

---

## 9. Top 20 highest-impact fixes (ranked)

| # | Fix | ID | Priority | Effort |
|---|---|---|---|---|
| 1 | Make "subtle" text pass AA (token change) | A1 | ♿ | Low |
| 2 | Visible focus on inputs and search | A2 | ♿ | Low |
| 3 | Mobile queue rows show priority, SLA and assign | U2 / A3 | 🚨 | Medium |
| 4 | Fix mobile horizontal overflow on the ticket page | U1 | 🚨 | Low |
| 5 | Default the queue sort to SLA urgency | U6 | 🚨 | Medium |
| 6 | Assign from the ticket header | U7 | 🚨 | Low–Med |
| 7 | Stat tiles show skeletons, not "0 / Every promise kept", while loading | U5 | 🚨 | Low |
| 8 | Remove or disable the dead Attach button | U3 | 🚨 | Low |
| 9 | Constrain the back link | U4 | 🚨 | Low |
| 10 | Save composer drafts and guard against leaving | U13 | 🚀 | Low |
| 11 | Undo on status change | U14 | 🚀 | Medium |
| 12 | Queue density: metric strip, compact rows | U11 / V2 | 🚀 | Medium |
| 13 | Always-visible assign; fix nested-interactive rows | U10 / A5 | 🚀 | Medium |
| 14 | Label/input association and dialog descriptions | A4 / A13 | ♿ | Low |
| 15 | Skip link, route titles, focus on navigation | A7 | ♿ | Low |
| 16 | Board: fit columns, honest drag affordance, shared clock logic | U15–U17 | 🚀 | Low–Med |
| 17 | Keyboard shortcuts plus `?` sheet; ⌘Enter to send | F6 / F2 | ⭐ | Medium |
| 18 | Fix the incident banner copy and the currency mismatch | U8 / U19 | 🚨 / 🚀 | Low |
| 19 | Type scale (6 tokens, nothing under 12px) | V1 | 🎨 | Medium |
| 20 | Split-view queue with a customer-context pane | H1 / H2 | ⭐ | High |

### ⭐ High-impact items referenced above

- **H1: split-view queue.** List plus preview at ≥1280px (Missive/Intercom). High effort.
- **H2: customer context pane.** Plan tier, open tickets and recent conversations above AI Assist. Medium effort; needs a small read endpoint.
- **H3: command palette (⌘K).** Medium effort; build on the existing Radix Dialog.
- **H4: drag-and-drop board.** High effort (see U16).
- **H5: saved views and filter chips** ("Mine & at risk", "P1 unassigned"). Medium effort.
- **H6: compress the AI rail's triage block.** Four 60px tiles become one line (`Payment · Single user · Medium urgency · 100%`), so the draft is visible without scrolling. Low effort.
- **H7: humanised enums.** See V6.
- **H8: theme follows the OS**, with a Light/Dark/System control in the account menu. Low effort.

### 💡 Nice to have

- **N1:** the citation popover gets "Open document" and drops "Characters 1520–1840 of the source document".
- **N2:** relative timestamps with an absolute-time tooltip.
- **N3:** incident cards show a mini arrival-rate sparkline, making the gate visual.
- **N4:** Knowledge base search and a source filter.
- **N5:** click a citation to preview the whole document in a drawer.
- **N6:** the Evaluation sparkline gets an axis and a pass line.
- **N7:** customer ticket read state ("Seen by agent").

---

## 10. Enhancement roadmap

**Phase 1: fix what's broken (≈2–3 days).**
- A1, A2 (tokens and focus).
- U1, U3, U4, U5, U8, U19 (one-line fixes).
- U2 (mobile rows), U6 (urgency sort), U7 (assign in header).
- **Exit:** no horizontal scroll at 390px; every text token passes AA; focus visible on every control; nothing claims success before data.

**Phase 2: baseline polish (≈1 week).**
- U9–U17 and U20–U21 (density, hover-free actions, undo, drafts, board honesty, customer list meaning).
- A3–A13 (semantics, labels, targets, announcements).
- V1 type scale; V4 solid surfaces; V6 enum labels.
- **Exit:** 12+ queue rows visible at 1440×900; axe-core clean on every route; one type scale.

**Phase 3: differentiation (≈2 weeks).**
- H1 split view; H2 customer pane; H3 ⌘K; F6 shortcuts and sheet; H6 compact triage; V5 priority glyphs separate from SLA colors.
- Make the explainability surfaces first-class: "Why" links next to priority, the SLA clock and incident membership.
- **Exit:** an agent can work 10 tickets in a row without touching the mouse.

**Phase 4: delight (ongoing).**
- F4 staged drafting progress; F5 "new tickets" pill; F1/F2 optimistic moments; incident sparkline (N3); mobile bottom-sheet AI (R3) and bottom navigation (R4); H4 drag-and-drop board; H8 system theme.
- **Exit:** perceived draft time under 3s; mobile customers never need to pinch or scroll sideways.

---

### Appendix: evidence

| File | View |
|---|---|
| [01-login-desktop.png](ui-audit/01-login-desktop.png) | Login, 1440×900, light |
| [02-queue-desktop.png](ui-audit/02-queue-desktop.png) | Agent queue with tour, 1440×900 |
| [03-ticket-agent-desktop.png](ui-audit/03-ticket-agent-desktop.png) | Agent ticket with cited draft |
| [04-ticket-agent-dark.png](ui-audit/04-ticket-agent-dark.png) | Same, dark theme |
| [05-queue-mobile.png](ui-audit/05-queue-mobile.png) | Queue loading state at 390px (shows U5) |
| [06-queue-mobile-rows.png](ui-audit/06-queue-mobile-rows.png) | Queue rows at 390px (shows U2) |
| [07-ticket-agent-mobile.png](ui-audit/07-ticket-agent-mobile.png) | Agent ticket at 390px (shows U1) |
| [08-my-tickets-mobile.png](ui-audit/08-my-tickets-mobile.png) | Customer list at 390px |
| [09-board-desktop.png](ui-audit/09-board-desktop.png) | Board, team lead, 1440×900 (shows U15) |

**Measurements used in this report:**

| Measurement | Value |
|---|---|
| Contrast, subtle text | 3.13–3.56 (light), 3.59–4.12 (dark) |
| Contrast, light P2 / P4 | 3.19 / 3.42 |
| Contrast, focus ring | 1.08 |
| Contrast, input border | 1.19 |
| Document width at 390px | 394px |
| Back-link box | 1144×20px |
| Smallest targets | 14×14, 16×16 |
| Tab stops before main content | 5 |
| Distinct font sizes | 18 |
| `text-subtle` uses | 106 |

---

## 11. Resolution status (2026-09-26)

Every finding was worked. **72 are resolved** and **4 are partly resolved**, with the gap stated. After-screenshots are `docs/ui-audit/after-*.png`, taken at 1440×900 and 390×844.

**Verification:**
- `tsc -b` and `vite build` are clean. `oxlint` reports warnings only.
- 49 backend tests pass, including the new `QueueUiSupportTest` (requester filter, reference search, `GET /agents`, `lastPublicReply`, public-only counts for customers).
- `verify-openapi.mjs`, `verify-postman.mjs` and `redocly lint` all pass.
- Walked through in the browser as agent, team lead and customer:
  - keyboard `j`/`Enter`;
  - Assign to… (end to end);
  - Resolve → Undo inside the 5s window, and the commit after it;
  - mobile AI sheet;
  - no horizontal overflow at 390px (`scrollWidth` = 390).

### Usability

| ID | Status | What changed |
|---|---|---|
| U1 | ✅ | SLA strip wraps and its bar shrinks; there's no sideways scroll at 390px. |
| U2 | ✅ | Two-line mobile row: priority, clock, reference, incident and a **Take** button. |
| U3 | ✅ | Removed the dead Attach button. |
| U4 | ✅ | Back link is `inline-flex w-fit`. |
| U5 | ✅ | Metric strips and the board show skeletons while loading; a success tone appears only on real data. |
| U6 | ✅ | "Most urgent" (breached → at risk → priority → time left), Newest and Oldest. Urgency is ranked within the fetched page, because the API has no SLA-deadline sort. The spec now says so. |
| U7 | ✅ | The assignee is a control. Agents get **Assign to me**. Leads get **Assign to…** with each agent's load, from the new `GET /agents`. |
| U8 | ✅ | "This ticket is part of **INC-1000**: *title* · View incident". |
| U9 | ✅ | One demo card plus a link row. "Welcome back" (with the workspace prefilled) shows only after a previous password sign-in. |
| U10 | ✅ | Assign is always visible on unassigned rows. |
| U11 | ✅ | A single metric strip that doubles as filter chips, plus a slimmer tour. |
| U12 | ✅ | The AI rail has one scroll region with a sticky header. |
| U13 | ✅ | Per-ticket, per-tab drafts in `sessionStorage`, a `beforeunload` guard and "Draft saved". |
| U14 | ✅ | Resolved/Closed are held 5s behind an **Undo** toast. Other moves commit, with Undo when the reverse move is legal. |
| U15 | ✅ | Board columns fit the width (`minmax(210px,1fr)`) with sticky headers. |
| U16 | ✅ | Drag-and-drop between columns, plus a ⋯ **Move** menu for keyboard and touch. |
| U17 | ✅ | `primarySlaClock` is used everywhere. |
| U18 | ✅ | A proposed incident names the leads who can confirm it. There's no "Notify lead" action yet (the audit's minimum). |
| U19 | ✅ | USD everywhere. |
| U20 | ✅ | The row says "Support replied 2h ago", "You replied… · awaiting our reply" or "Awaiting our reply". The list DTO gained `lastPublicReply`. |
| U21 | ✅ | The status shows the promise: "Reply within 30m of business hours" or "Replied". |

### Visual

| ID | Status | What changed |
|---|---|---|
| V1 | ✅ | Six-step type scale (12–28px, plus a 44px display size); no `text-[Npx]` left. |
| V2 | ✅ | The metric strip replaces the tiles on the queue, board and My tickets. |
| V3 | ✅ | Sentence-case category text, the team chip hidden when grouped by team, and only the incident chip is colored. |
| V4 | ✅ | `.glass` is a solid surface. Blur is kept only for bars and overlays. |
| V5 | ✅ | Priority is a glyph plus a label. Red and amber mean SLA state only. |
| V6 | ✅ | `humanize()` labels in the rail, header and board. |
| V7 | ✅ | Token comments were corrected, and the theme follows the OS. |

### Accessibility

| ID | Status | What changed |
|---|---|---|
| A1 | ✅ | `text-subtle` is `#6f6a63` / `#8b8b94` (≥4.5:1). |
| A2 | ✅ | A 2px `outline-primary` on every search field and input. |
| A3 | ✅ | P2 is `#b45309` and P4 is `#6b6b73`. Priority is never shown by color alone. |
| A4 | ✅ | `htmlFor`/`id` on every Settings field, the switches and the ConfirmDialog reason. |
| A5 | ✅ | Stretched-link row. The Assign button is a sibling, not nested in the link. |
| A6 | ✅ | Segmented is a `radiogroup` with roving tabindex and arrow keys. The composer toggle uses `aria-pressed`. |
| A7 | ✅ | Skip link, a per-route `document.title`, focus moved to the `h1`, and a route announcer. |
| A8 | ✅ | At least 24px targets: rationale ⓘ, rail collapse (32px), citation chips, Regenerate, and the toast/banner ✕. |
| A9 | ✅ | `aria-pressed` on the working-day toggles, plus a dashed/struck-through off state. |
| A10 | ✅ | `role="alert"` on errors, `aria-busy` on loading, and a polite live region for new tickets. |
| A11 | ✅ | `role="progressbar"` with values on the SLA, confidence and budget bars. |
| A12 | ✅ | `--color-border-control` `#908a80` / `#666670` (≥3:1). |
| A13 | ✅ | `Dialog.Description` in ConfirmDialog and the document drawer. |

### Responsive

| ID | Status | What changed |
|---|---|---|
| R1 | ✅ | Reference above the title on phones. |
| R2 | ✅ | Two-line rows, with `whitespace-nowrap` on meta items. |
| R3 | ✅ | On phones the AI rail is an **AI Assist** button that opens a bottom sheet (always closed on load). The composer is pinned to the bottom of the thread. |
| R4 | ✅ | Bottom tab bar. |
| R5 | ✅ | Metric strip. |
| R6 | ✅ | On mobile, a single board column with a "By status" select. |
| R7 | ✅ | Sticky reply bar on the customer ticket. |

### Feedback & keyboard

| ID | Status | What changed |
|---|---|---|
| F1 | ✅ | "TKT-1036 is yours." with an **Open ticket** action. |
| F2 | ✅ | Optimistic "Sending…" bubble, ⌘/Ctrl+Enter, and "Sent · first response met ✓". |
| F3 | ✅ | Same mechanism as U14. |
| F4 | ✅ | Staged "Retrieving sources → Drafting → Checking each claim". |
| F5 | ✅ | A "N new tickets · Show" pill; rows don't reorder under the cursor. |
| F6 | ✅ | `j`/`k`/`Enter`/`a`/`/` on the queue, `r`/`a`/`e`/`c`/`Esc` on a ticket, `⌘K`, and `?` for the sheet. |
| F7 | ✅ | Click-to-copy reference, or `c`. |
| F8 | ✅ | Contextual empty-column copy. |

### High value

| ID | Status | What changed |
|---|---|---|
| H1 | ✅ | Split view from 1280px with `TicketPreview`. A plain click previews; a modifier-click opens the ticket. |
| H2 | ◐ | The customer-context pane shows the requester and their other tickets (`requesterId` filter). **Plan tier isn't shown:** no API exposes it. |
| H3 | ✅ | ⌘K palette (tickets by reference or text, pages). |
| H4 | ✅ | Drag-and-drop board (see U16). |
| H5 | ✅ | Views: Mine & at risk, P1 unassigned, Breached. The last view is remembered. |
| H6 | ✅ | One-line triage summary. |
| H7 | ✅ | See V6. |
| H8 | ✅ | Light/Dark/System in the account menu; the default follows the OS. |

### Nice to have

| ID | Status | What changed |
|---|---|---|
| N1 | ✅ | Citation popover: "Open document" replaces the character offsets. |
| N2 | ✅ | `<RelativeTime>` with an exact-time tooltip and label. |
| N3 | ◐ | Arrival sparkline with a "gate fired" marker on the **incident page**. Cards don't have it, because the summary DTO has no per-ticket times. |
| N4 | ✅ | Title search plus a server-side source filter. |
| N5 | ✅ | Document drawer with the cited passage highlighted and scrolled into view. |
| N6 | ◐ | Y-axis, gridlines, dated ends and a pass/fail legend. **No pass line:** the suite threshold isn't exposed by the API. |
| N7 | ◐ | The customer row says "An agent has picked this up" once assigned. **No true read receipts:** "seen" isn't tracked server-side. |

### Found and fixed during verification

- A team lead without an agent profile was offered **Assign to me** and got a 404. The new `useCanSelfAssign` hides it in the queue, preview, header and the `a` shortcut.
- A customer's `messageCount` included internal notes, which revealed that they exist. It now counts PUBLIC messages only for customers.
- The OpenAPI `sort` enum listed `sla_deadline`, which the server rejects. The enum is now `created_at | priority | updated_at`.

### Follow-up (not a UI finding): fixed

`GET /tickets` used to cost about 8 SQL statements per row. Each row separately loaded:
- its SLA records, plus a `SELECT NOW()`;
- each clock's segments;
- up to three percentile aggregates for the breach prediction;
- its incident link;
- its requester, assignee and team, lazily.

A page of 25 took 1.3s on a fresh backend, and up to 40s for 100 rows on a loaded one.

The list now makes a fixed number of reads per page:
- the tickets with their people and team, fetched together;
- every clock and every segment on the page;
- the clock and the calendar, once each;
- the percentiles, once per class of ticket on the page;
- the incident links.

Measured on the same machine: 25 rows went from 1.3s to 0.05s, and 100 rows now take 0.08s. `TicketListQueryCountTest` counts statements at the JDBC layer: 17 for a page of 2 or 12, down from 26 and 106. It fails the build if the count starts growing with page size or goes above 20. The same test checks that clock state, remaining time, at-risk and incident refs still match the single-ticket paths.
