import type * as React from 'react'
import { cn } from '@/lib/utils'

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  className,
}: {
  value: T
  options: { value: T; label: string; count?: number }[]
  onChange: (v: T) => void
  className?: string
}) {
  return (
    <div role="tablist" className={cn('glass inline-flex gap-0.5 rounded-lg p-1', className)}>
      {options.map((o) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(o.value)}
            className={cn(
              'flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors',
              active ? 'bg-surface-2 text-text shadow-card' : 'text-text-muted hover:text-text',
            )}
          >
            {o.label}
            {o.count != null && (
              <span className={cn('tabular-nums text-[11.5px]', active ? 'text-text-muted' : 'text-text-subtle')}>
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
}: {
  label: string
  value: React.ReactNode
  hint?: string
  tone?: 'neutral' | 'warning' | 'danger' | 'success'
}) {
  return (
    <div className="glass rounded-xl px-4 py-3.5">
      <p className="flex items-center gap-1.5 text-[12px] text-text-muted">
        {tone !== 'neutral' && (
          <span
            className={cn(
              'size-1.5 rounded-full',
              tone === 'warning' && 'bg-warning',
              tone === 'danger' && 'bg-danger',
              tone === 'success' && 'bg-success',
            )}
          />
        )}
        {label}
      </p>
      <p className="mt-1 text-[24px] font-semibold tabular-nums tracking-[-0.02em] text-text">{value}</p>
      {hint && <p className="mt-0.5 text-[11.5px] text-text-subtle">{hint}</p>}
    </div>
  )
}
