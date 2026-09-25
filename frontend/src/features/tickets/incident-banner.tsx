import { AlertTriangle, X } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'

/**
 * "This ticket is part of INC-1001: Card payment failures at checkout · View incident"
 * (audit U8) - it used to read "INC-1001 linked to this incident".
 */
export function IncidentBanner({
  incidentId,
  reference,
  title,
  linkedCount,
  dismissible = true,
}: {
  incidentId: number
  reference: string
  /** The incident's own title; omitted while it loads. */
  title?: string
  linkedCount?: number
  dismissible?: boolean
}) {
  const [dismissed, setDismissed] = React.useState(false)
  if (dismissed) return null

  return (
    <div role="note" className="flex items-center gap-2.5 border-b border-warning/25 bg-warning-soft px-5 py-1.5 text-sm text-warning">
      <AlertTriangle className="size-4 shrink-0" aria-hidden />
      <span className="min-w-0 flex-1">
        This ticket is part of <strong className="font-semibold">{reference}</strong>
        {title && (
          <>
            : <span className="text-foreground">{title}</span>
          </>
        )}
        {linkedCount != null && ` — ${linkedCount} tickets linked`}
      </span>
      <Link to={`/incidents/${incidentId}`} className="inline-flex min-h-6 shrink-0 items-center font-medium hover:underline">
        View incident
      </Link>
      {dismissible && (
        <button
          onClick={() => setDismissed(true)}
          aria-label="Dismiss incident notice"
          className="flex size-7 shrink-0 items-center justify-center rounded-md text-warning/70 hover:bg-warning/10 hover:text-warning"
        >
          <X className="size-3.5" aria-hidden />
        </button>
      )}
    </div>
  )
}

/** Customer-facing variant — the only system-generated text a customer sees. Doc 06 §4.4. */
export function CustomerIncidentBanner({ message }: { message: string }) {
  return (
    <div className="flex items-center gap-2 rounded-md bg-warning-soft px-3 py-2 text-sm text-warning">
      <AlertTriangle className="size-3.5 shrink-0" aria-hidden />
      {message}
    </div>
  )
}
