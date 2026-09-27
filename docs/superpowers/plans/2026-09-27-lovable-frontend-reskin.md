# Lovable Frontend Reskin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the visual design from the Lovable-generated export (`ResolveAI-handover.zip`, extracted for reference) onto the existing, real-backend-wired `frontend/` app — new look, same data layer.

**Architecture:** `frontend/` keeps its stack (Vite, react-router-dom, npm, real `axios` client in `lib/api-client.ts`, `AuthContext`). We import the Lovable export's shadcn/ui primitives, `components/app/*` shared components, and CSS design tokens wholesale, then re-skin each existing page/feature directory to use them, one route at a time, without touching how that page fetches or mutates data. We do **not** import the Lovable export's `lib/api.ts`, `lib/mock-db.ts`, `lib/auth.tsx`, or `lib/types.ts` — those are mock-only and use an incompatible schema (string IDs, different `TicketStatus` vocabulary, no tenant/team/permission fields).

**Tech Stack:** React 18/19, Vite, react-router-dom, Tailwind v4 (CSS-first `@theme`, already in use on both sides), shadcn/ui + Radix primitives, `class-variance-authority`, `clsx`/`tailwind-merge`, `@tanstack/react-query` (already a dependency), `sonner` for toasts, `react-hook-form` + `zod` for forms.

## Global Constraints

- Never import from the Lovable export's `lib/api.ts`, `lib/mock-db.ts`, `lib/auth.tsx`, or `lib/types.ts` — these are mock data files with an incompatible schema. Only copy their `components/`, `styles.css` tokens, and structural/JSX patterns.
- Every existing route must keep working against the real backend at every step — no route goes dark mid-migration. Verify by running `npm run dev` in `frontend/` and exercising the route in a browser (as CUSTOMER, AGENT, TEAM_LEAD, and ADMIN test accounts as applicable) after each task, not just at the end.
- Keep OLD's dark-mode toggle (`lib/theme.ts`, `getStoredTheme`/`resolveTheme`/`applyTheme`) — the Lovable export dropped it, but it's an existing feature and nothing in the brief says to remove it. Add a `.dark { ... }` block using the new oklch token names.
- Keep OLD's command palette (`features/command/*`) and demo tour (`features/demo/*`) — Lovable didn't redesign them, so they keep their current visual style until a follow-up task restyles them by hand.
- Route path renames are IN SCOPE per the mapping (`/my-tickets`→`/tickets`, `/admin/settings`→`/settings`, `/admin/evaluation`→`/evaluation`) — update `App.tsx` and every internal `<Link>`/`navigate()` call that references the old paths.
- `/board` (team-lead Kanban) has no Lovable redesign to copy — leave `features/board/board-page.tsx` on the new token/primitive set (Task 2) but do not attempt a layout rewrite of it; it is out of scope for this plan.
- The reference export lives at: `C:\Users\sunny\AppData\Local\Temp\claude\d--temporary-resume-ResolveAI\aec6e3c7-a659-4ab7-880a-da3685b2ee0b\scratchpad\lovable-export\ResolveAI\src` (call this `$LOVABLE` below — it is scratch, not part of the repo, so copy *from* it, never treat it as importable at runtime).

---

## File Structure

**New files (copied/adapted from `$LOVABLE`):**
- `frontend/src/components/ui/{accordion,alert-dialog,alert,aspect-ratio,avatar,breadcrumb,calendar,carousel,chart,checkbox,collapsible,command,context-menu,dialog,drawer,dropdown-menu,form,hover-card,input-otp,label,menubar,navigation-menu,pagination,popover,progress,radio-group,resizable,scroll-area,select,separator,sheet,sidebar,slider,sonner,switch,table,tabs,textarea,toggle-group,toggle,tooltip}.tsx` — shadcn primitives OLD doesn't have yet.
- `frontend/src/components/app/{field.tsx,states.tsx,status.tsx,confirm-dialog.tsx,unsaved-guard.tsx,incident-parts.tsx}` — shared app-level components, replacing several OLD one-off files (see Task 3).
- `frontend/src/components/app/app-shell.tsx`, `frontend/src/components/app/nav-bar.tsx` (split out of the Lovable `app-shell.tsx` to match OLD's existing shell/nav split) — new top-header layout.

**Modified files:**
- `frontend/src/index.css` — token block replaced with new oklch tokens + `.dark` block + Figtree/Outfit fonts.
- `frontend/src/App.tsx` — path renames.
- `frontend/src/components/ui/{badge,button,card,input}.tsx` — replaced with Lovable's shadcn versions (same primitives, but Lovable's have more variants used by the new pages).
- Every file under `frontend/src/features/{auth,tickets,incidents,knowledge,settings,eval,board}/*-page.tsx` and their feature-local subcomponents — re-skinned to use the new primitives/tokens, keeping existing props, hooks, and data-fetching calls unchanged.

