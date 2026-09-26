import * as Switch from '@radix-ui/react-switch'
import { ArrowLeft, Link2Off, Send } from 'lucide-react'
import * as React from 'react'
import { Link, useParams } from 'react-router-dom'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/app/confirm-dialog'
import { ErrorBanner } from '@/components/ui/error-banner'
import { SkeletonCard } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/input'
import { useToast } from '@/components/ui/toast'
import { ApiError } from '@/lib/api-client'
import { cn, formatDateTime, formatSeconds } from '@/lib/utils'
import { useAuth } from '@/features/auth/auth-context'
import { track } from '@/lib/analytics'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { useDetachTicket, useIncident, usePublishUpdate, useResolveIncident } from './api'
import { ArrivalSparkline } from './arrival-sparkline'

export function IncidentDetailPage() {
  const { id } = useParams()
  const { push } = useToast()
  const { data: incident, isLoading, isError, refetch } = useIncident(id!)
  const publishUpdate = usePublishUpdate()
  const detachTicket = useDetachTicket()
  const resolveIncident = useResolveIncident()
  const { user } = useAuth()
  useDocumentTitle(incident ? `${incident.reference} · ${incident.title}` : 'Incident')

  React.useEffect(() => {
    if (incident) track('incident_viewed', { status: incident.status, role: user?.role })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [incident?.id])
  // Publishing, detaching and resolving are TEAM_LEAD+ on the server.
  const canManage = user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN'
  const [confirmResolve, setConfirmResolve] = React.useState(false)

  const [updateBody, setUpdateBody] = React.useState('')
  const [isPublic, setIsPublic] = React.useState(false)
  const [confirmPublicSend, setConfirmPublicSend] = React.useState(false)
  const [detachTarget, setDetachTarget] = React.useState<number | null>(null)
  const idempotencyKey = React.useRef(crypto.randomUUID())

  if (isLoading) {
    return (
      <div className="mx-auto max-w-3xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
        <SkeletonCard count={1} />
      </div>
    )
  }
  if (isError || !incident) {
    return (
      <div className="mx-auto max-w-3xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
        <ErrorBanner onRetry={() => refetch()} />
      </div>
    )
  }

  async function doPublish() {
    try {
      await publishUpdate.mutateAsync({
        incidentId: incident!.id,
        body: updateBody.trim(),
        visibility: isPublic ? 'PUBLIC' : 'INTERNAL',
        idempotencyKey: idempotencyKey.current,
      })
      setUpdateBody('')
      idempotencyKey.current = crypto.randomUUID()
      push('success', 'Update published.')
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not publish.')
    }
  }

  function handlePublishClick() {
    if (!updateBody.trim()) return
    if (isPublic) {
      setConfirmPublicSend(true)
    } else {
      void doPublish()
    }
  }

  return (
    <div className="mx-auto max-w-3xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
      <Link to="/incidents" className="mb-4 flex items-center gap-1 text-sm text-text-muted hover:text-text">
        <ArrowLeft className="size-3.5" aria-hidden /> Incidents
      </Link>

      <div className="mb-5">
        <div className="flex items-center gap-2">
          <Badge variant={incident.status === 'RESOLVED' ? 'success' : incident.status === 'PROPOSED' ? 'warning' : 'primary'}>
            {incident.status}
          </Badge>
          <span className="text-sm text-text-subtle">{incident.reference}</span>
        </div>
        <div className="mt-1.5 flex items-start justify-between gap-4">
          <h1 tabIndex={-1} className="text-xl font-semibold tracking-[-0.02em] text-text">{incident.title}</h1>
          {canManage && (incident.status === 'CONFIRMED' || incident.status === 'MITIGATED') && (
            <Button variant="secondary" size="sm" onClick={() => setConfirmResolve(true)}>
              Resolve incident
            </Button>
          )}
        </div>
        {incident.summary && <p className="mt-1 text-base text-text-muted">{incident.summary}</p>}
        <p className="mt-2 font-mono text-xs text-text-subtle">
          {incident.detection.clusterSizeAtDetection} tickets &middot; {incident.detection.arrivalRateMultiple.toFixed(1)}&times;
          baseline &middot; {formatSeconds(incident.detection.timeToDetectSeconds)} to detect &middot;{' '}
          {incident.titleGeneratedBy ? `title by ${incident.titleGeneratedBy}` : 'templated title'}
        </p>
        <div className="mt-4 max-w-md">
          <ArrivalSparkline tickets={incident.linkedTickets} detection={incident.detection} />
        </div>
      </div>

      <section className="glass mb-6 overflow-hidden rounded-xl">
        <h2 className="border-b border-border px-4 py-2.5 text-sm font-semibold text-text">
          Linked tickets ({incident.linkedTickets.length})
        </h2>
        <div className="divide-y divide-border">
          {incident.linkedTickets.map((t) => (
            <div key={t.ticketId} className="flex items-center justify-between px-4 py-2.5">
              <Link to={`/tickets/${t.ticketId}`} className="text-sm text-text hover:text-primary">
                {t.reference} — {t.subject}
              </Link>
              <div className="flex items-center gap-2">
                {t.linkConfidence != null && (
                  <span className="text-xs text-text-subtle">{t.linkConfidence.toFixed(2)}</span>
                )}
                {canManage && incident.status !== 'RESOLVED' && <button
                  onClick={() => setDetachTarget(t.ticketId)}
                  className="text-text-subtle hover:text-danger"
                  aria-label="Detach"
                >
                  <Link2Off className="size-3.5" aria-hidden />
                </button>}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="glass mb-6 overflow-hidden rounded-xl">
        <h2 className="border-b border-border px-4 py-2.5 text-sm font-semibold text-text">Updates</h2>
        <div className="divide-y divide-border">
          {incident.updates.length === 0 && (
            <p className="px-4 py-4 text-sm text-text-subtle">No updates published yet.</p>
          )}
          {incident.updates.map((u) => (
            <div key={u.id} className="px-4 py-3">
              <div className="mb-1 flex items-center gap-2 text-xs text-text-subtle">
                <Badge variant={u.visibility === 'PUBLIC' ? 'primary' : 'neutral'}>{u.visibility}</Badge>
                <span>{u.authorName}</span>
                <span>&middot;</span>
                <span>{formatDateTime(u.publishedAt)}</span>
              </div>
              <p className="text-sm text-text">{u.body}</p>
              <p className="mt-1 text-xs text-text-subtle">
                {u.delivery.sent} sent &middot; {u.delivery.pending} pending &middot; {u.delivery.failed} failed
              </p>
            </div>
          ))}
        </div>

        {canManage && incident.status !== 'RESOLVED' && (
          <div className="border-t border-border p-4">
            <Textarea
              rows={3}
              placeholder="Write an update for the team, or for affected customers…"
              value={updateBody}
              onChange={(e) => setUpdateBody(e.target.value)}
            />
            <div className="mt-2.5 flex items-center justify-between">
              <label className="flex items-center gap-2 text-sm text-text-muted">
                <Switch.Root
                  checked={isPublic}
                  onCheckedChange={setIsPublic}
                  className={cn(
                    'relative h-5 w-9 rounded-full transition-colors',
                    isPublic ? 'bg-primary' : 'bg-surface-2 border border-border',
                  )}
                >
                  <Switch.Thumb className="block size-4 translate-x-0.5 rounded-full bg-surface shadow transition-transform data-[state=checked]:translate-x-[18px]" />
                </Switch.Root>
                Visible to customers
              </label>
              <Button
                size="sm"
                loading={publishUpdate.isPending}
                disabled={!updateBody.trim()}
                onClick={handlePublishClick}
              >
                <Send className="size-3.5" aria-hidden /> Publish
              </Button>
            </div>
          </div>
        )}
      </section>

      <ConfirmDialog
        open={confirmPublicSend}
        onOpenChange={setConfirmPublicSend}
        title="Send to affected customers?"
        consequences={[`This sends to ${incident.linkedTickets.length} customers.`]}
        confirmLabel="Send"
        onConfirm={doPublish}
      />

      <ConfirmDialog
        open={detachTarget !== null}
        onOpenChange={(o) => !o && setDetachTarget(null)}
        title="Detach this ticket?"
        consequences={['Its resolution clock resumes from where it was paused.']}
        confirmLabel="Detach"
        destructive
        onConfirm={async () => {
          if (!detachTarget) return
          try {
            await detachTicket.mutateAsync({ incidentId: incident.id, ticketId: detachTarget })
            push('success', 'Ticket detached.')
          } catch (err) {
            push('error', err instanceof ApiError ? err.problem.detail : 'Could not detach.')
          }
        }}
      />
      <ConfirmDialog
        open={confirmResolve}
        onOpenChange={setConfirmResolve}
        title="Resolve this incident?"
        consequences={[
          'Linked tickets stay open for agents to close individually.',
          'Their paused resolution clocks resume.',
        ]}
        requireReason
        reasonLabel="Resolution note (what fixed it)"
        confirmLabel="Resolve incident"
        onConfirm={async (note) => {
          try {
            await resolveIncident.mutateAsync({ id: incident.id, resolutionNote: note ?? '', etag: incident.etag })
            push('success', 'Incident resolved.')
          } catch (err) {
            push('error', err instanceof ApiError ? err.problem.detail : 'Could not resolve the incident.')
          }
        }}
      />
    </div>
  )
}
