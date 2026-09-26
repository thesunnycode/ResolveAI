import { Clock, CheckCircle2, PauseCircle, AlertTriangle, XCircle, Siren } from 'lucide-react'
import { cn } from '@/lib/utils'
import { formatDuration } from '@/lib/utils'
import { primarySlaClock } from '@/features/tickets/sla-utils'
import { PriorityGlyph } from '@/features/tickets/priority-badge'
import { STATUS_LABEL } from '@/features/tickets/status-labels'
import type { Priority, SlaChipClock, SlaSummary, TicketStatus } from '@/lib/types'

type Tone = 'neutral' | 'info' | 'warning' | 'danger' | 'success'
const TONE: Record<Tone, string> = {
  neutral: 'bg-muted text-secondary-foreground border-border',
  info: 'bg-info-soft text-link border-transparent',
  warning: 'bg-warning-soft text-warning border-warning-border',
  danger: 'bg-danger-soft text-danger border-danger-border',
  success: 'bg-success-soft text-success border-success-border',
}

export function Pill({ tone = 'neutral', className, children }: { tone?: Tone; className?: string; children: React.ReactNode }) {
  return (
    <span className={cn('inline-flex h-7 items-center gap-1.5 whitespace-nowrap rounded-full border px-3 text-[13px] font-semibold', TONE[tone], className)}>
      {children}
    </span>
  )
}

export const PRIORITY_WORD: Record<Priority, string> = {
  P1: 'Urgent',
  P2: 'High',
  P3: 'Normal',
  P4: 'Low',
  UNTRIAGED: 'Not triaged yet',
}

/**
 * Priority as shape, not hue (audit V5/A3): a colored pill here would collide with SLA
 * red/amber, which must mean SLA state only. Same neutral pill for every priority; the
 * glyph (bars) and label carry the signal instead.
 */
export function PriorityMark({ priority, className }: { priority: Priority; className?: string }) {
  return (
    <span className={cn('inline-flex h-7 items-center gap-1.5 rounded-full bg-secondary px-3 text-[13px] font-semibold text-secondary-foreground', className)} title={PRIORITY_WORD[priority]}>
      <PriorityGlyph priority={priority} />
      {PRIORITY_WORD[priority]}
    </span>
  )
}

const STATUS_TONE: Record<TicketStatus, Tone> = {
  OPEN: 'info',
  TRIAGED: 'neutral',
  ASSIGNED: 'neutral',
  IN_PROGRESS: 'info',
  WAITING_ON_CUSTOMER: 'warning',
  PENDING_THIRD_PARTY: 'warning',
  RESOLVED: 'success',
  CLOSED: 'neutral',
}
export function StatusPill({ status }: { status: TicketStatus }) {
  return <Pill tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Pill>
}

export function SlaChipFromClock({ clock, label }: { clock: SlaChipClock; label?: string }) {
  const prefix = label ? `${label} ` : ''
  if (clock.state === 'MET') {
    return (
      <Pill tone="success">
        <CheckCircle2 className="size-3" aria-hidden />
        {prefix}met
      </Pill>
    )
  }
  if (clock.state === 'BREACHED') {
    return (
      <Pill tone="danger">
        <XCircle className="size-3" aria-hidden />
        {prefix}breached
      </Pill>
    )
  }
  if (clock.state === 'PAUSED') {
    return (
      <Pill tone="neutral">
        <PauseCircle className="size-3" aria-hidden />
        {prefix}paused
      </Pill>
    )
  }
  if (clock.state === 'CANCELLED' || clock.remainingBusinessMinutes == null) {
    return <Pill tone="neutral">{prefix}—</Pill>
  }
  return (
    <Pill tone={clock.atRisk ? 'warning' : 'neutral'}>
      {clock.atRisk && <AlertTriangle className="size-3" aria-hidden />}
      {!clock.atRisk && <Clock className="size-3" aria-hidden />}
      {prefix}
      {clock.remainingBusinessMinutes < 0
        ? `${formatDuration(clock.remainingBusinessMinutes)} over`
        : `${formatDuration(clock.remainingBusinessMinutes)} left`}
    </Pill>
  )
}

/** The one clock that matters most right now (first response until met, then resolution). */
export function SlaChip({ sla }: { sla: SlaSummary | null | undefined }) {
  const primary = primarySlaClock(sla)
  if (!primary) return null
  return <SlaChipFromClock clock={primary.clock} label={primary.label} />
}

export function IncidentMark({ label = 'Part of an outage' }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-warning-soft px-2.5 text-xs font-semibold leading-6 text-warning">
      <Siren className="size-3" aria-hidden />
      {label}
    </span>
  )
}

export function Avatar({ name, size = 'md' }: { name: string; size?: 'sm' | 'md' }) {
  const i = name
    .split(' ')
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase()
  return (
    <span
      className={cn(
        'inline-grid shrink-0 place-items-center rounded-full bg-accent font-semibold text-secondary-foreground',
        size === 'sm' ? 'size-[22px] text-[10px]' : 'size-8 text-xs',
      )}
      aria-hidden
    >
      {i}
    </span>
  )
}
