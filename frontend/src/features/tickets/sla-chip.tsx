import { AlertCircle, CheckCircle2, Pause, XCircle } from 'lucide-react'
import type { SlaChipClock, SlaClockDetail } from '@/lib/types'
import { formatDuration } from '@/lib/utils'
import { cn } from '@/lib/utils'

/** "34m left" / "MET ✓" / "BREACHED" — doc 06: agents think in minutes, not percentages. */
export function SlaChip({ clock, label }: { clock: SlaChipClock; label?: string }) {
  const prefix = label ? `${label} ` : ''

  if (clock.state === 'MET') {
    return (
      <span className="inline-flex items-center gap-1 whitespace-nowrap text-sm text-success">
        <CheckCircle2 className="size-3.5" aria-hidden />
        {prefix}met
      </span>
    )
  }
  if (clock.state === 'BREACHED') {
    return (
      <span className="inline-flex items-center gap-1 whitespace-nowrap text-sm font-medium text-danger">
        <XCircle className="size-3.5" aria-hidden />
        {prefix}breached
      </span>
    )
  }
  if (clock.state === 'PAUSED') {
    return (
      <span className="inline-flex items-center gap-1 whitespace-nowrap text-sm text-muted-foreground">
        <Pause className="size-3.5" aria-hidden />
        {prefix}paused
      </span>
    )
  }
  if (clock.remainingBusinessMinutes == null) {
    return <span className="text-sm text-muted-foreground">{prefix}—</span>
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 whitespace-nowrap text-sm',
        clock.atRisk ? 'font-medium text-warning' : 'text-muted-foreground',
      )}
    >
      {clock.atRisk && <AlertCircle className="size-3.5" aria-hidden />}
      {prefix}
      {clock.remainingBusinessMinutes < 0
        ? `${formatDuration(clock.remainingBusinessMinutes)} over`
        : `${formatDuration(clock.remainingBusinessMinutes)} left`}
    </span>
  )
}

/**
 * The ticket's two clocks under the header. Responsive (audit U1): the resolution bar used
 * to be a fixed w-32 beside its label, which pushed the page 4px wider than a 390px phone
 * and put a horizontal scrollbar on the whole ticket. Now each clock is a block that
 * wraps, and the bar shrinks. The bar is a real progressbar (audit A11).
 */
export function SlaStrip({ clocks }: { clocks: SlaClockDetail[] | undefined }) {
  const firstResponse = clocks?.find((c) => c.kind === 'FIRST_RESPONSE')
  const resolution = clocks?.find((c) => c.kind === 'RESOLUTION')
  const pct = resolution
    ? Math.round(Math.min(100, Math.max(0, (resolution.elapsedBusinessMinutes / resolution.targetBusinessMinutes) * 100)))
    : 0

  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-1.5 border-b border-border bg-popover/80 px-4 py-2.5 text-sm backdrop-blur-xl sm:px-6">
      {firstResponse && (
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground">First response</span>
          <SlaChip clock={{ ...firstResponse, atRisk: firstResponse.prediction?.atRisk ?? false }} />
        </div>
      )}
      {resolution && (
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <span className="text-muted-foreground">Resolution</span>
          <SlaChip clock={{ ...resolution, atRisk: resolution.prediction?.atRisk ?? false }} />
          {resolution.state === 'RUNNING' && (
            <div
              role="progressbar"
              aria-label="Resolution time used"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={pct}
              aria-valuetext={`${pct}% of the resolution target used`}
              className="ml-1 h-1.5 w-full min-w-10 max-w-32 overflow-hidden rounded-full bg-muted"
            >
              <div
                className={cn('h-full rounded-full', resolution.prediction?.atRisk ? 'bg-warning' : 'bg-primary')}
                style={{ width: `${pct}%` }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  )
}
