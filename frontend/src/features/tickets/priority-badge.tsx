import type { Priority } from '@/lib/types'
import { cn } from '@/lib/utils'

const COLORS: Record<Priority, string> = {
  P1: 'bg-p1',
  P2: 'bg-p2',
  P3: 'bg-p3',
  P4: 'bg-p4',
  UNTRIAGED: 'bg-transparent border border-border-strong',
}

/** The 3px colour bar — doc 06: "colour encodes, never decorates." */
export function PriorityBar({ priority, className }: { priority: Priority; className?: string }) {
  return <span className={cn('block w-[3px] shrink-0 self-stretch rounded-full', COLORS[priority], className)} aria-hidden />
}

export function PriorityLabel({ priority }: { priority: Priority }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-[12px] font-semibold',
        priority === 'P1' && 'text-p1',
        priority === 'P2' && 'text-p2',
        priority === 'P3' && 'text-p3',
        priority === 'P4' && 'text-p4',
        priority === 'UNTRIAGED' && 'text-text-subtle',
      )}
    >
      <span className={cn('size-1.5 rounded-full', priority === 'UNTRIAGED' ? 'border border-text-subtle' : COLORS[priority])} />
      {priority === 'UNTRIAGED' ? 'Untriaged' : priority}
    </span>
  )
}
