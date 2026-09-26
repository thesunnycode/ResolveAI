import * as DropdownMenu from '@radix-ui/react-dropdown-menu'
import { GripVertical, KanbanSquare, Link2, Loader2, MoreHorizontal, Search, Sparkles } from 'lucide-react'
import * as React from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { ConfirmDialog } from '@/components/app/confirm-dialog'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { MetricStrip, Segmented } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { PriorityGlyph, PriorityLabel } from '@/features/tickets/priority-badge'
import { SlaChip } from '@/features/tickets/sla-chip'
import { primarySlaClock } from '@/features/tickets/sla-utils'
import { REASON_REQUIRED, STATUS_LABEL } from '@/features/tickets/status-labels'
import { allowedTransitionsFrom } from '@/features/tickets/ticket-state-machine'
import { useStatusChanger } from '@/features/tickets/use-status-change'
import { humanize } from '@/lib/labels'
import type { Priority, TicketStatus, TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useBoardTickets } from './api'

type GroupBy = 'team' | 'status'
type PriorityFilter = 'all' | 'P1' | 'P2' | 'P3' | 'P4'

interface Column {
  key: string
  title: string
  caption: string
  /** Shown instead of a bare "Nothing here" (audit F8). */
  emptyText: string
  tone?: 'ai' | 'default'
  /** Status columns only: the statuses a card dropped here can move to, in preference order. */
  statuses?: TicketStatus[]
  tickets: TicketSummary[]
}

const AWAITING_TRIAGE = '__awaiting__'
const DONE: TicketStatus[] = ['RESOLVED', 'CLOSED']

const STATUS_COLUMNS: {
  key: string
  title: string
  caption: string
  emptyText: string
  statuses: TicketStatus[]
}[] = [
  {
    key: 'new',
    title: 'New',
    caption: 'Triaged, nobody on it yet',
    emptyText: 'Every triaged ticket has an owner.',
    statuses: ['OPEN', 'TRIAGED'],
  },
  {
    key: 'assigned',
    title: 'Assigned',
    caption: 'Has an owner, not started',
    emptyText: 'Nothing is waiting to be picked up.',
    statuses: ['ASSIGNED'],
  },
  {
    key: 'progress',
    title: 'In progress',
    caption: 'Being worked',
    emptyText: 'Nobody is actively working a ticket right now.',
    statuses: ['IN_PROGRESS'],
  },
  {
    key: 'waiting',
    title: 'Waiting',
    caption: 'On the customer or a third party — clock paused',
    emptyText: 'Nothing is waiting on customers or third parties — every clock here is running.',
    statuses: ['WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY'],
  },
  {
    key: 'done',
    title: 'Resolved',
    caption: 'Resolved or closed',
    emptyText: 'Nothing resolved yet.',
    statuses: DONE,
  },
]

const PRIORITY_ORDER: Record<Priority, number> = { P1: 0, P2: 1, P3: 2, P4: 3, UNTRIAGED: 4 }

function isBreached(t: TicketSummary) {
  return t.sla?.firstResponse?.state === 'BREACHED' || t.sla?.resolution?.state === 'BREACHED'
}

function isAtRisk(t: TicketSummary) {
  const running = [t.sla?.firstResponse, t.sla?.resolution].filter((c) => c?.state === 'RUNNING')
  return !isBreached(t) && running.some((c) => c?.atRisk)
}

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

/** Where a card dropped on `col` would go: the first of the column's statuses it may reach. */
function targetFor(t: TicketSummary, col: Column): TicketStatus | null {
  if (!col.statuses || col.statuses.includes(t.status)) return null
  const allowed = allowedTransitionsFrom(t.status)
  return col.statuses.find((s) => allowed.includes(s)) ?? null
}

