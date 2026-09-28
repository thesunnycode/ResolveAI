import { Check } from 'lucide-react'
import type { TicketStatus, TicketSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const STEPS = ['Sent', 'Assigned', 'Replied', 'Solved'] as const

const ASSIGNED_OR_LATER: TicketStatus[] = ['ASSIGNED', 'IN_PROGRESS', 'WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY']

/**
 * Which of the four customer-facing stages a ticket has reached. The backend has eight
 * statuses; a customer only cares whether we've seen it, someone owns it, we've answered,
 * and whether it's done - the same shape as order tracking.
 */
export function ticketStage(t: TicketSummary): number {
  if (t.status === 'RESOLVED' || t.status === 'CLOSED') return 3
  if (t.lastPublicReply?.fromSupport || t.status === 'WAITING_ON_CUSTOMER') return 2
  if (t.assignee || ASSIGNED_OR_LATER.includes(t.status)) return 1
  return 0
}

/**
 * Sent → Assigned → Replied → Solved. `onPrimary` is for the indigo highlight card, where
 * the usual primary-on-white colors would vanish.
 */
export function ProgressTracker({
  ticket,
  size = 'md',
  onPrimary = false,
  className,
}: {
  ticket: TicketSummary
  size?: 'sm' | 'md'
  onPrimary?: boolean
  className?: string
}) {
  const stage = ticketStage(ticket)
  const solved = stage === 3
  const sm = size === 'sm'
  const pct = (stage / (STEPS.length - 1)) * 100

  const track = onPrimary ? 'bg-primary-foreground/25' : 'bg-border'
  const fill = onPrimary ? 'bg-primary-foreground' : solved ? 'bg-success' : 'bg-primary'

  return (
    <div className={cn('relative', className)}>
      <span className="sr-only">{`Progress: ${STEPS[stage]}, step ${stage + 1} of ${STEPS.length}`}</span>
      <ol aria-hidden className="relative grid grid-cols-4">
        {/* The rail runs between the first and last dot centres: 12.5% in from each edge of a 4-column grid. */}
        <span className={cn('absolute left-[12.5%] right-[12.5%] rounded-full', track, sm ? 'top-[5px] h-0.5' : 'top-[10px] h-[3px]')} />
        <span
          className={cn('absolute left-[12.5%] rounded-full transition-[width]', fill, sm ? 'top-[5px] h-0.5' : 'top-[10px] h-[3px]')}
          style={{ width: `${pct * 0.75}%` }}
        />
        {STEPS.map((label, i) => {
          const done = i < stage || solved
          const now = i === stage && !solved
          return (
            <li key={label} className="relative flex flex-col items-center gap-1.5">
              <span
                className={cn(
                  'grid place-items-center rounded-full border-2',
                  sm ? 'size-3 border-[1.5px]' : 'size-[23px]',
                  onPrimary
                    ? done
                      ? 'border-primary-foreground bg-primary-foreground text-primary'
                      : now
                        ? 'border-primary-foreground bg-primary ring-4 ring-primary-foreground/20'
                        : 'border-primary-foreground/40 bg-primary'
                    : done
                      ? solved
                        ? 'border-success bg-success text-primary-foreground'
                        : 'border-primary bg-primary text-primary-foreground'
                      : now
                        ? 'border-primary bg-card ring-4 ring-secondary'
                        : 'border-border bg-card',
                )}
              >
                {!sm && done && <Check className="size-3" strokeWidth={3.5} />}
                {now && <span className={cn('rounded-full', sm ? 'size-1' : 'size-2', onPrimary ? 'bg-primary-foreground' : 'bg-primary')} />}
              </span>
              {!sm && (
                <span
                  className={cn(
                    'text-xs',
                    onPrimary
                      ? now ? 'font-semibold text-primary-foreground' : 'text-primary-foreground/70'
                      : now ? 'font-semibold text-primary' : done ? 'text-foreground' : 'text-muted-foreground',
                  )}
                >
                  {label}
                </span>
              )}
            </li>
          )
        })}
      </ol>
      {sm && (
        <p aria-hidden className={cn('mt-1.5 text-center text-xs font-medium', solved ? 'text-success' : 'text-primary')}>
          {STEPS[stage]}
        </p>
      )}
    </div>
  )
}
