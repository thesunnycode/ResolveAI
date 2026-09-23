import { AlertTriangle } from 'lucide-react'
import { Button } from './button'

/**
 * Doc 06 §4.1: "the last successful data still rendered beneath, dimmed" —
 * this component is the banner only; the caller keeps rendering stale data
 * below it and dims it with a wrapping `opacity-60 pointer-events-none`.
 */
export function ErrorBanner({
  message = "Couldn't load this.",
  onRetry,
}: {
  message?: string
  onRetry?: () => void
}) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-danger/20 bg-danger-bg px-4 py-3 text-[13px] text-danger animate-slide-up">
      <AlertTriangle className="size-4 shrink-0" aria-hidden />
      <span className="flex-1">{message}</span>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry} className="border-danger/30">
          Retry
        </Button>
      )}
    </div>
  )
}