function BoardCard({
  t,
  groupBy,
  canMove,
  moveTargets,
  onMove,
}: {
  t: TicketSummary
  groupBy: GroupBy
  canMove: boolean
  moveTargets: TicketStatus[]
  onMove: (t: TicketSummary, to: TicketStatus) => void
}) {
  // One clock rule everywhere (audit U17): the first-response clock until it is met.
  const primary = primarySlaClock(t.sla)
  const untriaged = t.priority === 'UNTRIAGED'
  return (
    <div
      draggable={canMove}
      onDragStart={(e) => {
        e.dataTransfer.setData('text/ticket-id', String(t.id))
        e.dataTransfer.effectAllowed = 'move'
      }}
      className={cn(
        'group relative flex gap-2.5 rounded-lg border border-border bg-surface p-3 transition-colors',
        'hover:border-border-strong has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-primary',
        canMove && 'cursor-grab active:cursor-grabbing',
      )}
    >
      {canMove && (
        <GripVertical className="absolute left-0.5 top-1/2 size-3.5 -translate-y-1/2 text-text-subtle opacity-0 group-hover:opacity-100" aria-hidden />
      )}
      <PriorityGlyph priority={t.priority} className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs tabular-nums text-text-subtle">{t.reference}</span>
          {untriaged ? (
            <span className="inline-flex items-center gap-1 text-xs text-text-subtle">
              <Loader2 className="size-3 animate-spin" aria-hidden /> triaging
            </span>
          ) : (
            <PriorityLabel priority={t.priority} />
          )}
        </div>
        <Link
          to={`/tickets/${t.id}`}
          draggable={false}
          className="mt-1 line-clamp-2 text-sm font-medium leading-snug text-text outline-none after:absolute after:inset-0 after:content-['']"
        >
          {t.subject}
        </Link>

        {/* Calmer metadata (audit V3): plain text, one coloured chip - the incident. */}
        <p className="mt-1.5 flex flex-wrap items-center gap-x-1.5 text-xs text-text-muted">
          {t.category && (
            <span className="inline-flex items-center gap-1" title="Category set by AI triage">
              <Sparkles className="size-3 text-text-subtle" aria-hidden />
              {humanize(t.category)}
            </span>
          )}
          {groupBy === 'team' ? (
            <span>· {STATUS_LABEL[t.status]}</span>
          ) : (
            t.team && <span>· {t.team.name}</span>
          )}
          {t.incidentRef && (
            <span className="relative inline-flex items-center gap-1 rounded-md bg-warning-bg px-1.5 py-0.5 font-medium text-warning">
              <Link2 className="size-3" aria-hidden />
              {t.incidentRef}
            </span>
          )}
        </p>

        <div className="mt-2 flex flex-wrap items-center justify-between gap-x-2 gap-y-1 border-t border-border pt-2">
          {t.assignee ? (
            <span className="flex min-w-0 items-center gap-1.5 text-xs text-text-muted">
              <span aria-hidden className="flex size-6 shrink-0 items-center justify-center rounded-full bg-surface-2 text-xs font-semibold text-text">
                {initials(t.assignee.fullName)}
              </span>
              <span className="truncate">{t.assignee.fullName}</span>
            </span>
          ) : (
            <span className="whitespace-nowrap text-xs text-warning">Unassigned</span>
          )}
          <span className="ml-auto flex min-w-0 items-center gap-1">
            {primary && <SlaChip clock={primary.clock} label={primary.label} />}
            {/* The keyboard/touch way to move a card - drag is mouse-only. */}
            {canMove && moveTargets.length > 0 && (
              <DropdownMenu.Root>
                <DropdownMenu.Trigger
                  aria-label={`Move ${t.reference}`}
                  className="relative z-10 -mr-1 flex size-7 items-center justify-center rounded-md text-text-subtle hover:bg-surface-2 hover:text-text"
                >
                  <MoreHorizontal className="size-4" aria-hidden />
                </DropdownMenu.Trigger>
                <DropdownMenu.Portal>
                  <DropdownMenu.Content align="end" sideOffset={4} className="z-50 min-w-44 rounded-lg border border-border bg-surface p-1 shadow-popover">
                    <DropdownMenu.Label className="px-2.5 py-1.5 text-xs text-text-subtle">Move to</DropdownMenu.Label>
                    {moveTargets.map((s) => (
                      <DropdownMenu.Item
                        key={s}
                        onSelect={() => onMove(t, s)}
                        className="cursor-pointer rounded-md px-2.5 py-1.5 text-sm text-text outline-none data-[highlighted]:bg-surface-2"
                      >
                        {STATUS_LABEL[s]}
                      </DropdownMenu.Item>
                    ))}
                  </DropdownMenu.Content>
                </DropdownMenu.Portal>
              </DropdownMenu.Root>
            )}
          </span>
        </div>
      </div>
    </div>
  )
}

