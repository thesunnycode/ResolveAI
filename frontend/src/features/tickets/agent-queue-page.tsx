import { Inbox, Search } from 'lucide-react'
import * as React from 'react'
import { useSearchParams } from 'react-router-dom'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { EmptyState } from '@/components/ui/empty-state'
import { Segmented, StatTile } from '@/components/ui/segmented'
import { ErrorBanner } from '@/components/ui/error-banner'
import { SkeletonRow } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError, api } from '@/lib/api-client'
import { cn } from '@/lib/utils'
import { useAssignTicket, useTicketQueue } from './api'
import { TicketRow } from './ticket-row'

type ScopeFilter = 'all' | 'mine' | 'unassigned'

export function AgentQueuePage() {
  const { user } = useAuth()
  const { push } = useToast()
  const [params, setParams] = useSearchParams()
  const [search, setSearch] = React.useState(params.get('q') ?? '')
  const scope = (params.get('scope') as ScopeFilter) ?? 'all'
  const assignMutation = useAssignTicket()

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

  const filters = React.useMemo(() => {
    if (scope === 'mine') return { assigneeId: 'me' }
    if (scope === 'unassigned') return { assigneeId: 'none' }
    return {}
  }, [scope])

  const { data, isLoading, isError, error, refetch, isFetching } = useTicketQueue({
    q: params.get('q') ?? undefined,
    ...filters,
  })

  function setScope(next: ScopeFilter) {
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      if (next === 'all') p.delete('scope')
      else p.set('scope', next)
      return p
    })
  }

  async function handleAssignToMe(ticketId: number) {
    try {
      const etagRes = await api.get(`/tickets/${ticketId}`)
      const etag = etagRes.headers.etag
      await assignMutation.mutateAsync({ id: ticketId, etag })
      push('success', 'Assigned to you.')
    } catch (err) {
      if (err instanceof ApiError && err.problem.errorCode === 'ALREADY_ASSIGNED') {
        push('info', err.problem.detail || 'Someone else just took this one.')
      } else {
        push('error', 'Could not assign this ticket.')
      }
    }
  }

  const tickets = data?.data ?? []
  const clocks = tickets.map((t) => t.sla?.resolution ?? t.sla?.firstResponse).filter(Boolean)
  const unassigned = tickets.filter((t) => !t.assignee).length
  const atRisk = clocks.filter((c) => c!.state === 'RUNNING' && c!.atRisk).length
  const breached = clocks.filter((c) => c!.state === 'BREACHED').length
  const more = data?.pagination.hasNext ? '+' : ''

  return (
    <Page width="wide">
      <PageHeader
        eyebrow={
          <Eyebrow>
            {isFetching && !isLoading ? 'Updating…' : 'Live · refreshes every 20s'}
          </Eyebrow>
        }
        title="Queue"
        description={`Hi ${user?.fullName.split(' ')[0] ?? 'there'} — work the oldest at-risk ticket first. SLA clocks count business hours only.`}
      />

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="In view" value={`${tickets.length}${more}`} hint={scope === 'mine' ? 'Assigned to you' : scope === 'unassigned' ? 'Nobody has these yet' : user?.role === 'AGENT' ? "Yours and your team's" : 'Across all teams'} />
        <StatTile label="Unassigned" value={unassigned} tone={unassigned ? 'warning' : 'neutral'} hint="Waiting for an owner" />
        <StatTile label="At risk" value={atRisk} tone={atRisk ? 'warning' : 'neutral'} hint="Predicted to breach" />
        <StatTile label="Breached" value={breached} tone={breached ? 'danger' : 'success'} hint={breached ? 'Past their target' : 'Every promise kept'} />
      </div>

      <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 z-10 size-4 -translate-y-1/2 text-text-subtle" aria-hidden />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search subject or body…"
            aria-label="Search tickets"
            className="glass h-10 w-full rounded-lg pl-9 pr-3 text-[14px] text-text placeholder:text-text-subtle focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary-bg"
          />
        </div>
        <Segmented
          value={scope}
          onChange={setScope}
          options={[
            { value: 'all', label: 'All' },
            { value: 'mine', label: 'Mine' },
            { value: 'unassigned', label: 'Unassigned' },
          ]}
        />
      </div>

      <div className="glass overflow-hidden rounded-xl">
        <div className="hidden items-center gap-3 border-b border-border px-5 py-2.5 text-[11.5px] font-medium uppercase tracking-[0.08em] text-text-subtle sm:flex">
          <span className="flex-1 pl-4">Ticket</span>
          <span className="w-28 text-right">Priority · SLA</span>
          <span className="w-24" />
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
            <SkeletonRow count={6} />
          ) : tickets.length === 0 ? (
            <EmptyState
              icon={Inbox}
              title={scope === 'unassigned' ? 'Nothing unassigned' : 'Your queue is clear'}
              description={
                scope === 'mine'
                  ? 'Tickets assigned to you appear here.'
                  : scope === 'unassigned'
                    ? 'Every ticket has an owner right now — nice work.'
                    : 'New tickets land here within seconds of arriving, already triaged.'
              }
              action={scope !== 'all' ? { label: 'View all tickets', onClick: () => setScope('all') } : undefined}
            />
          ) : (
            <div>
              {tickets.map((t) => (
                <TicketRow
                  key={t.id}
                  ticket={t}
                  onAssignToMe={user?.role !== 'CUSTOMER' ? () => handleAssignToMe(t.id) : undefined}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </Page>
  )
}
