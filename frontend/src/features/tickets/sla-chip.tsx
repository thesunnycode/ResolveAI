import { AlertCircle, CheckCircle2, Pause, XCircle } from 'lucide-react'
import type { SlaChipClock, SlaClockDetail } from '@/lib/types'
import { formatDuration } from '@/lib/utils'
import { cn } from '@/lib/utils'

/** "34m left" / "MET ✓" / "BREACHED" — doc 06: agents think in minutes, not percentages. */
export function SlaChip({ clock, label }: { clock: SlaChipClock; label?: string }) {
  const prefix = label ? `${label} ` : ''

  if (clock.state === 'MET') {
    return (
      <span className="inline-flex items-center gap-1 text-[13px] text-success">
        <CheckCircle2 className="size-3.5" aria-hidden />
        {prefix}MET
      </span>
    )
  }
  if (clock.state === 'BREACHED') {
    return (
      <span className="inline-flex items-center gap-1 text-[13px] font-medium text-danger">
        <XCircle className="size-3.5" aria-hidden />
        {prefix}BREACHED
      </span>
    )
  }
  if (clock.state === 'PAUSED') {
    return (
      <span className="inline-flex items-center gap-1 text-[13px] text-text-muted">
        <Pause className="size-3.5" aria-hidden />
        {prefix}paused
      </span>
    )
  }
  if (clock.remainingBusinessMinutes == null) {
    return <span className="text-[13px] text-text-subtle">{prefix}—</span>
  }
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-[13px]',
        clock.atRisk ? 'font-medium text-warning' : 'text-text-muted',
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

export function SlaStrip({ clocks }: { clocks: SlaClockDetail[] | undefined }) {
  const firstResponse = clocks?.find((c) => c.kind === 'FIRST_RESPONSE')
  const resolution = clocks?.find((c) => c.kind === 'RESOLUTION')

  return (
    <div className="flex items-center gap-6 border-b border-border bg-glass px-6 py-2.5 text-[13px] backdrop-blur-xl">
      {firstResponse && (
        <div className="flex items-center gap-2">
          <span className="text-text-subtle">First response</span>
          <SlaChip clock={{ ...firstResponse, atRisk: firstResponse.prediction?.atRisk ?? false }} />
        </div>
      )}
      {resolution && (
        <div className="flex flex-1 items-center gap-2">
          <span className="text-text-subtle">Resolution</span>
          <SlaChip clock={{ ...resolution, atRisk: resolution.prediction?.atRisk ?? false }} />
          {resolution.state === 'RUNNING' && (
            <div className="ml-2 h-1.5 w-32 overflow-hidden rounded-full bg-surface-2">
              <div
                className={cn(
                  'h-full rounded-full',
                  resolution.prediction?.atRisk ? 'bg-warning' : 'bg-primary',
                )}
                style={{
                  width: `${Math.min(100, Math.max(0, (resolution.elapsedBusinessMinutes / resolution.targetBusinessMinutes) * 100))}%`,
                }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  )
}
