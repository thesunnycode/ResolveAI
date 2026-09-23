import { KanbanSquare, Link2, Loader2, Search, Sparkles } from 'lucide-react'
import * as React from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { Segmented, StatTile } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/features/auth/auth-context'
import { PriorityBar, PriorityLabel } from '@/features/tickets/priority-badge'
import { SlaChip } from '@/features/tickets/sla-chip'
import type { Priority, TicketStatus, TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useBoardTickets } from './api'

type GroupBy = 'team' | 'status'
type PriorityFilter = 'all' | 'P1' | 'P2' | 'P3' | 'P4'

interface Column {
  key: string
  title: string
  caption: string
  tone?: 'ai' | 'default'
  tickets: TicketSummary[]
}

const AWAITING_TRIAGE = '__awaiting__'
const DONE: TicketStatus[] = ['RESOLVED', 'CLOSED']

const STATUS_COLUMNS: { key: string; title: string; caption: string; statuses: TicketStatus[] }[] = [
  { key: 'new', title: 'New', caption: 'Triaged, nobody on it yet', statuses: ['OPEN', 'TRIAGED'] },
  { key: 'assigned', title: 'Assigned', caption: 'Has an owner, not started', statuses: ['ASSIGNED'] },
  { key: 'progress', title: 'In progress', caption: 'Being worked', statuses: ['IN_PROGRESS'] },
  {
    key: 'waiting',
    title: 'Waiting',
    caption: 'On the customer or a third party — clock paused',
    statuses: ['WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY'],
  },
  { key: 'done', title: 'Resolved', caption: 'Resolved or closed', statuses: DONE },
]

const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: 'Open',
  TRIAGED: 'Triaged',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In progress',
  WAITING_ON_CUSTOMER: 'Waiting on customer',
  PENDING_THIRD_PARTY: 'Third party',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

const PRIORITY_ORDER: Record<Priority, number> = { P1: 0, P2: 1, P3: 2, P4: 3, UNTRIAGED: 4 }

function clockOf(t: TicketSummary) {
  return t.sla?.resolution ?? t.sla?.firstResponse ?? null
}

/** Either clock: a missed first reply is a breach even while resolution is still in time. */
function isBreached(t: TicketSummary) {
  return t.sla?.firstResponse?.state === 'BREACHED' || t.sla?.resolution?.state === 'BREACHED'
}

function isAtRisk(t: TicketSummary) {
  const running = [t.sla?.firstResponse, t.sla?.resolution].filter((c) => c?.state === 'RUNNING')
  return !isBreached(t) && running.some((c) => c?.atRisk)
}

/** Most urgent first: breached, then at risk, then by priority, then oldest. */
function byUrgency(a: TicketSummary, b: TicketSummary) {
  const rank = (t: TicketSummary) => {
    if (isBreached(t)) return 0
    if (isAtRisk(t)) return 1
    return 2
  }
  return (
    rank(a) - rank(b) ||
    PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] ||
    a.createdAt.localeCompare(b.createdAt)
  )
}

function initials(name: string) {
  return name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
}

function BoardCard({ t, groupBy }: { t: TicketSummary; groupBy: GroupBy }) {
  const clock = clockOf(t)
  const untriaged = t.priority === 'UNTRIAGED'
  return (
    <Link
      to={`/tickets/${t.id}`}
      className="group flex gap-2.5 rounded-lg border border-border bg-surface-2/40 p-3 transition-all hover:border-border-strong hover:bg-surface-2/80 hover:shadow-card"
    >
      <PriorityBar priority={t.priority} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11.5px] tabular-nums text-text-subtle">{t.reference}</span>
          {untriaged ? (
            <span className="inline-flex items-center gap-1 text-[11px] text-text-subtle">
              <Loader2 className="size-3 animate-spin" aria-hidden /> triaging
            </span>
          ) : (
            <PriorityLabel priority={t.priority} />
          )}
        </div>
        <p className="mt-1 line-clamp-2 text-[13px] font-medium leading-snug text-text">{t.subject}</p>

        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {t.category && (
            <span
              className="inline-flex items-center gap-1 rounded-md bg-primary-bg px-1.5 py-0.5 text-[11px] font-medium text-primary"
              title="Category set by AI triage"
            >
              <Sparkles className="size-2.5" aria-hidden />
              {t.category}
            </span>
          )}
          {groupBy === 'team' ? (
            <span className="rounded-md bg-surface-2 px-1.5 py-0.5 text-[11px] text-text-muted">
              {STATUS_LABEL[t.status]}
            </span>
          ) : (
            t.team && (
              <span className="rounded-md bg-surface-2 px-1.5 py-0.5 text-[11px] text-text-muted">{t.team.name}</span>
            )
          )}
          {t.incidentRef && (
            <span className="inline-flex items-center gap-1 rounded-md bg-warning-bg px-1.5 py-0.5 text-[11px] font-medium text-warning">
              <Link2 className="size-2.5" aria-hidden />
              {t.incidentRef}
            </span>
          )}
        </div>

        <div className="mt-2.5 flex items-center justify-between gap-2 border-t border-border pt-2">
          {t.assignee ? (
            <span className="flex min-w-0 items-center gap-1.5 text-[12px] text-text-muted">
              <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-surface-2 text-[9px] font-semibold text-text">
                {initials(t.assignee.fullName)}
              </span>
              <span className="truncate">{t.assignee.fullName}</span>
            </span>
          ) : (
            <span className="text-[12px] text-warning">Unassigned</span>
          )}
          {t.sla?.firstResponse?.state === 'BREACHED' && t.sla.resolution?.state !== 'BREACHED' ? (
            <SlaChip clock={t.sla.firstResponse} label="1st reply" />
          ) : (
            clock && <SlaChip clock={clock} />
          )}
        </div>
      </div>
    </Link>
  )
}

