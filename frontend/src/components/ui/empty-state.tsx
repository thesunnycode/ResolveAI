import type { LucideIcon } from 'lucide-react'
import { Inbox } from 'lucide-react'
import { Button } from './button'

export function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  action,
}: {
  icon?: LucideIcon
  title: string
  description?: string
  action?: { label: string; onClick: () => void }
}) {
  return (
    <div className="relative flex flex-col items-center justify-center overflow-hidden px-6 py-16 text-center animate-fade-in">
      <div
        aria-hidden
        className="pointer-events-none absolute left-1/2 top-6 size-48 -translate-x-1/2 rounded-full bg-primary/10 blur-3xl"
      />
      <div className="glass relative flex size-12 items-center justify-center rounded-xl">
        <Icon className="size-5 text-text-muted" aria-hidden />
      </div>
      <div className="relative mt-4 space-y-1.5">
        <p className="text-[15px] font-semibold tracking-tight text-text">{title}</p>
        {description && <p className="max-w-sm text-[13px] leading-relaxed text-text-muted">{description}</p>}
      </div>
      {action && (
        <Button variant="secondary" size="sm" onClick={action.onClick} className="relative mt-5">
          {action.label}
        </Button>
      )}
    </div>
  )
}
