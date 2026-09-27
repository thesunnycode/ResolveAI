import { UserRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import { RelativeTime } from '@/components/ui/relative-time'
import { Skeleton } from '@/components/ui/skeleton'
import type { UserRef } from '@/lib/types'
import { useTicketQueue } from './api'
import { STATUS_LABEL } from './status-labels'

/**
 * Who is asking, and what else they've asked (audit H2) - above AI Assist, as Intercom
 * and Front do. A customer with three open tickets about the same checkout is a different
 * conversation from a first-time question.
 */
export function CustomerContext({ requester, currentTicketId }: { requester: UserRef | null; currentTicketId: number }) {
  const { data, isLoading } = useTicketQueue({
    requesterId: requester?.id,
    size: 6,
    sort: 'created_at',
    order: 'desc',
  }, { enabled: !!requester })
  if (!requester) return null
  const others = (data?.data ?? []).filter((t) => t.id !== currentTicketId).slice(0, 4)
  const open = others.filter((t) => !['RESOLVED', 'CLOSED'].includes(t.status)).length

  return (
    <section className="border-b border-border px-4 py-3" aria-labelledby="customer-context-heading">
      <h2 id="customer-context-heading" className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Customer
      </h2>
      <p className="flex items-center gap-1.5 text-sm font-medium text-foreground">
        <UserRound className="size-3.5 text-muted-foreground" aria-hidden /> {requester.fullName}
      </p>
      {isLoading ? (
        <Skeleton className="mt-2 h-4 w-2/3" />
      ) : others.length === 0 ? (
        <p className="mt-1 text-xs text-muted-foreground">No other tickets from this customer.</p>
      ) : (
        <>
          <p className="mt-1 text-xs text-muted-foreground">
            {open > 0 ? `${open} other open ${open === 1 ? 'ticket' : 'tickets'}` : 'Other recent tickets'}
          </p>
          <ul className="mt-1.5 space-y-0.5">
            {others.map((t) => (
              <li key={t.id}>
                <Link
                  to={`/tickets/${t.id}`}
                  className="flex min-h-7 items-baseline gap-2 rounded px-1 text-sm hover:bg-muted"
                >
                  <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{t.reference}</span>
                  <span className="min-w-0 flex-1 truncate text-foreground">{t.subject}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {STATUS_LABEL[t.status]} · <RelativeTime iso={t.createdAt} />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