**Removed files (superseded, once nothing imports them — verify with a repo-wide grep before deleting each):**
- `frontend/src/components/ui/{empty-state,error-banner,field-error,segmented,toast}.tsx` → replaced by `components/app/states.tsx` (`EmptyState`, `ErrorState`, `FormBanner`, `RowsSkeleton`, `StaleBanner`) and `components/ui/sonner.tsx`.
- `frontend/src/components/layout/page-header.tsx` → replaced by `PageHeader`/`Card`/`CardHeader` in `components/app/field.tsx`.

---

### Task 1: Design tokens, fonts, and dark mode

**Files:**
- Modify: `frontend/src/index.css`
- Reference: `$LOVABLE/src/styles.css`, `$LOVABLE/src/routes/__root.tsx` (font `<link>` tags)
- Reference: `frontend/src/lib/theme.ts` (existing dark-mode logic — do not change this file, just make sure the CSS it toggles still exists)

**Interfaces:**
- Produces: every CSS custom property new/re-skinned components will reference — `--background`, `--foreground`, `--card`, `--popover`, `--primary`, `--primary-foreground`, `--secondary`, `--muted`, `--muted-foreground`, `--accent`, `--destructive`, `--border`, `--input`, `--ring`, `--danger`/`--danger-soft`/`--danger-border`, `--warning`/`--warning-soft`/`--warning-border`, `--success`/`--success-soft`/`--success-border`, `--internal`/`--internal-border`, `--link`, `--chart-1`..`--chart-5`, `--sidebar*`, `--radius`. All later tasks assume these names exist in both `:root` and `.dark`.

- [x] **Step 1: Read both token files side by side**

Run: view `frontend/src/index.css` and `$LOVABLE/src/styles.css` in full.

- [x] **Step 2: Replace the `@theme`/`:root` token block in `frontend/src/index.css` with the Lovable token set**

Copy the `:root { ... }` and `@theme inline { ... }` blocks verbatim from `$LOVABLE/src/styles.css` into `frontend/src/index.css`, keeping OLD's `@import "tailwindcss";` line and any OLD-specific utility classes (e.g. `.glass`) that aren't token-related — re-point those utility classes at the new token names (e.g. if `.glass` referenced `--color-surface`, repoint it to `--card`/`--popover` as appropriate) rather than deleting them, since `features/*` still reference `.glass` until later tasks re-skin those pages.

- [x] **Step 3: Add a `.dark { ... }` block**

