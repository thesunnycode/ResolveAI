import * as React from 'react'
import { ChevronRight, Inbox, Plus } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { ErrorBanner } from '@/components/ui/error-banner'
import { StatTile } from '@/components/ui/segmented'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/features/auth/auth-context'
import type { TicketStatus } from '@/lib/types'
import { trackOnce } from '@/lib/analytics'
import { cn, formatRelativeTime } from '@/lib/utils'
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

const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: 'Received',
  TRIAGED: 'In progress',
  ASSIGNED: 'In progress',
  IN_PROGRESS: 'In progress',
  PENDING_THIRD_PARTY: 'In progress',
  WAITING_ON_CUSTOMER: 'Waiting for your reply',
  RESOLVED: 'Resolved',
  CLOSED: 'Resolved',
}

const PILL: Record<Bucket, string> = {
  open: 'bg-primary-bg text-primary',
  waiting: 'bg-warning-bg text-warning',
  resolved: 'bg-surface-2 text-text-muted',
}

export function MyTicketsPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { data, isLoading, isError, refetch } = useTicketQueue({})
  const tickets = data?.data ?? []
  const count = (b: Bucket) => tickets.filter((t) => BUCKET[t.status] === b).length

  React.useEffect(() => {
    if (!isLoading && data) trackOnce('first_page_loaded', 'first_page_loaded', { route: 'my-tickets', rows: tickets.length })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isLoading, data])

  return (
    <Page>
      <PageHeader
        eyebrow={<Eyebrow><span className="capitalize">{`${user?.tenantSlug ?? ""} help center`}</span></Eyebrow>}
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

      {tickets.length > 0 && (
        <div className="mb-6 grid grid-cols-3 gap-3">
          <StatTile label="In progress" value={count('open')} />
          <StatTile label="Needs your reply" value={count('waiting')} tone={count('waiting') ? 'warning' : 'neutral'} />
          <StatTile label="Resolved" value={count('resolved')} tone="success" />
        </div>
      )}

      {isError && <ErrorBanner onRetry={() => refetch()} />}

      <div className="glass overflow-hidden rounded-xl">
        {isLoading ? (
          <div className="divide-y divide-border">
            {[1, 2, 3].map((i) => (
              <div key={i} className="space-y-2 px-5 py-4">
                <Skeleton className="h-4 w-2/5" />
                <Skeleton className="h-3 w-1/4" />
              </div>
            ))}
          </div>
        ) : tickets.length === 0 ? (
          <EmptyState
            icon={Inbox}
            title="No tickets yet"
            description="When you raise something with us it shows up here, with every reply in one place."
            action={{ label: 'Create your first ticket', onClick: () => navigate('/tickets/new') }}
          />
        ) : (
          <ul className="divide-y divide-border">
            {tickets.map((t) => {
              const bucket = BUCKET[t.status]
              return (
                <li key={t.id}>
                  <Link
                    to={`/tickets/${t.id}`}
                    className="group flex items-center gap-4 px-5 py-4 transition-colors hover:bg-surface-2/50"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2.5">
                        <span className="truncate text-[14px] font-medium text-text">{t.subject}</span>
                        <span className={cn('shrink-0 rounded-md px-2 py-0.5 text-[11.5px] font-medium', PILL[bucket])}>
                          {STATUS_LABEL[t.status]}
                        </span>
                      </div>
                      <p className="mt-1 text-[12.5px] text-text-subtle">
                        {t.reference} &middot; updated {formatRelativeTime(t.updatedAt)} &middot; {t.messageCount}{' '}
                        {t.messageCount === 1 ? 'message' : 'messages'}
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