function BoardColumn({
  col,
  groupBy,
  canMove,
  onMove,
  onDropTicket,
}: {
  col: Column
  groupBy: GroupBy
  canMove: boolean
  onMove: (t: TicketSummary, to: TicketStatus) => void
  onDropTicket: (ticketId: number, col: Column) => void
}) {
  const [over, setOver] = React.useState(false)
  const breached = col.tickets.filter(isBreached).length
  const atRisk = col.tickets.filter(isAtRisk).length
  const droppable = canMove && !!col.statuses

  return (
    <section
      aria-label={col.title}
      onDragOver={droppable ? (e) => { e.preventDefault(); setOver(true) } : undefined}
      onDragLeave={droppable ? () => setOver(false) : undefined}
      onDrop={
        droppable
          ? (e) => {
              e.preventDefault()
              setOver(false)
              const id = Number(e.dataTransfer.getData('text/ticket-id'))
              if (id) onDropTicket(id, col)
            }
          : undefined
      }
      className={cn(
        'glass flex min-w-0 flex-col rounded-xl transition-colors',
        col.tone === 'ai' && 'border-primary/30',
        over && 'border-primary bg-primary-bg/40',
      )}
    >
      {/* One scroll region - the page (audit U12). Headers stay in view instead. */}
      <header className="sticky top-0 z-[1] rounded-t-xl border-b border-border bg-surface px-3.5 py-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="flex items-center gap-1.5 text-base font-semibold text-text">
            {col.tone === 'ai' && <Sparkles className="size-3.5 text-primary" aria-hidden />}
            {col.title}
          </h2>
          <span className="rounded-md bg-surface-2 px-1.5 py-px text-xs font-medium tabular-nums text-text-muted">
            {col.tickets.length}
          </span>
        </div>
        <p className="mt-0.5 text-xs text-text-subtle">{col.caption}</p>
        {(breached > 0 || atRisk > 0) && (
          <div className="mt-2 flex gap-1.5">
            {breached > 0 && (
              <span className="rounded-md bg-danger-bg px-1.5 py-0.5 text-xs font-medium text-danger">{breached} breached</span>
            )}
            {atRisk > 0 && (
              <span className="rounded-md bg-warning-bg px-1.5 py-0.5 text-xs font-medium text-warning">{atRisk} at risk</span>
            )}
          </div>
        )}
      </header>
      <div className="flex-1 space-y-2 p-2.5">
        {col.tickets.length === 0 ? (
          <p className="px-2 py-6 text-center text-xs leading-relaxed text-text-subtle">
            {over ? 'Drop to move here' : col.emptyText}
          </p>
        ) : (
          col.tickets.map((t) => (
            <BoardCard
              key={t.id}
              t={t}
              groupBy={groupBy}
              canMove={canMove && groupBy === 'status'}
              moveTargets={allowedTransitionsFrom(t.status)}
              onMove={onMove}
            />
          ))
        )}
      </div>
    </section>
  )
}