Copy Lovable's dark-mode values if present in `$LOVABLE/src/styles.css`; if Lovable's export has no `.dark` block (per the exploration report, it doesn't ship one wired into the UI), derive one by darkening each oklch lightness channel consistently with the existing OLD `.dark` block's relationships (e.g. same delta between `--background` and `--card` that OLD's old dark block had). Keep the selector `.dark` (matches `lib/theme.ts`'s `document.documentElement.classList.toggle('dark', ...)`).

- [x] **Step 4: Swap fonts**

In `frontend/src/index.css` (or `frontend/index.html`, wherever OLD currently loads Inter/JetBrains Mono), replace the Google Fonts `<link>`/`@import` with Figtree + Outfit, matching `$LOVABLE/src/routes/__root.tsx`. Add `--font-heading: 'Outfit', ...` and body font `--font-sans: 'Figtree', ...` custom properties, and confirm `frontend/src/index.css` maps Tailwind's `font-sans`/heading utility to them the same way Lovable's `@theme inline` does.

- [x] **Step 5: Verify**

Run: `cd frontend && npm run dev`, open the app, confirm the page renders (existing components will look broken/unstyled in places until Task 2+ — that's expected), confirm no console errors about missing CSS variables, and toggle dark mode via the existing UI control to confirm `.dark` applies without a flash of unstyled colors.

- [x] **Step 6: Commit**

```bash
git add frontend/src/index.css frontend/index.html
git commit -m "style(frontend): adopt Lovable design tokens, fonts, and dark theme"
```

---

### Task 2: Import shadcn/ui primitives

**Files:**
- Create: `frontend/src/components/ui/{accordion,alert-dialog,alert,aspect-ratio,avatar,breadcrumb,calendar,carousel,chart,checkbox,collapsible,command,context-menu,dialog,drawer,dropdown-menu,form,hover-card,input-otp,label,menubar,navigation-menu,pagination,popover,progress,radio-group,resizable,scroll-area,select,separator,sheet,sidebar,slider,sonner,switch,table,tabs,textarea,toggle-group,toggle,tooltip}.tsx`
- Modify: `frontend/src/components/ui/{badge,button,card,input}.tsx` (OLD already has these — replace with Lovable's versions since Lovable's have more variants the new pages use)
- Modify: `frontend/package.json` (add missing Radix/shadcn deps)
- Reference: `$LOVABLE/src/components/ui/*`, `$LOVABLE/package.json` (dependency list), `$LOVABLE/src/lib/utils.ts` (confirm `cn()` matches OLD's `frontend/src/lib/utils.ts` before assuming drop-in compatibility)

**Interfaces:**
- Consumes: `cn()` from `frontend/src/lib/utils.ts` (already exists, already `clsx` + `tailwind-merge` per the exploration — every copied primitive imports `import { cn } from "@/lib/utils"`, path must resolve the same in OLD as it did in `$LOVABLE`).
- Produces: standard shadcn component exports (e.g. `Dialog`, `DialogContent`, `DialogHeader`, `Select`, `SelectTrigger`, `SelectContent`, `SelectItem`, `Sheet`, `SheetContent`, `Tabs`, `TabsList`, `TabsTrigger`, `Popover`, `PopoverTrigger`, `PopoverContent`, `Table`, `TableHeader`, `TableBody`, `TableRow`, `TableCell`, `Tooltip`, `TooltipTrigger`, `TooltipContent`, etc.) — later tasks import these by name from `@/components/ui/<file>`.

- [x] **Step 1: Diff dependency lists**

Run: `diff <(node -e "console.log(Object.keys(require('./frontend/package.json').dependencies).sort().join('\n'))") <(node -e "console.log(Object.keys(require('<path-to-lovable-package-json>').dependencies).sort().join('\n'))")` (substitute the real `$LOVABLE/package.json` path) to get the exact list of new packages OLD needs: at minimum `@radix-ui/react-accordion`, `@radix-ui/react-alert-dialog`, `@radix-ui/react-aspect-ratio`, `@radix-ui/react-avatar`, `@radix-ui/react-checkbox`, `@radix-ui/react-collapsible`, `@radix-ui/react-context-menu`, `@radix-ui/react-dialog`, `@radix-ui/react-dropdown-menu` (OLD may already have this raw, confirm), `@radix-ui/react-hover-card`, `@radix-ui/react-label`, `@radix-ui/react-menubar`, `@radix-ui/react-navigation-menu`, `@radix-ui/react-popover`, `@radix-ui/react-progress`, `@radix-ui/react-radio-group`, `@radix-ui/react-scroll-area`, `@radix-ui/react-select`, `@radix-ui/react-separator`, `@radix-ui/react-slider`, `@radix-ui/react-slot`, `@radix-ui/react-switch`, `@radix-ui/react-tabs`, `@radix-ui/react-toggle`, `@radix-ui/react-toggle-group`, `@radix-ui/react-tooltip`, `@hookform/resolvers`, `cmdk`, `embla-carousel-react`, `input-otp`, `react-day-picker`, `react-hook-form`, `react-resizable-panels`, `recharts`, `sonner`, `tw-animate-css`, `vaul`, `zod`.

- [x] **Step 2: Install**

Run: `cd frontend && npm install <each package from Step 1 at the version pinned in $LOVABLE/package.json>`.

- [x] **Step 3: Copy primitive files**

Copy every file in `$LOVABLE/src/components/ui/` that doesn't already exist in `frontend/src/components/ui/` into `frontend/src/components/ui/`, unmodified except for import paths if OLD's `@/` alias resolves differently (check `frontend/vite.config.ts` / `tsconfig.json` `paths` — it already uses `@/` per the existing feature imports, so this should be a straight copy).

- [x] **Step 4: Replace OLD's `badge.tsx`, `button.tsx`, `card.tsx`, `input.tsx`**

Copy `$LOVABLE/src/components/ui/{badge,button,card,input}.tsx` over OLD's versions. Grep for every current usage of these four (`grep -rn "from '@/components/ui/button'" frontend/src`, etc.) and fix any prop/variant name that changed (e.g. if OLD called `<Button variant="ghost">` and Lovable's `button.tsx` renamed a variant, update call sites) — do this fix inline in this task, not deferred, since a broken `Button` import breaks every page.

- [x] **Step 5: Verify**

Run: `cd frontend && npm run build` — must complete with zero TypeScript errors. Any error naming a missing export means Step 4's grep missed a call site; fix it.

- [x] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/components/ui
git commit -m "feat(frontend): add shadcn/ui primitive components from Lovable export"
```

---

### Task 3: Shared `components/app/*` layer

**Files:**
- Create: `frontend/src/components/app/field.tsx` (adapt from `$LOVABLE/src/components/app/field.tsx`)
- Create: `frontend/src/components/app/states.tsx` (adapt from `$LOVABLE/src/components/app/states.tsx`)
- Create: `frontend/src/components/app/status.tsx` (adapt from `$LOVABLE/src/components/app/status.tsx`)
- Create: `frontend/src/components/app/confirm-dialog.tsx` (adapt from `$LOVABLE/src/components/app/confirm-dialog.tsx`, replacing OLD's `frontend/src/components/ui/confirm-dialog.tsx`)
- Create: `frontend/src/components/app/unsaved-guard.tsx` (copy from `$LOVABLE/src/components/app/unsaved-guard.tsx`, adapted from TanStack Router's blocker API to react-router-dom's `useBlocker`/`unstable_usePrompt` — react-router-dom v6.7+ ships `useBlocker`, confirm OLD's installed version in `frontend/package.json` supports it before writing this)
- Create: `frontend/src/components/app/incident-parts.tsx` (copy from `$LOVABLE/src/components/app/incident-parts.tsx`)
- Delete (after grep confirms zero remaining imports): `frontend/src/components/ui/{empty-state,error-banner,field-error,segmented,toast}.tsx`, `frontend/src/components/layout/page-header.tsx`
- Modify: `frontend/src/features/tickets/{priority-badge,sla-chip}.tsx`, `frontend/src/lib/status-labels.ts` (or wherever OLD's status/priority label mapping lives) — keep these files' *logic* (they encode real SLA/priority business rules OLD already has), but have them render through the new `Pill`/`PriorityMark`/`SlaChip` components from `components/app/status.tsx` instead of their own markup.

**Interfaces:**
- Consumes: `cn()` from `lib/utils.ts`; `Card`, `Badge` etc. from Task 2.
- Produces: `Field`, `Card`, `CardHeader`, `PageHeader` (from `field.tsx`); `EmptyState`, `ErrorState`, `FormBanner`, `RowsSkeleton`, `StaleBanner` (from `states.tsx`); `Pill`, `Avatar`, `PriorityMark`, `SlaChip`, `SlaChipFromState`, `StatusPill`, `PRIORITY_WORD` (from `status.tsx`); `ConfirmDialog` (from `confirm-dialog.tsx`); `UnsavedGuard` (from `unsaved-guard.tsx`); `Evidence`, `IncidentStatusPill` (from `incident-parts.tsx`). These are the names every route task below imports.

- [x] **Step 1: Copy `field.tsx`, `states.tsx`, `status.tsx`, `incident-parts.tsx` verbatim**

These four have no OLD equivalent with routing/data dependencies baked in — copy as-is into `frontend/src/components/app/`.

- [x] **Step 2: Adapt `confirm-dialog.tsx`**

Copy Lovable's version, but check every existing call site of OLD's `components/ui/confirm-dialog.tsx` (`grep -rn "confirm-dialog" frontend/src`) and match its prop signature — if OLD's callers pass e.g. `onConfirm: () => Promise<void>` and Lovable's expects `onConfirm: () => void`, keep the async signature (real network calls need it) and adjust the new component to await it.

- [x] **Step 3: Adapt `unsaved-guard.tsx` for react-router-dom**

Read `$LOVABLE/src/components/app/unsaved-guard.tsx` to see what it blocks on (likely a `when: boolean` prop + a confirm dialog on navigation attempt). Reimplement using react-router-dom's `useBlocker(when)` hook: when the blocker state is `"blocked"`, render the same confirm-leave UI Lovable used, calling `blocker.proceed()` / `blocker.reset()` instead of TanStack Router's navigation API.

- [x] **Step 4: Re-point priority/SLA rendering**

In `frontend/src/features/tickets/priority-badge.tsx` and `sla-chip.tsx`, keep the exported function names and prop types identical (so call sites in ticket pages don't need changes yet), but change the JSX body to call `PriorityMark`/`SlaChip` from `components/app/status.tsx` instead of rendering raw markup.

- [x] **Step 5: Grep-and-delete superseded files**

For each of `empty-state.tsx`, `error-banner.tsx`, `field-error.tsx`, `segmented.tsx`, `toast.tsx`, `page-header.tsx`: run `grep -rln "<old-file-basename>" frontend/src`. Do not delete yet — deletion happens per-file only after Task 4+ route work has replaced every remaining caller (track this as a running checklist in this task's PR description, not in code).

- [x] **Step 6: Verify**

Run: `cd frontend && npm run build`. Fix any type errors from the adapted signatures.

- [x] **Step 7: Commit**

```bash
git add frontend/src/components/app frontend/src/features/tickets/priority-badge.tsx frontend/src/features/tickets/sla-chip.tsx
git commit -m "feat(frontend): add shared app-level UI components (states, status, field, guards)"
```

---

### Task 4: App shell + navigation (top header layout)

**Files:**
- Modify: `frontend/src/components/layout/app-shell.tsx` (rewrite layout, keep the `<Outlet />`/children contract `App.tsx` relies on)
- Modify: `frontend/src/components/layout/nav-bar.tsx` (rewrite as horizontal top nav + `Sheet`-based mobile drawer, replacing the sidebar/bottom-tab-bar pattern)
- Reference: `$LOVABLE/src/components/app/app-shell.tsx`
- Reference (do not delete): `frontend/src/lib/theme.ts`, `frontend/src/features/command/command-palette.tsx` (its trigger button must still exist somewhere in the new header), `frontend/src/features/auth/auth-context.tsx` (for the user menu's `user`/`logout()`)

**Interfaces:**
- Consumes: `useAuth()` from `features/auth/auth-context.tsx` (existing hook, must not change its signature); `getStoredTheme`/`setThemePreference`/`applyTheme` from `lib/theme.ts`; primitives from Task 2 (`Sheet`, `SheetContent`, `DropdownMenu*`, `Avatar`).
- Produces: `AppShell` component with the same external contract `App.tsx`'s `<ProtectedRoute><AppShell /></ProtectedRoute>` expects (renders `<Outlet />` for nested routes).

- [x] **Step 1: Read Lovable's app-shell for structure**

Read `$LOVABLE/src/components/app/app-shell.tsx` in full — note its nav item list (labels per role), the mobile `Sheet` trigger, and the user menu.

- [x] **Step 2: Rewrite `nav-bar.tsx`**

Build the horizontal top nav using Lovable's markup/classes, but source the nav items from OLD's existing role-based item list (do not hardcode Lovable's mock nav items — reuse OLD's existing `NAV_ITEMS`-style config if `nav-bar.tsx` has one, updating labels per the copy changes noted in the mapping: "Incidents"→"Outages", "Knowledge base"→"Help articles", keep "/queue" URL but label it "Tickets" for agent roles). Wire the mobile hamburger to a `Sheet` from Task 2 instead of OLD's bottom tab bar.

- [x] **Step 3: Add the theme toggle and command palette trigger to the new user menu / header**

Keep both features (Global Constraints) — place the theme toggle switch inside the `DropdownMenu` user menu (matching where OLD currently has it), and keep a visible button that opens `features/command/command-palette.tsx` (⌘K), styled with the new `Button`/`DropdownMenu` primitives.

- [x] **Step 4: Rewrite `app-shell.tsx`**

Swap the sidebar/bottom-nav wrapper markup for the new top-header wrapper, keeping `<Outlet />` (or `children`, whatever OLD currently uses) in the same place so `App.tsx`'s route tree needs no structural change.

- [x] **Step 5: Verify**

Run: `cd frontend && npm run dev`, log in as each of CUSTOMER/AGENT/TEAM_LEAD/ADMIN test accounts, confirm the header shows the right nav items per role, the mobile drawer opens/closes, dark mode toggle still works, and the command palette still opens with ⌘K.

- [x] **Step 6: Commit**

```bash
git add frontend/src/components/layout/app-shell.tsx frontend/src/components/layout/nav-bar.tsx
git commit -m "feat(frontend): rebuild app shell as top-header layout per Lovable design"
```

---

### Task 5: Auth pages (login, register)

**Files:**
- Modify: `frontend/src/features/auth/login-page.tsx`, `frontend/src/features/auth/register-page.tsx`, `frontend/src/features/auth/auth-shell.tsx`
- Reference: `$LOVABLE/src/routes/login.tsx`, `$LOVABLE/src/routes/register.tsx`, `$LOVABLE/src/components/app/auth-layout.tsx`

**Interfaces:**
- Consumes: OLD's existing `useAuth().login(email, password)` / `.register(...)` (real network calls — do not touch their signatures), `Field` and `FormBanner` from Task 1/3.
- Produces: same page components, same route paths (`/login`, `/register`).

- [x] **Step 1: Rewrite `auth-shell.tsx` markup to match `auth-layout.tsx`**, keeping it a wrapper that takes `children` (or whatever prop OLD's version currently takes).

- [x] **Step 2: Rewrite `login-page.tsx` form markup** using `Field`, `Input` (Task 2), password show/hide toggle (`Eye`/`EyeOff` from `lucide-react`, already a dependency), and `FormBanner` for error display — but keep the existing `onSubmit` handler calling OLD's real `useAuth().login`, do not switch to react-hook-form/zod unless it's a small lift; if OLD's current login page already manages simple two-field state, keep that state management and only change the JSX/classes, to minimize risk of breaking the real submit flow. (RHF+zod adoption for this form is optional polish, not required for the reskin.)

- [x] **Step 3: Rewrite `register-page.tsx`** the same way.

- [x] **Step 4: Verify**

Run: `cd frontend && npm run dev`, submit login with a real test account (wrong password → see error banner render correctly; correct password → redirects per `RoleHome`), then log out and register a new test account.

- [x] **Step 5: Commit**

```bash
git add frontend/src/features/auth
git commit -m "style(frontend): reskin login and register pages"
```

---

### Task 6: Ticket pages (customer: my-tickets, new-ticket, ticket-detail)

**Files:**
- Modify: `frontend/src/App.tsx` (route path `/my-tickets` → `/tickets`, update `RoleHome`'s redirect target)
- Modify: `frontend/src/features/tickets/my-tickets-page.tsx`, `new-ticket-page.tsx`, `ticket-detail-page.tsx` (customer-facing rendering path only — the agent workspace view is Task 7)
- Reference: `$LOVABLE/src/routes/_shell.tickets.index.tsx`, `_shell.tickets.new.tsx`, `_shell.tickets.$ticketId.tsx`
- Grep and fix: every `<Link to="/my-tickets">` / `navigate('/my-tickets')` across `frontend/src` (nav-bar already updated in Task 4 if it references the old path; also check `features/demo/*`, `features/command/command-palette.tsx`)

**Interfaces:**
- Consumes: OLD's existing `features/tickets/api.ts` fetchers (unchanged), `UnsavedGuard` and `Field`/`FormBanner` (Task 3).
- Produces: same components, new route path `/tickets`.

- [x] **Step 1: Rename the route**

In `App.tsx`, change `<Route path="/my-tickets" ...>` to `<Route path="/tickets" ...>` and update `RoleHome`'s `user.role === 'CUSTOMER' ? '/my-tickets' : '/queue'` to `'/tickets'`.

- [x] **Step 2: Grep for stale path references**

Run: `grep -rn "/my-tickets" frontend/src` — fix every hit.

- [x] **Step 3: Re-skin `my-tickets-page.tsx`**

Swap markup for `Card`/`PageHeader` (Task 3 `field.tsx`) as the page frame, `Pill`/`StatusPill` (Task 3 `status.tsx`) for ticket status badges, and `EmptyState`/`ErrorState`/`RowsSkeleton`/`StaleBanner` (Task 3 `states.tsx`) for the loading/empty/error/stale states — keep the existing `useQuery`/fetch call and list-rendering logic untouched, this is a markup-and-className swap only.

- [x] **Step 4: Re-skin `new-ticket-page.tsx`**

Swap form field markup for `Field`, wrap the form in `UnsavedGuard` (Task 3) — new UX addition, OLD didn't have this. Keep the existing submit handler/mutation.

- [x] **Step 5: Re-skin the customer branch of `ticket-detail-page.tsx`**

If OLD's `ticket-detail-page.tsx` already branches on role internally (render a simpler view for CUSTOMER vs a richer one for AGENT/TEAM_LEAD/ADMIN), re-skin only the CUSTOMER branch here using Lovable's `_shell.tickets.$ticketId.tsx` as the reference; the agent-facing branch is Task 7. If it does NOT currently branch (single view for all roles), leave it fully alone in this task and do the split in Task 7 instead — do not duplicate work.

- [x] **Step 6: Verify**

Run: `cd frontend && npm run dev`, log in as a CUSTOMER test account, view `/tickets`, create a new ticket (confirm the unsaved-guard fires if you navigate away mid-form), open a ticket detail.

- [x] **Step 7: Commit**

```bash
git add frontend/src/App.tsx frontend/src/features/tickets
git commit -m "style(frontend): reskin customer ticket pages, rename /my-tickets to /tickets"
```

---

### Task 7: Agent queue + ticket workspace (agent/team-lead/admin)

**Files:**
- Modify: `frontend/src/features/tickets/agent-queue-page.tsx`
- Modify: `frontend/src/features/tickets/ticket-detail-page.tsx` (agent-facing branch) and its subcomponents: `ai-assist-panel.tsx`, `citation-popover.tsx`, `customer-context.tsx`, `message-bubble.tsx`, `message-composer.tsx`, `status-dropdown.tsx`, `assign-control.tsx`, `sla-chip.tsx` (already touched in Task 3, revisit if needed), `priority-badge.tsx` (same), `priority-rationale.tsx`, `response-promise.tsx`, `incident-banner.tsx`
- Reference: `$LOVABLE/src/routes/_shell.queue.index.tsx`, `_shell.queue.$ticketId.tsx` (555 lines — read in full, it inlines what OLD keeps as separate subcomponents)

**Interfaces:**
- Consumes: OLD's existing ticket-workspace data hooks/mutations (unchanged) — do not adopt Lovable's inline data-fetching, only its visual structure.
- Produces: same subcomponent file set and exported names OLD already has (keep the modular split — do not flatten into one file, per the exploration report's recommendation), just re-skinned with `Popover`, `Select`, `Collapsible` (Task 2) and `Pill`/`SlaChip`/`PriorityMark` (Task 3).

- [x] **Step 1: Re-skin `agent-queue-page.tsx`**

Reference `_shell.queue.index.tsx`'s `Table` usage and filter UI; keep OLD's existing `useSearchParams`-driven filter state (do not migrate to zod `validateSearch`, that's a TanStack Router API OLD doesn't have — react-router-dom's `useSearchParams` already does the equivalent job, just restyle the filter controls with `Select`/`Tabs` from Task 2).

- [x] **Step 2: Re-skin `ai-assist-panel.tsx` and `citation-popover.tsx`**

Use `Popover`/`Collapsible` (Task 2) for citation display, matching the interaction pattern in `_shell.queue.$ticketId.tsx`'s inlined AI panel section — keep OLD's existing props (`ticketId`, `onInsertDraft`, whatever the current signature is) unchanged.

- [x] **Step 3: Re-skin `message-bubble.tsx`, `message-composer.tsx`, `customer-context.tsx`, `status-dropdown.tsx`, `assign-control.tsx`, `priority-rationale.tsx`, `response-promise.tsx`, `incident-banner.tsx`**

One at a time: open the corresponding section of `_shell.queue.$ticketId.tsx` for visual reference, restyle each OLD file's JSX/classes, run `npm run build` after each to catch prop-type breaks early, keep every existing prop and callback signature.

- [x] **Step 4: Re-skin the agent-facing branch of `ticket-detail-page.tsx`**

Wire the re-skinned subcomponents from Steps 2–3 back into the page, using `status-dropdown.tsx` (Task 2's `Select`) for status changes and `Popover`/`Collapsible` for the AI assist panel layout.

- [x] **Step 5: Verify**

Run: `cd frontend && npm run dev`, log in as an AGENT test account, open `/queue`, filter tickets, open a ticket, exercise: AI-assist draft request, citation popover, status change, priority display, SLA chip. Confirm every real API call still round-trips (network tab).

- [x] **Step 6: Commit**

```bash
git add frontend/src/features/tickets
git commit -m "style(frontend): reskin agent queue and ticket workspace"
```

---

### Task 8: Incidents (list + detail)

**Files:**
- Modify: `frontend/src/features/incidents/incident-board-page.tsx`, `incident-card.tsx`, `incident-detail-page.tsx`
- Reference: `$LOVABLE/src/routes/_shell.incidents.index.tsx`, `_shell.incidents.$incidentId.tsx`, `$LOVABLE/src/components/app/incident-parts.tsx` (already copied in Task 3)
- Note: `arrival-sparkline.tsx` has no Lovable equivalent — keep it as-is, just restyle its container/wrapper to match the new card style, do not redesign the chart itself.

**Interfaces:**
- Consumes: `Evidence`, `IncidentStatusPill` from `components/app/incident-parts.tsx` (Task 3); `ConfirmDialog` (Task 3) for any incident-resolution confirmation flow.
- Produces: same page components; nav label already changed to "Outages" in Task 4 (this task just re-skins the page content, not the nav).

- [x] **Step 1: Re-skin `incident-board-page.tsx`**

Use `Tabs` (Task 2) for the PROPOSED/LIVE/RESOLVED filter, keeping OLD's existing tab-state management (react-router-dom `useSearchParams`, not TanStack's `validateSearch`).

- [x] **Step 2: Re-skin `incident-card.tsx`**, keeping `arrival-sparkline.tsx` embedded unchanged, restyling only the surrounding card chrome with `Card`/`Pill`.

- [x] **Step 3: Re-skin `incident-detail-page.tsx`**, using `Evidence`/`IncidentStatusPill` from `incident-parts.tsx` and `ConfirmDialog` for any action confirmations.

- [x] **Step 4: Verify**

Run: `cd frontend && npm run dev`, log in as AGENT/TEAM_LEAD, view `/incidents`, switch tabs, open a detail page.

- [x] **Step 5: Commit**

```bash
git add frontend/src/features/incidents
git commit -m "style(frontend): reskin incidents list and detail pages"
```

---

### Task 9: Knowledge base (list + new document)

**Files:**
- Modify: `frontend/src/App.tsx` (no path rename needed here — `/knowledge`, `/knowledge/new` stay the same per the mapping)
- Modify: `frontend/src/features/knowledge/knowledge-base-page.tsx`, `document-drawer.tsx`, `knowledge-document-form-page.tsx`
- Reference: `$LOVABLE/src/routes/_shell.knowledge.index.tsx`, `_shell.knowledge.new.tsx`
- Check: `frontend/src/lib/labels.ts`'s `SOURCE_LABEL`-equivalent copy against Lovable's "Guide for staff" / "Help article" / "Solved ticket" strings — align copy if the product owner wants the new wording (flag this as a copy decision, don't silently change user-facing label text without confirming — if unsure, keep OLD's existing label strings and only change layout/markup).

**Interfaces:**
- Consumes: OLD's existing `features/knowledge/api.ts` fetchers, `UnsavedGuard` (Task 3), `Field` (Task 3).

- [x] **Step 1: Re-skin `knowledge-base-page.tsx`** using `Card`/`Table` for the document list.

- [x] **Step 2: Re-skin `document-drawer.tsx`** using `Sheet` or `Drawer` (Task 2) — check which one matches Lovable's interaction (side-panel vs bottom-sheet on mobile) and use that.

- [x] **Step 3: Re-skin `knowledge-document-form-page.tsx`**, wrapping in `UnsavedGuard`, using `Field`/`Textarea` (Task 2) for the form body.

- [x] **Step 4: Verify**

Run: `cd frontend && npm run dev`, log in as AGENT (view) and ADMIN (create new document), confirm both flows.

- [x] **Step 5: Commit**

```bash
git add frontend/src/features/knowledge
git commit -m "style(frontend): reskin knowledge base pages"
```

---

### Task 10: Settings and Evaluation dashboard (admin)

**Files:**
- Modify: `frontend/src/App.tsx` (route paths `/admin/settings` → `/settings`, `/admin/evaluation` → `/evaluation`)
- Modify: `frontend/src/features/settings/settings-page.tsx`, `frontend/src/features/eval/eval-dashboard-page.tsx`
- Reference: `$LOVABLE/src/routes/_shell.settings.tsx`, `_shell.evaluation.tsx`
- Grep and fix: `grep -rn "/admin/settings\|/admin/evaluation" frontend/src`

**Interfaces:**
- Consumes: OLD's existing `features/settings/api.ts` fetchers, `Tabs` (Task 2) for the sla/calendar/ai tab groups (keep as `useSearchParams`-driven state, matching the pattern used elsewhere in this plan rather than TanStack's `validateSearch`).

- [x] **Step 1: Rename routes in `App.tsx`**, update `RoleHome`/nav references if any point at the old admin paths.

- [x] **Step 2: Re-skin `settings-page.tsx`** tab-by-tab (sla/calendar/ai), using `Tabs`, `Select`, `Switch`, `Calendar` (Task 2) as appropriate per tab content.

- [x] **Step 3: Re-skin `eval-dashboard-page.tsx`** using `Chart` (Task 2, recharts-based) for any metrics visualizations, `Card` for summary tiles.

- [x] **Step 4: Verify**

Run: `cd frontend && npm run dev`, log in as ADMIN, visit `/settings` (all three tabs) and `/evaluation`.

- [x] **Step 5: Commit**

```bash
git add frontend/src/App.tsx frontend/src/features/settings frontend/src/features/eval
git commit -m "style(frontend): reskin settings and evaluation pages, rename admin routes"
```

---

### Task 11: Not-found page, cleanup, and final sweep

**Files:**
- Modify: `frontend/src/App.tsx` (`NotFoundPage`)
- Delete: `frontend/src/components/ui/{empty-state,error-banner,field-error,segmented,toast}.tsx`, `frontend/src/components/layout/page-header.tsx` (only if Step 1's grep confirms zero remaining references)
- Modify: `frontend/src/features/board/board-page.tsx` (Task 2/3 primitive/token pass only — no layout redesign, per Global Constraints)

**Interfaces:** none new — this task only removes dead code and does a final consistency pass.

- [x] **Step 1: Confirm superseded files are unused**

Run: `grep -rln "empty-state\|error-banner\|field-error\|components/ui/segmented\|components/ui/toast\|layout/page-header" frontend/src` — every hit must be inside the files being deleted themselves (self-reference) or nonexistent. If any real caller remains, re-skin that caller now instead of deferring further.

- [x] **Step 2: Delete the confirmed-dead files**

- [x] **Step 3: Re-skin `NotFoundPage`** in `App.tsx` using `EmptyState` from `components/app/states.tsx`.

- [x] **Step 4: Pass over `board-page.tsx`**

Swap any remaining OLD primitive imports (`components/ui/badge`, `card`, etc.) to confirm it still compiles against the Task 2 replacements — no layout change.

- [x] **Step 5: Full verify**

Run: `cd frontend && npm run build && npm run lint` (or `oxlint` per `frontend/.oxlintrc.json`) — zero errors. Then run `npm run dev` and click through every route in the nav as each of the four roles once, end to end.

- [x] **Step 6: Commit**

```bash
git add -A frontend
git commit -m "chore(frontend): remove superseded UI components, final reskin sweep"
```

---

## Explicitly out of scope for this plan

- `features/command/command-palette.tsx`, `features/command/shortcuts-sheet.tsx`, `features/demo/*`, `components/layout/route-a11y.tsx` — no Lovable redesign exists to copy; restyle by hand in a follow-up if desired.
- Adopting Lovable's `lib/queries.ts` (`qk` query-key factory + `useInvalidate()`) pattern — worth doing, but it's a data-layer refactor orthogonal to the visual reskin and should be its own plan so this one stays reviewable in pure UI terms.
- Migrating remaining forms to `react-hook-form` + `zod` beyond what's naturally touched — OLD's existing manual form-state code is functionally fine; only adopt RHF/zod where a task above calls for it explicitly.
- `/board` Kanban redesign — Lovable substituted a different concept (`_shell.dashboard.tsx`) rather than redesigning the board; a real redesign here needs a product decision, not a mechanical port.
