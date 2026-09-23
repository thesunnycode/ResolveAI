import type * as React from 'react'
import { cn } from '@/lib/utils'

export function Eyebrow({ children, dot = true }: { children: React.ReactNode; dot?: boolean }) {
  return (
    <span className="glass inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium text-text-muted">
      {dot && <span className="size-1.5 rounded-full bg-primary" />}
      {children}
    </span>
  )
}

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  className,
}: {
  eyebrow?: React.ReactNode
  title: React.ReactNode
  description?: React.ReactNode
  actions?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-7 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between', className)}>
      <div className="min-w-0">
        {eyebrow && <div className="mb-3">{eyebrow}</div>}
        <h1 className="text-[28px] font-semibold leading-[1.15] tracking-[-0.025em] text-text">{title}</h1>
        {description && <p className="mt-1.5 max-w-xl text-[13.5px] leading-relaxed text-text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  )
}

/** The standard page frame: consistent gutters and width, so no screen floats alone in empty space. */
export function Page({ children, width = 'default' }: { children: React.ReactNode; width?: 'narrow' | 'default' | 'wide' | 'full' }) {
  return (
    <div
      className={cn(
        'mx-auto w-full px-5 py-8 sm:px-8 lg:py-10 animate-slide-up',
        width === 'narrow' && 'max-w-2xl',
        width === 'default' && 'max-w-5xl',
        width === 'wide' && 'max-w-6xl',
        width === 'full' && 'max-w-none',
      )}
    >
      {children}
    </div>
  )
}
