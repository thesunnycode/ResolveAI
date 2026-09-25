import { ArrowUpRight, MousePointerClick, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { humanize } from '@/lib/labels'
import { cn, formatRelativeTime } from '@/lib/utils'
import { useTicket, useTicketAnalysis } from './api'
import { PriorityLabel } from './priority-badge'
import { SlaStrip } from './sla-chip'
import { STATUS_LABEL } from './status-labels'

/**
 * The right-hand pane of the split queue (audit H1): read a ticket without leaving the
 * list. Every ticket used to be a full navigation and a "← Queue" back - the round trip
 * Missive and Intercom avoid by keeping the list on screen. j/k move the selection;
 * Enter or "Open" goes to the full ticket.
 */
export function TicketPreview({
  ticketId,
  onAssignToMe,
}: {
  ticketId: number | null
  onAssignToMe?: (id: number) => void
}) {
  const { data: t, isLoading } = useTicket(ticketId ?? '')
  const analysis = useTicketAnalysis(ticketId ?? '')

  if (!ticketId) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 p-8 text-center text-sm text-text-muted">
        <MousePointerClick className="size-5 text-text-subtle" aria-hidden />
        <p>Select a ticket to preview it here.</p>
        <p className="text-xs text-text-subtle">
          <kbd className="font-mono">j</kbd> / <kbd className="font-mono">k</kbd> to move ·{' '}
          <kbd className="font-mono">Enter</kbd> to open
        </p>
      </div>
    )
  }
  if (isLoading || !t) {
    return (
      <div className="space-y-3 p-5" role="status" aria-busy="true" aria-label="Loading preview">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-6 w-3/4" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    )
  }

  const signals = analysis.data?.signals
  const last = t.messages.at(-1)

  return (
    <div className="flex h-full flex-col" aria-label={`Preview of ${t.reference}`} role="region">
      <div className="border-b border-border p-5">
        <p className="text-xs tabular-nums text-text-subtle">{t.reference}</p>
        <h2 className="mt-1 text-lg font-semibold leading-snug text-text">{t.subject}</h2>
        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-text-muted">
          <PriorityLabel priority={t.priority} showName />
          <span>{STATUS_LABEL[t.status]}</span>
          <span className={cn(!t.assignee && 'text-warning')}>{t.assignee?.fullName ?? 'Unassigned'}</span>
        </div>
        <div className="mt-3 flex gap-2">
          <Button asChild size="sm">
            <Link to={`/tickets/${t.id}`}>
              Open ticket <ArrowUpRight className="size-3.5" aria-hidden />
            </Link>
          </Button>
          {!t.assignee && onAssignToMe && (
            <Button size="sm" variant="secondary" onClick={() => onAssignToMe(t.id)}>
              Assign to me
            </Button>
          )}
        </div>
      </div>
      <SlaStrip clocks={t.sla?.clocks} />
      <div className="flex-1 space-y-4 overflow-y-auto p-5">
        {signals && (
          <p className="flex flex-wrap items-center gap-x-1.5 text-sm text-text-muted">
            <Sparkles className="size-3.5 text-primary" aria-hidden />
            <span className="font-medium text-text">{humanize(signals.category)}</span>
            <span aria-hidden>·</span> {humanize(signals.reportedImpact)}
            <span aria-hidden>·</span> {humanize(signals.linguisticUrgency)} urgency
            {signals.paymentAffected && (
              <>
                <span aria-hidden>·</span> <span className="text-warning">payment affected</span>
              </>
            )}
          </p>
        )}
        <section>
          <p className="mb-1 text-xs text-text-subtle">
            {t.requester?.fullName ?? 'Customer'} · {formatRelativeTime(t.createdAt)}
          </p>
          <p className="line-clamp-[8] whitespace-pre-wrap text-sm leading-relaxed text-text">{t.body}</p>
        </section>
        {last && (
          <section className="rounded-lg bg-surface-2/60 p-3">
            <p className="mb-1 text-xs text-text-subtle">
              Latest · {last.authorName} · {formatRelativeTime(last.createdAt)}
              {last.visibility === 'INTERNAL' && ' · internal note'}
            </p>
            <p className="line-clamp-4 whitespace-pre-wrap text-sm leading-relaxed text-text">{last.body}</p>
          </section>
        )}
        <p className="text-xs text-text-subtle">
          {t.messages.length} {t.messages.length === 1 ? 'reply' : 'replies'} in the thread
        </p>
      </div>
    </div>
  )
}
