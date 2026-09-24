import { CheckCircle2, Link2, Loader2, MessageSquare, XCircle } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { humanize } from '@/lib/labels'
import type { TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { PriorityGlyph, PriorityLabel } from './priority-badge'
import { SlaChip } from './sla-chip'
import { primarySlaClock } from './sla-utils'

/**
 * One queue row.
 *
 * <b>A row, not a link</b> (audit A5): the whole row used to be an <a> with the "Assign to
 * me" <button> inside it - invalid nesting that screen readers announce as one long link.
 * Now the subject is the link, stretched over the row with ::after so the whole row stays
 * clickable, and the button is a sibling above it.
 *
 * <b>Assign is always visible</b> on unassigned rows (audit U10) - it was opacity-0 until
 * hover, which no touch device can do.
 *
 * <b>Two lines on phones</b> (audit U2): below sm, priority and the SLA clock move into
 * the meta line instead of being hidden, so urgency is readable on every width.
 */
export function TicketRow({
  ticket,
  onAssignToMe,
  selected,
  onSelect,
  onOpen,
}: {
  ticket: TicketSummary
  onAssignToMe?: (ticket: TicketSummary) => void
  /** Keyboard selection (j/k) - highlighted and scrolled into view. */
  selected?: boolean
  onSelect?: () => void
  /** Split view: intercept the click (e.g. to preview instead of navigating). */
  onOpen?: (e: React.MouseEvent<HTMLAnchorElement>) => void
}) {
  const [assigning, setAssigning] = React.useState(false)
  const rowRef = React.useRef<HTMLDivElement>(null)
  // Untriaged tickets have no SLA policy resolved yet, so `sla` itself is null.
  const primary = primarySlaClock(ticket.sla)
  const isAnalysing = ticket.status === 'OPEN' && ticket.priority === 'UNTRIAGED'
  const isTerminal = ticket.status === 'RESOLVED' || ticket.status === 'CLOSED'

  React.useEffect(() => {
    if (selected) rowRef.current?.scrollIntoView({ block: 'nearest' })
  }, [selected])

  async function handleAssign() {
    if (!onAssignToMe) return
    setAssigning(true)
    try {
      await onAssignToMe(ticket)
    } finally {
      setAssigning(false)
    }
  }

  const sep = <span aria-hidden className="text-text-subtle">&middot;</span>

  return (
    <div
      ref={rowRef}
      data-selected={selected || undefined}
      className={cn(
        'group relative flex items-start gap-3 border-b border-border px-4 py-2.5 transition-colors last:border-0 sm:items-center sm:px-5',
        'hover:bg-surface-2/50 has-[a:focus-visible]:bg-surface-2/50 has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-inset has-[a:focus-visible]:ring-primary',
        selected && 'bg-surface-2/70 shadow-[inset_3px_0_0_var(--color-primary)]',
        isTerminal && 'opacity-60',
      )}
    >
      <PriorityGlyph priority={ticket.priority} className="mt-1 sm:mt-0" />
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="hidden shrink-0 whitespace-nowrap text-xs tabular-nums text-text-subtle sm:inline">{ticket.reference}</span>
          <Link
            to={`/tickets/${ticket.id}`}
            onFocus={onSelect}
            onClick={onOpen}
            aria-current={selected ? 'true' : undefined}
            className={cn(
              'line-clamp-2 min-w-0 text-base font-medium text-text outline-none after:absolute after:inset-0 after:content-[\'\'] sm:truncate sm:line-clamp-none',
              isTerminal && 'line-through decoration-text-subtle/60',
            )}
          >
            {ticket.subject}
          </Link>
          {ticket.incidentRef && (
            <span className="relative hidden shrink-0 items-center gap-1 rounded-sm bg-warning-bg px-1.5 py-0.5 text-xs font-medium text-warning sm:inline-flex">
              <Link2 className="size-3" aria-hidden />
              {ticket.incidentRef}
            </span>
          )}
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-0.5 text-sm text-text-muted">
          {/* Mobile only: urgency first, where the right-hand column would have been. */}
          {!isAnalysing && (
            <span className="inline-flex items-center gap-1.5 sm:hidden">
              <PriorityLabel priority={ticket.priority} />
              {primary && <SlaChip clock={primary.clock} label={primary.label} />}
              {sep}
            </span>
          )}
          <span className="whitespace-nowrap text-xs tabular-nums text-text-subtle sm:hidden">
            {ticket.reference} {sep}
          </span>
          {ticket.incidentRef && (
            <span className="inline-flex items-center gap-1 whitespace-nowrap text-warning sm:hidden">
              <Link2 className="size-3" aria-hidden />
              {ticket.incidentRef}
              {sep}
            </span>
          )}
          {ticket.requester && (
            <>
              <span className="whitespace-nowrap">{ticket.requester.fullName}</span>
              {sep}
            </>
          )}
          <span className={cn('whitespace-nowrap', !ticket.assignee && 'text-warning')}>
            {ticket.assignee?.fullName ?? 'Unassigned'}
          </span>
          {ticket.category && (
            <>
              {sep}
              <span className="whitespace-nowrap">{humanize(ticket.category)}</span>
            </>
          )}
          {sep}
          <span className="inline-flex items-center gap-1" aria-label={`${ticket.messageCount} replies`}>
            <MessageSquare className="size-3" aria-hidden />
            {ticket.messageCount}
          </span>
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-3">
        {isTerminal ? (
          <div className="flex w-36 justify-end">
            {ticket.status === 'RESOLVED' ? (
              <Badge variant="success">
                <CheckCircle2 className="size-3" aria-hidden /> Resolved
              </Badge>
            ) : (
              <Badge variant="neutral">
                <XCircle className="size-3" aria-hidden /> Closed
              </Badge>
            )}
          </div>
        ) : isAnalysing ? (
          <span className="inline-flex items-center justify-end gap-1.5 text-sm text-text-subtle sm:w-32">
            <Loader2 className="size-3.5 animate-spin" aria-hidden /> <span className="hidden sm:inline">analysing&hellip;</span>
          </span>
        ) : (
          <div className="hidden w-36 flex-col items-end gap-0.5 sm:flex">
            <PriorityLabel priority={ticket.priority} />
            {primary && <SlaChip clock={primary.clock} label={primary.label} />}
          </div>
        )}

        {onAssignToMe && !isTerminal && (
          <div className="relative z-10 flex justify-end sm:w-28">
            {!ticket.assignee && (
              <Button variant="ghost" size="sm" loading={assigning} onClick={handleAssign} aria-label="Assign to me" className="border border-border">
                <span className="sm:hidden">Take</span>
                <span className="hidden sm:inline">Assign to me</span>
              </Button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