function BoardColumn({ col, groupBy }: { col: Column; groupBy: GroupBy }) {
  const breached = col.tickets.filter(isBreached).length
  const atRisk = col.tickets.filter(isAtRisk).length
  return (
    <section
      className={cn(
        'glass flex max-h-[calc(100svh-340px)] min-h-[260px] w-[300px] shrink-0 flex-col rounded-xl',
        col.tone === 'ai' && 'border-primary/30',
      )}
      aria-label={col.title}
    >
      <header className="border-b border-border px-3.5 py-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="flex items-center gap-1.5 text-[13.5px] font-semibold text-text">
            {col.tone === 'ai' && <Sparkles className="size-3.5 text-primary" aria-hidden />}
            {col.title}
          </h2>
          <span className="rounded-md bg-surface-2 px-1.5 py-px text-[11.5px] font-medium tabular-nums text-text-muted">
            {col.tickets.length}
          </span>
        </div>
        <p className="mt-0.5 text-[11.5px] text-text-subtle">{col.caption}</p>
        {(breached > 0 || atRisk > 0) && (
          <div className="mt-2 flex gap-1.5">
            {breached > 0 && (
              <span className="rounded-md bg-danger-bg px-1.5 py-0.5 text-[11px] font-medium text-danger">
                {breached} breached
              </span>
            )}
            {atRisk > 0 && (
              <span className="rounded-md bg-warning-bg px-1.5 py-0.5 text-[11px] font-medium text-warning">
                {atRisk} at risk
              </span>
            )}
          </div>
        )}
      </header>
      <div className="flex-1 space-y-2 overflow-y-auto p-2.5">
        {col.tickets.length === 0 ? (
          <p className="px-1 py-6 text-center text-[12px] text-text-subtle">Nothing here</p>
        ) : (
          col.tickets.map((t) => <BoardCard key={t.id} t={t} groupBy={groupBy} />)
        )}
      </div>
    </section>
  )
}

