import type { DetectionView, LinkedTicketView } from '@/lib/types'

const BUCKETS = 12

/**
 * Tickets joining the incident over time (audit N3) - the gate made visual. The bars are
 * linked-ticket arrivals bucketed across the span from first report to the latest link;
 * the marker is the moment the statistical gate fired.
 */
export function ArrivalSparkline({ tickets, detection }: { tickets: LinkedTicketView[]; detection: DetectionView }) {
  const times = tickets.map((t) => new Date(t.linkedAt).getTime()).filter((n) => !Number.isNaN(n))
  if (times.length < 2) return null
  const start = Math.min(new Date(detection.firstTicketAt).getTime(), ...times)
  const end = Math.max(...times, new Date(detection.detectedAt).getTime())
  const span = Math.max(end - start, 60_000)
  const counts = Array.from({ length: BUCKETS }, () => 0)
  for (const t of times) counts[Math.min(BUCKETS - 1, Math.floor(((t - start) / span) * BUCKETS))]++
  const max = Math.max(...counts)
  const detectX = ((new Date(detection.detectedAt).getTime() - start) / span) * 100
  const minutes = Math.round(span / 60_000)
  const peak = counts.indexOf(max)

  return (
    <figure className="mt-3">
      <div
        className="relative flex h-10 items-end gap-0.5"
        role="img"
        aria-label={`${times.length} tickets linked over ${minutes} minutes; busiest stretch had ${max}. The gate fired ${Math.round((detectX / 100) * minutes)} minutes in.`}
      >
        {counts.map((c, i) => (
          <div
            key={i}
            className={i === peak ? 'flex-1 rounded-sm bg-warning' : 'flex-1 rounded-sm bg-warning/40'}
            style={{ height: `${Math.max(6, (c / max) * 100)}%`, opacity: c === 0 ? 0.25 : 1 }}
          />
        ))}
        <div className="absolute inset-y-0 w-px bg-text" style={{ left: `${Math.min(100, Math.max(0, detectX))}%` }} aria-hidden>
          <span className="absolute -top-4 -translate-x-1/2 whitespace-nowrap text-xs text-text-muted">gate fired</span>
        </div>
      </div>
      <figcaption className="mt-1 flex justify-between text-xs text-text-subtle">
        <span>first report</span>
        <span>
          {times.length} tickets over {minutes} min
        </span>
      </figcaption>
    </figure>
  )
}
