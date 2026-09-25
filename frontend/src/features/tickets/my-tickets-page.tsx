import * as React from 'react'
import { ChevronRight, Inbox, Plus } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { RelativeTime } from '@/components/ui/relative-time'
import { MetricStrip } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/features/auth/auth-context'
import type { TicketStatus, TicketSummary } from '@/lib/types'
import { trackOnce } from '@/lib/analytics'
import { cn } from '@/lib/utils'
import { useTicketQueue } from './api'
import { CustomerIncidentBanner } from './incident-banner'

type Bucket = 'open' | 'waiting' | 'resolved'

const BUCKET: Record<TicketStatus, Bucket> = {
  OPEN: 'open',
  TRIAGED: 'open',
  ASSIGNED: 'open',
  IN_PROGRESS: 'open',
  PENDING_THIRD_PARTY: 'open',
  WAITING_ON_CUSTOMER: 'waiting',
  RESOLVED: 'resolved',
  CLOSED: 'resolved',
}

const PILL: Record<Bucket, string> = {
  open: 'bg-primary-bg text-primary',
  waiting: 'bg-warning-bg text-warning',
  resolved: 'bg-surface-2 text-text-muted',
}

function duration(minutes: number) {
  if (minutes < 60) return `${Math.max(1, Math.round(minutes))}m`
  const h = Math.round(minutes / 60)
  return h < 24 ? `${h}h` : `${Math.round(h / 24)}d`
}

/**
 * What the customer actually wants to know about each request (audit U21): the promise
 * while we owe a reply, "Replied" once we have. It used to say "Received" for every
 * open ticket, promise or not.
 */
function statusLabel(t: TicketSummary): string {
  const bucket = BUCKET[t.status]
  if (bucket === 'resolved') return 'Resolved'
  if (bucket === 'waiting') return 'Waiting for your reply'
  if (t.lastPublicReply?.fromSupport) return 'Replied'
  const fr = t.sla?.firstResponse
  if (fr?.state === 'RUNNING' && fr.remainingBusinessMinutes != null) {
    return fr.remainingBusinessMinutes > 0 ? `Reply within ${duration(fr.remainingBusinessMinutes)} of business hours` : 'Reply coming soon'
  }
  if (fr?.state === 'PAUSED') return 'Reply expected next business day'
  return 'In progress'
}

/** Audit U20: replaces "0 messages" with who spoke last, and when. N7: whether a person has it. */
function lastActivity(t: TicketSummary): React.ReactNode {
  const r = t.lastPublicReply
  if (r?.fromSupport) {
    return (
      <>
        Support replied <RelativeTime iso={r.at} />
      </>
    )
  }
  if (r) {
    return (
      <>
        You replied <RelativeTime iso={r.at} /> · awaiting our reply
      </>
    )
  }
  if (BUCKET[t.status] === 'resolved') return 'Closed without a written reply'
  return t.assignee ? 'An agent has picked this up' : 'Awaiting our reply'
}

export function MyTicketsPage() {
  useDocumentTitle('My tickets')
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data, isLoading, isError, refetch } = useTicketQueue({})
  const [filter, setFilter] = React.useState<Bucket | null>(null)
  const all = data?.data ?? []
  const count = (b: Bucket) => all.filter((t) => BUCKET[t.status] === b).length
  const tickets = filter ? all.filter((t) => BUCKET[t.status] === filter) : all

  React.useEffect(() => {
    if (!isLoading && data) trackOnce('first_page_loaded', 'first_page_loaded', { route: 'my-tickets', rows: all.length })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLoading, data])

  const chip = (b: Bucket, label: string, tone: 'neutral' | 'warning' | 'success') => ({
    key: b,
    label,
    value: count(b),
    tone,
    selected: filter === b,
    onSelect: () => setFilter(filter === b ? null : b),
    title: `Show only: ${label}`,
  })

  return (
    <Page>
      <PageHeader
        eyebrow={
          <Eyebrow>
            <span className="capitalize">{`${user?.tenantSlug ?? ''} help center`}</span>
          </Eyebrow>
        }
        title="My tickets"
        description="Everything you've asked us about, and where each request stands."
        actions={
          <Button asChild>
            <Link to="/tickets/new">
              <Plus className="size-4" aria-hidden /> New ticket
            </Link>
          </Button>
        }
      />

      {all.length > 0 && (
        <div className="mb-4">
          <MetricStrip
            label="Your tickets"
            metrics={[
              chip('open', 'in progress', 'neutral'),
              chip('waiting', 'need your reply', count('waiting') ? 'warning' : 'neutral'),
              chip('resolved', 'resolved', 'success'),
            ]}
          />
        </div>
      )}

      {isError && <ErrorBanner onRetry={() => refetch()} />}

      <div className="glass overflow-hidden rounded-xl">
        {isLoading ? (
          <div className="divide-y divide-border" aria-busy="true">
            {[1, 2, 3].map((i) => (
              <div key={i} className="space-y-2 px-5 py-4">
                <Skeleton className="h-4 w-2/5" />
                <Skeleton className="h-3 w-1/4" />
              </div>
            ))}
          </div>
        ) : all.length === 0 ? (
          <EmptyState
            icon={Inbox}
            title="No tickets yet"
            description="When you raise something with us it shows up here, with every reply in one place."
            action={{ label: 'Create your first ticket', onClick: () => navigate('/tickets/new') }}
          />
        ) : tickets.length === 0 ? (
          <EmptyState
            icon={Inbox}
            title="Nothing here"
            description="No tickets in this group right now."
            action={{ label: 'Show all tickets', onClick: () => setFilter(null) }}
          />
        ) : (
          <ul className="divide-y divide-border">
            {tickets.map((t) => {
              const bucket = BUCKET[t.status]
              const replied = bucket === 'open' && t.lastPublicReply?.fromSupport
              return (
                <li key={t.id}>
                  <Link
                    to={`/tickets/${t.id}`}
                    className="group flex items-center gap-4 px-5 py-3.5 transition-colors hover:bg-surface-2/50"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-2.5">
                        <span className="truncate text-base font-medium text-text">{t.subject}</span>
                        <span
                          className={cn(
                            'w-fit shrink-0 rounded-md px-2 py-0.5 text-xs font-medium',
                            replied ? 'bg-success-bg text-success' : PILL[bucket],
                          )}
                        >
                          {statusLabel(t)}
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-text-subtle">
                        <span className="tabular-nums">{t.reference}</span> &middot; {lastActivity(t)}
                      </p>
                      {t.incidentRef && (
                        <div className="mt-2.5">
                          <CustomerIncidentBanner message="We're aware of an issue affecting several customers and are working on it." />
                        </div>
                      )}
                    </div>
                    <ChevronRight className="size-4 shrink-0 text-text-subtle transition-transform group-hover:translate-x-0.5" aria-hidden />
                  </Link>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </Page>
  )
}
