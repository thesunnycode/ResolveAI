import * as React from 'react'
import { Skeleton } from './skeleton'
import { cn } from '@/lib/utils'

/**
 * A single-choice filter (All / Mine / Unassigned). A radio group, not tabs (audit A6):
 * it changes what a list shows, it does not switch between panels, and it had tab roles
 * with no tab panels, no arrow keys and one Tab stop per option. Now: one Tab stop,
 * arrows move and select, Home/End jump.
 */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  className,
  label,
}: {
  value: T
  options: { value: T; label: string; count?: number }[]
  onChange: (v: T) => void
  className?: string
  /** Accessible name for the group, e.g. "Show tickets". */
  label?: string
}) {
  const refs = React.useRef<(HTMLButtonElement | null)[]>([])

  function onKeyDown(e: React.KeyboardEvent, index: number) {
    const last = options.length - 1
    const next =
      e.key === 'ArrowRight' || e.key === 'ArrowDown'
        ? index === last ? 0 : index + 1
        : e.key === 'ArrowLeft' || e.key === 'ArrowUp'
          ? index === 0 ? last : index - 1
          : e.key === 'Home'
            ? 0
            : e.key === 'End'
              ? last
              : null
    if (next === null) return
    e.preventDefault()
    onChange(options[next].value)
    refs.current[next]?.focus()
  }

  return (
    <div role="radiogroup" aria-label={label} className={cn('glass inline-flex gap-0.5 rounded-lg p-1', className)}>
      {options.map((o, i) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            ref={(el) => {
              refs.current[i] = el
            }}
            type="button"
            role="radio"
            aria-checked={active}
            tabIndex={active ? 0 : -1}
            onClick={() => onChange(o.value)}
            onKeyDown={(e) => onKeyDown(e, i)}
            className={cn(
              'flex min-h-8 items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
              active ? 'bg-surface-2 text-text shadow-card' : 'text-text-muted hover:text-text',
            )}
          >
            {o.label}
            {o.count != null && (
              <span className={cn('tabular-nums text-xs', active ? 'text-text-muted' : 'text-text-subtle')}>
                {o.count}
              </span>
            )}
          </button>
        )
      })}
    </div>
  )
}

/** A number worth glancing at, with what it means underneath. */
export function StatTile({
  label,
  value,
  hint,
  tone = 'neutral',
  loading,
}: {
  label: string
  value: React.ReactNode
  hint?: string
  tone?: 'neutral' | 'warning' | 'danger' | 'success'
  /** While true, shows a placeholder instead of a value - never a reassuring "0" (audit U5). */
  loading?: boolean
}) {
  return (
    <div className="glass rounded-xl px-4 py-3.5" aria-busy={loading || undefined}>
      <p className="flex items-center gap-1.5 text-xs text-text-muted">
        {!loading && tone !== 'neutral' && <ToneDot tone={tone} />}
        {label}
      </p>
      {loading ? (
        <Skeleton className="mt-2 h-6 w-12" />
      ) : (
        <p className="mt-1 text-xl font-semibold tabular-nums tracking-[-0.02em] text-text">{value}</p>
      )}
      {hint && !loading && <p className="mt-0.5 text-xs text-text-subtle">{hint}</p>}
    </div>
  )
}

function ToneDot({ tone }: { tone: 'warning' | 'danger' | 'success' | 'neutral' }) {
  return (
    <span
      aria-hidden
      className={cn(
        'size-1.5 shrink-0 rounded-full',
        tone === 'warning' && 'bg-warning',
        tone === 'danger' && 'bg-danger',
        tone === 'success' && 'bg-success',
        tone === 'neutral' && 'bg-border-strong',
      )}
    />
  )
}

export interface Metric {
  key: string
  label: string
  value: React.ReactNode
  tone?: 'neutral' | 'warning' | 'danger' | 'success'
  /** Makes the metric a filter chip. */
  onSelect?: () => void
  selected?: boolean
  title?: string
}

/**
 * The compact form of StatTile (audit U11/V2): one 40px line instead of four 110px cards,
 * so the list the numbers describe starts above the fold. Metrics with onSelect act as
 * filter toggles.
 */
export function MetricStrip({ metrics, loading, label }: { metrics: Metric[]; loading?: boolean; label: string }) {
  return (
    <div
      role="group"
      aria-label={label}
      aria-busy={loading || undefined}
      className="glass flex flex-wrap items-center gap-x-1 gap-y-1 rounded-lg px-1.5 py-1.5"
    >
      {metrics.map((m) => {
        const content = (
          <>
            {!loading && m.tone && m.tone !== 'neutral' && <ToneDot tone={m.tone} />}
            <span className="font-semibold tabular-nums text-text">
              {loading ? <Skeleton className="inline-block h-3.5 w-5 align-middle" /> : m.value}
            </span>
            <span className="text-text-muted">{m.label}</span>
          </>
        )
        const cls = cn(
          'flex min-h-8 items-center gap-1.5 rounded-md px-2.5 text-sm',
          m.selected && 'bg-surface-2 shadow-card',
        )
        return m.onSelect && !loading ? (
          <button key={m.key} type="button" onClick={m.onSelect} aria-pressed={m.selected} title={m.title} className={cn(cls, 'hover:bg-surface-2/70')}>
            {content}
          </button>
        ) : (
          <span key={m.key} className={cls} title={m.title}>
            {content}
          </span>
        )
      })}
    </div>
  )
}
