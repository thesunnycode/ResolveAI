import { AlertTriangle, X } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'

export function IncidentBanner({
  incidentId,
  reference,
  title,
  linkedCount,
  dismissible = true,
}: {
  incidentId: number
  reference: string
  title: string
  linkedCount?: number
  dismissible?: boolean
}) {
  const [dismissed, setDismissed] = React.useState(false)
  if (dismissed) return null

  return (
    <div className="flex items-center gap-2.5 border-b border-warning/25 bg-warning-bg px-5 py-2 text-[13px] text-warning">
      <AlertTriangle className="size-4 shrink-0" aria-hidden />
      <span className="flex-1">
        <strong className="font-semibold">{reference}</strong> {title}
        {linkedCount != null && ` — ${linkedCount} tickets linked`}
      </span>
      <Link to={`/incidents/${incidentId}`} className="font-medium hover:underline">
        View
      </Link>
      {dismissible && (
        <button onClick={() => setDismissed(true)} aria-label="Dismiss" className="text-warning/70 hover:text-warning">
          <X className="size-3.5" aria-hidden />
        </button>
      )}
    </div>
  )
}

/** Customer-facing variant — the only system-generated text a customer sees. Doc 06 §4.4. */
export function CustomerIncidentBanner({ message }: { message: string }) {
  return (
    <div className="flex items-center gap-2 rounded-md bg-warning-bg px-3 py-2 text-[13px] text-warning">
      <AlertTriangle className="size-3.5 shrink-0" aria-hidden />
      {message}
    </div>
  )
}
