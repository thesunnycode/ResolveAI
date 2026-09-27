import { AlertTriangle, CheckCircle2, Circle } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAgents } from '@/features/tickets/api'
import type { IncidentSummary } from '@/lib/types'
import { cn, formatSeconds } from '@/lib/utils'

export function IncidentCard({
  incident,
  onConfirm,
  onReject,
}: {
  incident: IncidentSummary
  onConfirm?: () => void
  onReject?: () => void
}) {
  const d = incident.detection
  const sizeOk = d.clusterSizeAtDetection >= d.gateThresholds.minClusterSize
  const rateOk = d.arrivalRateMultiple > d.gateThresholds.minRateMultiple
  const waiting = incident.status === 'PROPOSED' && !onConfirm && !onReject
  const leads = useAgents(waiting).data?.filter((a) => a.role === 'TEAM_LEAD') ?? []
  const leadNames = leads.map((l) => l.fullName)

  return (
    <div
      className={cn(
        'glass rounded-xl p-5 transition-all hover:border-border hover:shadow-elevated',
        incident.status === 'PROPOSED' && 'border-warning/30',
      )}
    >
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          {incident.status === 'PROPOSED' && (
            <Badge variant="warning">
              <AlertTriangle className="size-3" aria-hidden /> Proposed
            </Badge>
          )}
          {(incident.status === 'CONFIRMED' || incident.status === 'MITIGATED') && (
            <Badge variant="primary">
              <Circle className="size-2 fill-current" aria-hidden /> Live
            </Badge>
          )}
          {incident.status === 'RESOLVED' && (
            <Badge variant="success">
              <CheckCircle2 className="size-3" aria-hidden /> Resolved
            </Badge>
          )}
          <span className="text-xs text-muted-foreground">{incident.reference}</span>
        </div>
        <span className="text-xs text-muted-foreground">
          detected {formatSeconds(d.timeToDetectSeconds)} after the first report
        </span>
      </div>

      <Link to={`/incidents/${incident.id}`} className="block">
        <h3 className="text-lg font-semibold tracking-tight text-foreground hover:text-primary">{incident.title}</h3>
      </Link>

      <p className="mt-1.5 text-sm text-muted-foreground">
        {incident.linkedTicketCount} tickets &middot; arrival rate {d.arrivalRateMultiple.toFixed(1)}&times; baseline
        &middot; window {Math.round((new Date(d.detectedAt).getTime() - new Date(d.firstTicketAt).getTime()) / 60000)} min
      </p>

      {/* The gate line, on the card, not behind a click — doc 12/06: the evidence
          that a statistical check, not a model, decided this. */}
      <p className="mt-3 rounded-lg bg-muted/60 px-3 py-2 font-mono text-xs text-muted-foreground">
        gate: size {sizeOk ? '✓' : '✗'} &ge;{d.gateThresholds.minClusterSize} AND rate {rateOk ? '✓' : '✗'} &gt;
        {d.gateThresholds.minRateMultiple}&times; — {sizeOk && rateOk ? 'both passed' : 'not satisfied'}
      </p>

      {waiting && (
        // Audit U18: name who can act, instead of "a team lead" - the agent's next step is
        // to ping one of them.
        <p className="mt-4 border-t border-border pt-3 text-xs leading-relaxed text-muted-foreground">
          {leadNames.length > 0 ? (
            <>
              Needs a team lead to confirm:{' '}
              <span className="font-medium text-foreground">
                {leadNames.length > 2 ? `${leadNames.slice(0, 2).join(', ')} +${leadNames.length - 2}` : leadNames.join(' or ')}
              </span>
              . Ping them if your customers are affected.
            </>
          ) : (
            'Waiting for a team lead to confirm or reject.'
          )}{' '}
          Confirming is what lets customers see an outage notice — a model never does that on its own.
        </p>
      )}
      {incident.status === 'PROPOSED' && (onConfirm || onReject) && (
        <div className="mt-4 flex justify-end gap-2 border-t border-border pt-4">
          {onReject && (
            <Button variant="secondary" size="sm" onClick={onReject}>
              Reject
            </Button>
          )}
          {onConfirm && (
            <Button size="sm" onClick={onConfirm}>
              Confirm incident
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
