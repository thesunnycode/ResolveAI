import type { Priority } from '@/lib/types'
import { cn } from '@/lib/utils'

const LEVEL: Record<Priority, number> = { P1: 4, P2: 3, P3: 2, P4: 1, UNTRIAGED: 0 }

const NAME: Record<Priority, string> = {
  P1: 'Urgent',
  P2: 'High',
  P3: 'Normal',
  P4: 'Low',
  UNTRIAGED: 'Not triaged yet',
}

/**
 * Priority as shape, not hue (audit V5/A3). It used to be a red/amber/blue/grey bar and
 * dot - the same red as BREACHED and the same amber as At risk, so a card read
 * "red P1, red BREACHED" as one fact, and the colour alone carried the meaning (and failed
 * contrast in light mode). Now signal bars in the text colour, plus the label; red and
 * amber are left to mean SLA state only.
 */
export function PriorityGlyph({ priority, className }: { priority: Priority; className?: string }) {
  const level = LEVEL[priority]
  if (priority === 'P1') {
    return (
      <svg viewBox="0 0 14 14" className={cn('size-3.5 shrink-0 text-text', className)} aria-hidden>
        <rect x="0.5" y="0.5" width="13" height="13" rx="3" fill="currentColor" />
        <rect x="6.1" y="3" width="1.8" height="5" rx="0.9" className="fill-surface" />
        <rect x="6.1" y="9.3" width="1.8" height="1.8" rx="0.9" className="fill-surface" />
      </svg>
    )
  }
  return (
    <svg viewBox="0 0 14 14" className={cn('size-3.5 shrink-0 text-text-muted', className)} aria-hidden>
      {[0, 1, 2].map((i) => (
        <rect
          key={i}
          x={1 + i * 4.5}
          y={9 - i * 3.5}
          width="3"
          height={4 + i * 3.5}
          rx="1"
          fill="currentColor"
          opacity={priority === 'UNTRIAGED' ? 0.25 : i < level ? 1 : 0.25}
          strokeDasharray={priority === 'UNTRIAGED' ? '1.5 1' : undefined}
        />
      ))}
    </svg>
  )
}

export function PriorityLabel({ priority, showName }: { priority: Priority; showName?: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-xs',
        priority === 'P1' ? 'font-semibold text-text' : 'font-medium text-text-muted',
      )}
      title={`${priority === 'UNTRIAGED' ? '' : priority + ' · '}${NAME[priority]}`}
    >
      <PriorityGlyph priority={priority} />
      {priority === 'UNTRIAGED' ? 'Untriaged' : showName ? `${priority} · ${NAME[priority]}` : priority}
    </span>
  )
}

export function priorityName(priority: Priority) {
  return NAME[priority]
}
