import { ArrowDownUp, Inbox, Search, Sparkles } from 'lucide-react'
import * as React from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { MetricStrip, Segmented } from '@/components/ui/segmented'
import { SkeletonRow } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { useIsDemoWorkspace, useStartStorm } from '@/features/demo/api'
import { DemoTour } from '@/features/demo/demo-tour'
import { trackOnce } from '@/lib/analytics'
import { ApiError, api } from '@/lib/api-client'
import { useHotkey } from '@/lib/hotkeys'
import type { Priority, TicketSummary } from '@/lib/types'
import { useMediaQuery } from '@/lib/use-media-query'
import { cn } from '@/lib/utils'
import { useAssignTicket, useCanSelfAssign, useTicketQueue, type QueueFilters } from './api'
import { primarySlaClock } from './sla-utils'
import { TicketPreview } from './ticket-preview'
import { TicketRow } from './ticket-row'

type ScopeFilter = 'all' | 'mine' | 'unassigned'
type SortKey = 'urgency' | 'newest' | 'oldest'
/** Client-side narrowing on top of the scope (audit H5). */
type Focus = 'none' | 'at-risk' | 'breached' | 'p1'

const VIEW_KEY = 'resolveai.queueView'

const PRIORITY_RANK: Record<Priority, number> = { P1: 0, P2: 1, P3: 2, P4: 3, UNTRIAGED: 4 }

function urgencyRank(t: TicketSummary) {
  const c = primarySlaClock(t.sla)?.clock
  if (c?.state === 'BREACHED') return 0
  if (c?.state === 'RUNNING' && c.atRisk) return 1
  return 2
}

/** Breached → at risk → priority → least time left: the order the subtitle promises. */
function byUrgency(a: TicketSummary, b: TicketSummary) {
  const left = (t: TicketSummary) => primarySlaClock(t.sla)?.clock.remainingBusinessMinutes ?? Number.MAX_SAFE_INTEGER
  return urgencyRank(a) - urgencyRank(b) || PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority] || left(a) - left(b)
}

const PRESETS: { key: string; label: string; scope: ScopeFilter; focus: Focus }[] = [
  { key: 'mine-risk', label: 'Mine & at risk', scope: 'mine', focus: 'at-risk' },
  { key: 'p1-unassigned', label: 'P1 unassigned', scope: 'unassigned', focus: 'p1' },
  { key: 'breached', label: 'Breached', scope: 'all', focus: 'breached' },
]

