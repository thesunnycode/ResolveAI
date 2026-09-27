import { ShieldCheck } from 'lucide-react'
import * as React from 'react'
import { Eyebrow, Page, PageHeader } from '@/components/layout/page-header'
import { ConfirmDialog } from '@/components/app/confirm-dialog'
import { Segmented } from '@/components/ui/segmented'
import { EmptyState, ErrorState } from '@/components/app/states'
import { SkeletonCard } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { ApiError, api } from '@/lib/api-client'
import { useAuth } from '@/features/auth/auth-context'
import { useIsDemoWorkspace } from '@/features/demo/api'
import { SimulateOutageButton } from '@/features/demo/simulate-outage-button'
import { useConfirmIncident, useIncidents, useRejectIncident } from './api'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { IncidentCard } from './incident-card'

type Tab = 'PROPOSED' | 'LIVE' | 'RESOLVED'

export function IncidentBoardPage() {
  useDocumentTitle('Incidents')
  const [tab, setTab] = React.useState<Tab>('PROPOSED')
  const { user } = useAuth()
  // Confirm/reject are TEAM_LEAD+ on the server; an agent sees the evidence, not the buttons.
  const canManage = user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN'
  const inDemo = useIsDemoWorkspace(user?.tenantSlug)
  const { push } = useToast()
  const statusParam = tab === 'LIVE' ? 'CONFIRMED,MITIGATED' : tab
  const { data, isLoading, isError, error, refetch } = useIncidents(statusParam)
  const confirmMutation = useConfirmIncident()
  const rejectMutation = useRejectIncident()

  const [confirmTarget, setConfirmTarget] = React.useState<number | null>(null)
  const [rejectTarget, setRejectTarget] = React.useState<number | null>(null)

  const incidents = data?.data ?? []

  async function withEtag(id: number, action: (etag: string) => Promise<unknown>) {
    const res = await api.get(`/incidents/${id}`)
    await action(res.headers.etag)
  }

  return (
    <Page>
      <PageHeader
        eyebrow={<Eyebrow>Correlation runs every minute</Eyebrow>}
        title="Incidents"
        description="Bursts of related tickets, detected statistically and confirmed by a person before anything changes for customers."
        actions={
          <>
          {canManage && <SimulateOutageButton />}
          <Segmented
            value={tab}
            onChange={setTab}
            options={[
              { value: 'PROPOSED', label: 'Proposed' },
              { value: 'LIVE', label: 'Live' },
              { value: 'RESOLVED', label: 'Resolved' },
            ]}
          />
          </>
        }
      />

      {isError && <ErrorState error={error} onRetry={() => refetch()} />}

      {isLoading ? (
        <SkeletonCard count={2} />
      ) : incidents.length === 0 ? (
        <div className="glass rounded-xl">
        <EmptyState
          icon={ShieldCheck}
          title={tab === 'PROPOSED' ? 'Nothing to review' : tab === 'LIVE' ? 'No live incidents' : 'No resolved incidents yet'}
          body={
            inDemo && tab === 'PROPOSED'
              ? canManage
                ? 'Correlated ticket bursts appear here automatically — an empty board is the healthy state. Use “Simulate a payment outage” above to watch one form in about a minute.'
                : 'Correlated ticket bursts appear here automatically — an empty board is the healthy state. Sign in to the demo as a team lead to simulate an outage.'
              : 'Correlated ticket bursts will appear here automatically. An empty board is the healthy state.'
          }
        />
        </div>
      ) : (
        <div className="space-y-4">
          {incidents.map((incident) => (
            <IncidentCard
              key={incident.id}
              incident={incident}
              onConfirm={tab === 'PROPOSED' && canManage ? () => setConfirmTarget(incident.id) : undefined}
              onReject={tab === 'PROPOSED' && canManage ? () => setRejectTarget(incident.id) : undefined}
            />
          ))}
        </div>
      )}

      <ConfirmDialog
        open={confirmTarget !== null}
        onOpenChange={(o) => !o && setConfirmTarget(null)}
        title="Confirm this incident?"
        consequences={[
          `Link ${incidents.find((i) => i.id === confirmTarget)?.linkedTicketCount ?? 0} tickets to this incident.`,
          'Pause their resolution clocks. First-response clocks keep running.',
          'Your decision improves detection accuracy.',
        ]}
        confirmLabel="Confirm incident"
        onConfirm={async () => {
          if (!confirmTarget) return
          try {
            await withEtag(confirmTarget, (etag) => confirmMutation.mutateAsync({ id: confirmTarget, etag }))
            push('success', 'Incident confirmed.')
          } catch (err) {
            push('error', err instanceof ApiError ? err.problem.detail : 'Could not confirm.')
          }
        }}
      />

      <ConfirmDialog
        open={rejectTarget !== null}
        onOpenChange={(o) => !o && setRejectTarget(null)}
        title="Reject this incident?"
        requireReason
        reasonLabel="Why is this not one incident?"
        confirmLabel="Reject"
        destructive
        onConfirm={async (reason) => {
          if (!rejectTarget || !reason) return
          try {
            await withEtag(rejectTarget, (etag) => rejectMutation.mutateAsync({ id: rejectTarget, reason, etag }))
            push('info', 'Incident rejected.')
          } catch (err) {
            push('error', err instanceof ApiError ? err.problem.detail : 'Could not reject.')
          }
        }}
      />
    </Page>
  )
}