export function BoardPage() {
  useDocumentTitle('Board')
  const { user } = useAuth()
  const { push } = useToast()
  const [params, setParams] = useSearchParams()
  // A team lead only sees their own team's tickets, so "by team" would be one column.
  const defaultGroup: GroupBy = user?.role === 'ADMIN' ? 'team' : 'status'
  const groupBy = (params.get('group') as GroupBy) ?? defaultGroup
  const priority = (params.get('priority') as PriorityFilter) ?? 'all'
  const showDone = params.get('done') === '1'
  const [search, setSearch] = React.useState('')
  const [mobileColumn, setMobileColumn] = React.useState<string | null>(null)
  const [reasonFor, setReasonFor] = React.useState<{ t: TicketSummary; to: TicketStatus } | null>(null)
  const { data, isLoading, isError, refetch, isFetching } = useBoardTickets()
  const changer = useStatusChanger()
  const canMove = user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN'

  function setParam(key: string, value: string | null) {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      if (value === null) next.delete(key)
      else next.set(key, value)
      return next
    })
  }

  const all = React.useMemo(() => data?.tickets ?? [], [data])
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
        emptyText: 'Nothing waiting on triage — new tickets are classified in seconds.',
        tone: 'ai',
        tickets: awaiting,
      },
      ...[...byTeam.entries()]
        .sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]))
        .map(([name, tickets]) => ({
          key: name,
          title: name,
          caption: name === 'General' ? 'No specialist team matched — routed to the catch-all' : 'Routed here by AI triage',
          emptyText: 'No open tickets for this team.',
          tickets,
        })),
    ]
  } else {
    columns = STATUS_COLUMNS.filter((c) => showDone || c.key !== 'done').map((c) => ({
      ...c,
      tickets: visible.filter((t) => c.statuses.includes(t.status)),
    }))
  }

  function move(t: TicketSummary, to: TicketStatus, reason?: string) {
    if (REASON_REQUIRED.includes(to) && !reason) {
      setReasonFor({ t, to })
      return
    }
    changer.request({ ticketId: t.id, reference: t.reference, from: t.status, to, reason })
  }

  function onDropTicket(ticketId: number, col: Column) {
    const t = all.find((x) => x.id === ticketId)
    if (!t) return
    const to = targetFor(t, col)
    if (!to) {
      if (!col.statuses?.includes(t.status)) {
        push('info', `${t.reference} can't move from ${STATUS_LABEL[t.status]} to ${col.title}.`)
      }
      return
    }
    move(t, to)
  }

  const open = all.filter((t) => !DONE.includes(t.status))
  const breached = open.filter(isBreached).length
  const atRisk = open.filter(isAtRisk).length
  const unassigned = open.filter((t) => !t.assignee && t.priority !== 'UNTRIAGED').length
  const triaging = open.filter((t) => t.priority === 'UNTRIAGED').length
  const activeMobile = columns.find((c) => c.key === mobileColumn) ?? columns[0]

  return (
    <Page width="full">
      <PageHeader
        eyebrow={<Eyebrow>{isFetching && !isLoading ? 'Updating…' : 'Live · refreshes every 30s'}</Eyebrow>}
        title="Board"
        description={
          user?.role === 'ADMIN'
            ? 'Every open ticket and where AI triage routed it. Most urgent at the top of each column.'
            : `Your team's tickets by stage. Most urgent at the top of each column.${canMove && groupBy === 'status' ? ' Drag a card, or use its ⋯ menu, to change its status.' : ''}`
        }
      />

      <div className="mb-4">
        <MetricStrip
          label="Board summary"
          loading={isLoading}
          metrics={[
            { key: 'open', label: data?.truncated ? 'open (first 500)' : 'open', value: open.length },
            { key: 'triaging', label: 'AI triaging', value: triaging },
            { key: 'unassigned', label: 'unassigned', value: unassigned, tone: unassigned ? 'warning' : 'neutral' },
            { key: 'risk', label: 'at risk', value: atRisk, tone: atRisk ? 'warning' : 'neutral' },
            { key: 'breached', label: 'breached', value: breached, tone: breached ? 'danger' : 'success' },
          ]}
        />
      </div>

      <div className="mb-4 flex flex-col gap-3 xl:flex-row xl:items-center">
        <div className="flex flex-wrap gap-3">
          <Segmented
            label="Group the board"
            value={groupBy}
            onChange={(v) => setParam('group', v === defaultGroup ? null : v)}
            options={[
              { value: 'team', label: 'By team' },
              { value: 'status', label: 'By status' },
            ]}
          />
          <Segmented
            label="Filter by priority"
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
        </div>
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 z-10 size-4 -translate-y-1/2 text-text-subtle" aria-hidden />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Filter by subject or reference…"
            aria-label="Filter tickets"
            className="h-10 w-full rounded-lg border border-border-control bg-surface pl-9 pr-3 text-base text-text placeholder:text-text-subtle focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary"
          />
        </div>
        <label className="flex min-h-10 shrink-0 cursor-pointer items-center gap-2 text-sm text-text-muted">
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
        <div className="grid gap-3 md:grid-cols-[repeat(4,minmax(0,1fr))]" role="status" aria-busy="true" aria-label="Loading board">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-[420px] rounded-xl" />
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
        <>
          {/* Phones (audit R6): one column at a time, chosen from a picker. */}
          <div className="md:hidden">
            <label className="mb-3 block">
              <span className="sr-only">Column</span>
              <select
                value={activeMobile?.key}
                onChange={(e) => setMobileColumn(e.target.value)}
                className="h-10 w-full rounded-lg border border-border-control bg-surface px-3 text-base text-text"
              >
                {columns.map((c) => (
                  <option key={c.key} value={c.key}>
                    {c.title} ({c.tickets.length})
                  </option>
                ))}
              </select>
            </label>
            {activeMobile && (
              <BoardColumn col={activeMobile} groupBy={groupBy} canMove={canMove} onMove={move} onDropTicket={onDropTicket} />
            )}
          </div>

          {/* Fit to the viewport (audit U15): columns share the width down to 210px, and
              only scroll sideways - with a visible scrollbar - when they truly cannot fit. */}
          <div
            className="hidden gap-3 overflow-x-auto pb-3 md:grid"
            style={{ gridTemplateColumns: `repeat(${columns.length}, minmax(210px, 1fr))` }}
          >
            {columns.map((col) => (
              <BoardColumn key={col.key} col={col} groupBy={groupBy} canMove={canMove} onMove={move} onDropTicket={onDropTicket} />
            ))}
          </div>
        </>
      )}

      <ConfirmDialog
        open={reasonFor !== null}
        onOpenChange={(o) => !o && setReasonFor(null)}
        title={reasonFor ? `Move ${reasonFor.t.reference} to ${STATUS_LABEL[reasonFor.to]}` : ''}
        consequences={['The resolution clock pauses until the ticket moves on.']}
        requireReason
        reasonLabel="Why is it waiting?"
        confirmLabel="Move"
        onConfirm={(reason) => {
          if (reasonFor) move(reasonFor.t, reasonFor.to, reason)
        }}
      />
    </Page>
  )
}
