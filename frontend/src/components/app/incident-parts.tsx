import type { DetectionView, IncidentStatus } from '@/lib/types'
import { Pill } from './status'

export function IncidentStatusPill({ status }: { status: IncidentStatus }) {
  if (status === 'PROPOSED') return <Pill>Needs checking</Pill>
  if (status === 'CONFIRMED') return <Pill tone="danger">Happening now</Pill>
  if (status === 'MITIGATED') return <Pill tone="warning">Fix in progress</Pill>
  if (status === 'RESOLVED') return <Pill tone="success">Fixed</Pill>
  return <Pill>Not an outage</Pill>
}

/** Plain-language explanation of why this was spotted. */
export function Evidence({ detection }: { detection: DetectionView }) {
  return (
    <div className="space-y-3 text-base">
      <p>
        <b>{detection.clusterSizeAtDetection} similar tickets</b> in the last {detection.gateThresholds.windowMinutes} minutes.
      </p>
      <p>That's about <b>{detection.arrivalRateMultiple.toFixed(1)} times</b> more than usual.</p>
      <p className="text-muted-foreground">{detection.baselineNote}</p>
    </div>
  )
}