export function AgentQueuePage() {
  useDocumentTitle('Queue')
  const { user } = useAuth()
  const { push } = useToast()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [search, setSearch] = React.useState(params.get('q') ?? '')
  const searchRef = React.useRef<HTMLInputElement>(null)
  const assignMutation = useAssignTicket()
  const inDemo = useIsDemoWorkspace(user?.tenantSlug)
  const storm = useStartStorm()
  const canSimulate = inDemo && (user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN')
  const split = useMediaQuery('(min-width: 1280px)')
  const canSelfAssign = useCanSelfAssign(user)

  // Remember the last view (audit H5) - restored when the URL does not name one.
  React.useEffect(() => {
    if ([...params.keys()].length > 0) return
    try {
      const saved = localStorage.getItem(VIEW_KEY)
      if (saved) setParams(new URLSearchParams(saved), { replace: true })
    } catch {
      // No saved view; defaults apply.
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  React.useEffect(() => {
    try {
      const view = new URLSearchParams(params)
      view.delete('q')
      localStorage.setItem(VIEW_KEY, view.toString())
    } catch {
      // Remembering the view is a convenience only.
    }
  }, [params])

  const scope = (params.get('scope') as ScopeFilter) ?? 'all'
  const sort = (params.get('sort') as SortKey) ?? 'urgency'
  const focus = (params.get('focus') as Focus) ?? 'none'

  React.useEffect(() => {
    const t = setTimeout(() => {
      setParams((prev) => {
        const next = new URLSearchParams(prev)
        if (search) next.set('q', search)
        else next.delete('q')
        return next
      })
    }, 350)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search])

  const filters: QueueFilters = React.useMemo(() => {
    const f: QueueFilters = {}
    if (scope === 'mine') f.assigneeId = 'me'
    if (scope === 'unassigned') f.assigneeId = 'none'
    if (focus === 'p1') f.priority = ['P1']
    // Urgency: the server orders by priority, then each page is ranked by SLA state
    // below. The server cannot sort by an SLA deadline, so this is urgency within a page.
    if (sort === 'urgency') Object.assign(f, { sort: 'priority', order: 'asc' })
    if (sort === 'newest') Object.assign(f, { sort: 'created_at', order: 'desc' })
    if (sort === 'oldest') Object.assign(f, { sort: 'created_at', order: 'asc' })
    return f
  }, [scope, focus, sort])

  const { data, isLoading, isError, error, refetch, isFetching } = useTicketQueue({
    q: params.get('q') ?? undefined,
    ...filters,
  })

  function setParam(key: string, value: string | null) {
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      if (value === null) p.delete(key)
      else p.set(key, value)
      return p
    })
  }
  function applyView(nextScope: ScopeFilter, nextFocus: Focus) {
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      if (nextScope === 'all') p.delete('scope')
      else p.set('scope', nextScope)
      if (nextFocus === 'none') p.delete('focus')
      else p.set('focus', nextFocus)
      return p
    })
  }

  const fetched = React.useMemo(() => {
    let list = data?.data ?? []
    if (focus === 'at-risk') list = list.filter((t) => urgencyRank(t) <= 1)
    if (focus === 'breached') list = list.filter((t) => urgencyRank(t) === 0)
    return sort === 'urgency' ? [...list].sort(byUrgency) : list
  }, [data, focus, sort])

  // Live updates without rows jumping under the cursor (audit F5): new arrivals are held
  // behind a "Show" pill; rows already on screen keep their place but update in place.
  const [shownIds, setShownIds] = React.useState<number[] | null>(null)
  const viewKey = `${scope}|${sort}|${focus}|${params.get('q') ?? ''}`
  const lastViewKey = React.useRef(viewKey)
  React.useEffect(() => {
    if (!data) return
    if (shownIds === null || lastViewKey.current !== viewKey) {
      lastViewKey.current = viewKey
      setShownIds(fetched.map((t) => t.id))
    }
  }, [data, fetched, shownIds, viewKey])
  const byId = new Map(fetched.map((t) => [t.id, t]))
  const tickets = (shownIds ?? fetched.map((t) => t.id)).map((id) => byId.get(id)).filter((t): t is TicketSummary => !!t)
  const newCount = shownIds ? fetched.filter((t) => !shownIds.includes(t.id)).length : 0
  const showNew = () => setShownIds(fetched.map((t) => t.id))

  const [selected, setSelected] = React.useState(0)
  const selectedTicket = tickets[Math.min(selected, tickets.length - 1)]
  React.useEffect(() => setSelected(0), [viewKey])

  async function handleAssignToMe(ticketId: number) {
    const t = tickets.find((x) => x.id === ticketId)
    try {
      const etagRes = await api.get(`/tickets/${ticketId}`)
      await assignMutation.mutateAsync({ id: ticketId, etag: etagRes.headers.etag })
      // Audit F1: say what happened and offer the obvious next step.
      push('success', `${t?.reference ?? 'Ticket'} is yours.`, {
        action: { label: 'Open ticket', onClick: () => navigate(`/tickets/${ticketId}`) },
      })
    } catch (err) {
      if (err instanceof ApiError && err.problem.errorCode === 'ALREADY_ASSIGNED') {
        push('info', err.problem.detail || 'Someone else just took this one.')
      } else {
        push('error', err instanceof ApiError ? err.problem.detail : 'Could not assign this ticket.')
      }
    }
  }

  // Keyboard (audit F6): j/k move, Enter opens, a assigns, / searches.
  useHotkey('j', () => setSelected((i) => Math.min(i + 1, tickets.length - 1)), { enabled: tickets.length > 0 })
  useHotkey('k', () => setSelected((i) => Math.max(i - 1, 0)), { enabled: tickets.length > 0 })
  useHotkey('Enter', () => selectedTicket && navigate(`/tickets/${selectedTicket.id}`), { enabled: !!selectedTicket })
  useHotkey('a', () => selectedTicket && !selectedTicket.assignee && void handleAssignToMe(selectedTicket.id), {
    enabled: !!selectedTicket && canSelfAssign,
  })
  useHotkey('/', () => searchRef.current?.focus())

  const all = data?.data ?? []
  const clocks = all.map((t) => primarySlaClock(t.sla)?.clock).filter(Boolean)
  const unassigned = all.filter((t) => !t.assignee).length
  const atRisk = clocks.filter((c) => c!.state === 'RUNNING' && c!.atRisk).length
  const breached = clocks.filter((c) => c!.state === 'BREACHED').length
  const more = data?.pagination.hasNext ? '+' : ''

  React.useEffect(() => {
    if (!isLoading && data) {
      trackOnce('first_page_loaded', 'first_page_loaded', {
        route: 'queue',
        rows: all.length,
        breachedPct: all.length ? Math.round((100 * breached) / all.length) : 0,
      })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLoading, data])

  const list = (
    <div className="glass overflow-hidden rounded-xl">
      <div className="hidden items-center gap-3 border-b border-border px-5 py-2 text-xs font-medium uppercase tracking-[0.08em] text-text-subtle sm:flex">
        <span className="flex-1 pl-6">Ticket</span>
        <span className="w-36 text-right">Priority · SLA</span>
        <span className="w-28" />
      </div>
      {newCount > 0 && (
        <button
          type="button"
          onClick={showNew}
          className="flex w-full items-center justify-center gap-1.5 border-b border-border bg-primary-bg/50 py-2 text-sm font-medium text-primary hover:bg-primary-bg"
        >
          {newCount} new {newCount === 1 ? 'ticket' : 'tickets'} · Show
        </button>
      )}
      <div role="status" aria-live="polite" className="sr-only">
        {newCount > 0 ? `${newCount} new tickets arrived` : ''}
      </div>
      {isError && (
        <div className="p-4">
          <ErrorBanner
            message={error instanceof ApiError ? error.problem.detail : "Couldn't load the queue."}
            onRetry={() => refetch()}
          />
        </div>
      )}
      <div className={cn(isError && tickets.length > 0 && 'pointer-events-none opacity-60')}>
        {isLoading ? (
          <SkeletonRow count={8} />
        ) : tickets.length === 0 ? (
          <EmptyState
            icon={Inbox}
            title={focus !== 'none' ? 'Nothing matches this view' : scope === 'unassigned' ? 'Nothing unassigned' : 'Your queue is clear'}
            description={
              focus !== 'none'
                ? 'No ticket on this page fits the view — good news for this one.'
                : scope === 'mine'
                  ? 'Tickets assigned to you appear here.'
                  : scope === 'unassigned'
                    ? 'Every ticket has an owner right now — nice work.'
                    : canSimulate
                      ? 'New tickets land here within seconds of arriving, already triaged. Nothing has arrived yet — simulate an outage to watch 38 of them come in.'
                      : 'New tickets land here within seconds of arriving, already triaged. Nothing has arrived yet.'
            }
            action={
              focus !== 'none' || scope !== 'all'
                ? { label: 'View all tickets', onClick: () => applyView('all', 'none') }
                : canSimulate
                  ? {
                      label: 'Simulate a payment outage',
                      onClick: () =>
                        storm
                          .mutateAsync()
                          .then((r) => push('success', r.detail))
                          .catch((err) => push('info', err instanceof ApiError ? err.problem.detail : 'Could not start the simulation.')),
                    }
                  : undefined
            }
          />
        ) : (
          <div>
            {tickets.map((t, i) => (
              <TicketRow
                key={t.id}
                ticket={t}
                selected={i === selected}
                onSelect={() => setSelected(i)}
                onOpen={
                  split
                    ? (e) => {
                        // Split view: a plain click previews; modifier-click still opens.
                        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
                        e.preventDefault()
                        setSelected(i)
                      }
                    : undefined
                }
                onAssignToMe={canSelfAssign ? () => handleAssignToMe(t.id) : undefined}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )

  return (
    <Page width={split ? 'full' : 'wide'}>
      <PageHeader
        className="mb-5"
        eyebrow={<Eyebrow>{isFetching && !isLoading ? 'Updating…' : 'Live · refreshes every 20s'}</Eyebrow>}
        title="Queue"
        description={
          <span className="hidden sm:inline">
            {`Hi ${user?.fullName.split(' ')[0] ?? 'there'} — ${
              sort === 'urgency' ? 'most urgent first: breached, then at risk, then priority.' : 'work the most urgent ticket first.'
            } SLA clocks count business hours only.`}
          </span>
        }
      />

      <DemoTour />

      <div className="mb-3 flex flex-col gap-2.5">
        <MetricStrip
          label="Queue summary"
          loading={isLoading}
          metrics={[
            { key: 'view', label: 'in view', value: `${all.length}${more}` },
            {
              key: 'unassigned',
              label: 'unassigned',
              value: unassigned,
              tone: unassigned ? 'warning' : 'neutral',
              selected: scope === 'unassigned' && focus === 'none',
              onSelect: () => applyView(scope === 'unassigned' && focus === 'none' ? 'all' : 'unassigned', 'none'),
              title: 'Show only unassigned tickets',
            },
            {
              key: 'risk',
              label: 'at risk',
              value: atRisk,
              tone: atRisk ? 'warning' : 'neutral',
              selected: focus === 'at-risk',
              onSelect: () => applyView(scope, focus === 'at-risk' ? 'none' : 'at-risk'),
              title: 'Show breached and at-risk tickets',
            },
            {
              key: 'breached',
              label: 'breached',
              value: breached,
              tone: breached ? 'danger' : 'success',
              selected: focus === 'breached',
              onSelect: () => applyView(scope, focus === 'breached' ? 'none' : 'breached'),
              title: breached ? 'Show breached tickets' : 'Every promise on this page kept',
            },
          ]}
        />

        <div className="flex flex-col gap-2.5 lg:flex-row lg:items-center">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 z-10 size-4 -translate-y-1/2 text-text-subtle" aria-hidden />
            <input
              ref={searchRef}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search tickets or TKT-1234…"
              aria-label="Search tickets"
              aria-keyshortcuts="/"
              className="h-10 w-full rounded-lg border border-border-control bg-surface pl-9 pr-3 text-base text-text placeholder:text-text-subtle focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-primary"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Segmented
              label="Show tickets"
              value={scope}
              onChange={(v) => applyView(v, focus === 'p1' && v !== 'unassigned' ? 'none' : focus)}
              options={[
                { value: 'all', label: 'All' },
                { value: 'mine', label: 'Mine' },
                { value: 'unassigned', label: 'Unassigned' },
              ]}
            />
            <label className="flex min-h-10 items-center gap-1.5 rounded-lg border border-border-control bg-surface px-2 text-sm text-text-muted">
              <ArrowDownUp className="size-3.5" aria-hidden />
              <span className="sr-only">Sort</span>
              <select
                value={sort}
                onChange={(e) => setParam('sort', e.target.value === 'urgency' ? null : e.target.value)}
                className="bg-transparent text-text outline-none"
              >
                <option value="urgency">Most urgent</option>
                <option value="newest">Newest</option>
                <option value="oldest">Oldest</option>
              </select>
            </label>
          </div>
        </div>

        <div className="-mx-1 flex items-center gap-1.5 overflow-x-auto px-1 [scrollbar-width:none]" role="group" aria-label="Saved views">
          <span className="mr-1 flex shrink-0 items-center gap-1 text-xs text-text-subtle">
            <Sparkles className="size-3" aria-hidden /> Views
          </span>
          {PRESETS.map((p) => {
            const active = scope === p.scope && focus === p.focus
            return (
              <button
                key={p.key}
                type="button"
                aria-pressed={active}
                onClick={() => (active ? applyView('all', 'none') : applyView(p.scope, p.focus))}
                className={cn(
                  'min-h-7 shrink-0 whitespace-nowrap rounded-full border px-2.5 text-xs font-medium transition-colors',
                  active ? 'border-text/30 bg-surface-2 text-text' : 'border-border text-text-muted hover:border-border-strong hover:text-text',
                )}
              >
                {p.label}
              </button>
            )
          })}
        </div>
      </div>

      {split ? (
        <div className="grid grid-cols-[minmax(0,1fr)_420px] items-start gap-4">
          {list}
          <aside className="glass sticky top-4 h-[calc(100svh-2rem)] overflow-hidden rounded-xl">
            <TicketPreview ticketId={selectedTicket?.id ?? null} onAssignToMe={canSelfAssign ? (id) => void handleAssignToMe(id) : undefined} />
          </aside>
        </div>
      ) : (
        list
      )}
    </Page>
  )
}
