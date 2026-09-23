import { Loader2, MessageSquare, Link2 } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import type { TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { PriorityBar, PriorityLabel } from './priority-badge'
import { SlaChip } from './sla-chip'

export function TicketRow({
  ticket,
  onAssignToMe,
}: {
  ticket: TicketSummary
  onAssignToMe?: (ticket: TicketSummary) => void
}) {
  const [assigning, setAssigning] = React.useState(false)
  // Untriaged tickets have no SLA policy resolved yet, so `sla` itself is null.
  const primaryClock = ticket.sla?.resolution ?? ticket.sla?.firstResponse ?? null
  const isAnalysing = ticket.status === 'OPEN' && ticket.priority === 'UNTRIAGED'

  async function handleAssign(e: React.MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    if (!onAssignToMe) return
    setAssigning(true)
    try {
      await onAssignToMe(ticket)
    } finally {
      setAssigning(false)
    }
  }

  return (
    <Link
      to={`/tickets/${ticket.id}`}
      className="group flex items-stretch gap-3 border-b border-border px-5 py-3.5 transition-colors last:border-0 hover:bg-surface-2/50 focus-visible:bg-surface-2/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
    >
      <PriorityBar priority={ticket.priority} />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="shrink-0 whitespace-nowrap text-[12px] tabular-nums text-text-subtle">{ticket.reference}</span>
          <span className="truncate text-[14px] font-medium text-text">{ticket.subject}</span>
          {ticket.incidentRef && (
            // The queue row only carries the incident's reference, not its id (see
            // TicketSummaryResponse) - a deep link needs the full detail fetch, so this
            // is a plain badge, not a link. The full-detail page links it properly.
            <span className="inline-flex shrink-0 items-center gap-1 rounded-sm bg-warning-bg px-1.5 py-0.5 text-[11px] font-medium text-warning">
              <Link2 className="size-3" aria-hidden />
              {ticket.incidentRef}
            </span>
          )}
        </div>
        <div className="mt-1 flex items-center gap-1.5 text-[12.5px] text-text-muted">
          {ticket.requester && (
            <>
              <span>{ticket.requester.fullName}</span>
              <span className="text-text-subtle">&middot;</span>
            </>
          )}
          <span className={ticket.assignee ? '' : 'text-warning'}>{ticket.assignee?.fullName ?? 'Unassigned'}</span>
          {ticket.category && (
            <>
              <span className="text-text-subtle">&middot;</span>
              <span>{ticket.category}</span>
            </>
          )}
          <span className="text-text-subtle">&middot;</span>
          <span className="inline-flex items-center gap-1">
            <MessageSquare className="size-3" aria-hidden />
            {ticket.messageCount}
          </span>
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-3">
        {isAnalysing ? (
          <span className="inline-flex w-28 items-center justify-end gap-1.5 text-[13px] text-text-subtle">
            <Loader2 className="size-3.5 animate-spin" aria-hidden /> analysing&hellip;
          </span>
        ) : (
          <div className="hidden w-28 flex-col items-end gap-0.5 sm:flex">
            <PriorityLabel priority={ticket.priority} />
            {primaryClock && <SlaChip clock={primaryClock} />}
          </div>
        )}

        <div className="hidden w-24 justify-end sm:flex">
          {!ticket.assignee && onAssignToMe && (
            <Button
              variant="secondary"
              size="sm"
              loading={assigning}
              onClick={handleAssign}
              className={cn('opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100')}
            >
              Assign to me
            </Button>
          )}
        </div>
      </div>
    </Link>
  )
}