export function BoardPage() {
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  // A team lead only sees their own team's tickets, so "by team" would be one column.
  const defaultGroup: GroupBy = user?.role === 'ADMIN' ? 'team' : 'status'
  const groupBy = (params.get('group') as GroupBy) ?? defaultGroup
  const priority = (params.get('priority') as PriorityFilter) ?? 'all'
  const showDone = params.get('done') === '1'
  const [search, setSearch] = React.useState('')
  const { data, isLoading, isError, refetch, isFetching } = useBoardTickets()

  function setParam(key: string, value: string | null) {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value === null) next.delete(key)
      else next.set(key, value)
      return next
    })
  }

  const all = data?.tickets ?? []
  const q = search.trim().toLowerCase()
  const visible = all
    .filter((t) => showDone || !DONE.includes(t.status))
    .filter((t) => priority === 'all' || t.priority === priority)
    .filter((t) => !q || t.subject.toLowerCase().includes(q) || t.reference.toLowerCase().includes(q))
    .sort(byUrgency)

  let columns: Column[]
  if (groupBy === 'team') {
    const awaiting = visible.filter((t) => t.priority === 'UNTRIAGED')
    const byTeam = new Map<string, TicketSummary[]>()
    for (const t of visible) {
      if (t.priority === 'UNTRIAGED') continue
      const name = t.team?.name ?? 'Unrouted'
      if (!byTeam.has(name)) byTeam.set(name, [])
      byTeam.get(name)!.push(t)
    }
    columns = [
      {
        key: AWAITING_TRIAGE,
        title: 'AI triaging',
        caption: 'Being classified and routed right now',
        tone: 'ai',
        tickets: awaiting,
      },
      ...[...byTeam.entries()]
        .sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]))
        .map(([name, tickets]) => ({
          key: name,
          title: name,
          caption:
            name === 'General'
              ? 'No specialist team matched — routed to the catch-all'
              : `Routed here by AI triage`,
          tickets,
        })),
    ]
  } else {
    columns = STATUS_COLUMNS.filter((c) => showDone || c.key !== 'done').map((c) => ({
      key: c.key,
      title: c.title,
      caption: c.caption,
      tickets: visible.filter((t) => c.statuses.includes(t.status)),
    }))
  }

  const open = all.filter((t) => !DONE.includes(t.status))
  const breached = open.filter(isBreached).length
  const atRisk = open.filter(isAtRisk).length
  const unassigned = open.filter((t) => !t.assignee && t.priority !== 'UNTRIAGED').length
  const triaging = open.filter((t) => t.priority === 'UNTRIAGED').length

  return (
    <Page width="full">
      <PageHeader
        eyebrow={<Eyebrow>{isFetching && !isLoading ? 'Updating…' : 'Live · refreshes every 30s'}</Eyebrow>}
        title="Board"
        description={
          user?.role === 'ADMIN'
            ? 'Every open ticket and where AI triage routed it. Most urgent at the top of each column.'
            : "Your team's tickets by stage. Most urgent at the top of each column."
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <StatTile label="Open" value={open.length} hint={data?.truncated ? 'First 500 shown' : 'Not yet resolved'} />
        <StatTile label="AI triaging" value={triaging} hint="Being classified now" />
        <StatTile label="Unassigned" value={unassigned} tone={unassigned ? 'warning' : 'neutral'} hint="Routed, no owner" />
        <StatTile label="At risk" value={atRisk} tone={atRisk ? 'warning' : 'neutral'} hint="Predicted to breach" />
        <StatTile label="Breached" value={breached} tone={breached ? 'danger' : 'success'} hint={breached ? 'Past target' : 'None'} />
      </div>

      <div className="mb-4 flex flex-col gap-3 xl:flex-row xl:items-center">
        <Segmented
          value={groupBy}
          onChange={(v) => setParam('group', v === defaultGroup ? null : v)}
          options={[
            { value: 'team', label: 'By team' },
            { value: 'status', label: 'By status' },
          ]}
        />
        <Segmented
          value={priority}
          onChange={(v) => setParam('priority', v === 'all' ? null : v)}
          options={[
            { value: 'all', label: 'All' },
            { value: 'P1', label: 'P1' },
            { value: 'P2', label: 'P2' },
            { value: 'P3', label: 'P3' },
            { value: 'P4', label: 'P4' },
          ]}
        />
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 z-10 size-4 -translate-y-1/2 text-text-subtle" aria-hidden />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter by subject or reference…"
            aria-label="Filter tickets"
            className="glass h-10 w-full rounded-lg pl-9 pr-3 text-[14px] text-text placeholder:text-text-subtle focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary-bg"
          />
        </div>
        <label className="flex shrink-0 cursor-pointer items-center gap-2 text-[13px] text-text-muted">
          <input
            type="checkbox"
            checked={showDone}
            onChange={(e) => setParam('done', e.target.checked ? '1' : null)}
            className="size-4 accent-[var(--color-primary)]"
          />
          Show resolved
        </label>
      </div>

      {isError && <ErrorBanner onRetry={() => refetch()} />}

      {isLoading ? (
        <div className="flex gap-4 overflow-hidden">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-[420px] w-[300px] shrink-0 rounded-xl" />
          ))}
        </div>
      ) : all.length === 0 ? (
        <div className="glass rounded-xl">
          <EmptyState
            icon={KanbanSquare}
            title="No tickets yet"
            description="As tickets arrive, AI triage classifies and routes each one — this board shows where they all went."
          />
        </div>
      ) : (
        <div className="-mx-5 flex gap-4 overflow-x-auto px-5 pb-4 sm:-mx-8 sm:px-8">
          {columns.map((col) => (
            <BoardColumn key={col.key} col={col} groupBy={groupBy} />
          ))}
        </div>
      )}
    </Page>
  )
}
