import { ChevronRight, Sparkles } from 'lucide-react'
import * as React from 'react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import type { AnalysisView, ClaimView, DraftView } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useDraft, useRequestDraft, useTicketAnalysis } from './api'
import { CitationPopover } from './citation-popover'

function TriageSignals({ analysis }: { analysis: AnalysisView }) {
  if (analysis.status === 'PROCESSING') {
    return (
      <div className="space-y-2 p-4">
        <Skeleton className="h-3.5 w-full" />
        <Skeleton className="h-3.5 w-3/4" />
        <Skeleton className="h-3.5 w-5/6" />
      </div>
    )
  }
  if (analysis.status === 'UNAVAILABLE' || !analysis.signals) {
    return (
      <div className="p-4 text-[13px] text-text-muted">
        {analysis.reason ?? 'No analysis yet.'}
        {analysis.manualTriageRequired && (
          <p className="mt-1 text-text-subtle">This ticket needs manual triage.</p>
        )}
      </div>
    )
  }
  const s = analysis.signals
  const pretty = (v: string) => v.replace(/_/g, ' ').toLowerCase()
  const tiles: { k: string; v: string; flag?: boolean }[] = [
    { k: 'Category', v: s.category },
    { k: 'Impact', v: pretty(s.reportedImpact) },
    { k: 'Urgency', v: pretty(s.linguisticUrgency), flag: s.linguisticUrgency === 'HIGH' },
    { k: 'Payment', v: s.paymentAffected ? 'affected' : 'not affected', flag: s.paymentAffected },
  ]
  const pct = Math.round(s.confidence * 100)
  return (
    <div className="space-y-2.5 p-4">
      <div className="grid grid-cols-2 gap-2">
        {tiles.map((t) => (
          <div key={t.k} className="rounded-lg border border-border bg-surface-2/50 px-2.5 py-2">
            <p className="text-[11px] text-text-subtle">{t.k}</p>
            <p className={cn('mt-0.5 truncate text-[13px] font-medium capitalize', t.flag ? 'text-warning' : 'text-text')}>{t.v}</p>
          </div>
        ))}
      </div>
      {(s.serviceDownClaimed || s.dataLossClaimed) && (
        <p className="rounded-lg bg-danger-bg px-2.5 py-2 text-[12px] text-danger">
          Customer reports {[s.serviceDownClaimed && 'service down', s.dataLossClaimed && 'data loss'].filter(Boolean).join(' and ')}.
        </p>
      )}
      <div>
        <div className="mb-1 flex justify-between text-[11px] text-text-subtle">
          <span>Model confidence</span>
          <span className="tabular-nums text-text-muted">{pct}%</span>
        </div>
        <div className="h-1 overflow-hidden rounded-full bg-surface-2">
          <div className={cn('h-full rounded-full', pct >= 70 ? 'bg-primary' : 'bg-warning')} style={{ width: `${pct}%` }} />
        </div>
      </div>
    </div>
  )
}

function ClaimCard({ claim, index }: { claim: ClaimView; index: number }) {
  // `kept` is what the backend actually decides on — see ClaimView's own doc: a claim is
  // struck through and its reason shown whenever it was dropped, regardless of which of
  // the four non-SUPPORTED verdicts caused it (NOT_SUPPORTED, FAILED_NUMERIC_CHECK, a
  // stuck PENDING). Matching on `verdict` alone would miss all but one of those.
  const dropped = !claim.kept
  return (
    <div className={cn('rounded-lg border p-3', dropped ? 'border-danger/25 bg-danger-bg' : 'border-border bg-surface-2/50')}>
      <p
        className={cn(
          'text-[13px] leading-relaxed',
          dropped ? 'text-text-subtle line-through decoration-danger/60' : 'text-text',
        )}
      >
        <span className="mr-1 font-medium text-text-subtle">&#9312;{index > 0 ? String(index + 1) : ''}</span>
        {claim.text}
      </p>
      {dropped ? (
        <p className="mt-1.5 flex items-center gap-1 text-[12px] text-danger">
          Dropped &mdash; {claim.rejectionReason}
        </p>
      ) : (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {claim.citations.map((c) => (
            <CitationPopover key={c.chunkId} citation={c} />
          ))}
          {claim.verdict === 'PARTIAL' && (
            <span className="inline-flex items-center rounded-sm bg-warning-bg px-1.5 py-0.5 text-[11px] font-medium text-warning">
              Partial
            </span>
          )}
        </div>
      )}
    </div>
  )
}

function DraftPanel({
  draft,
  onUseReply,
}: {
  draft: DraftView
  onUseReply: (text: string) => void
}) {
  if (draft.status === 'PENDING') {
    return (
      <div className="space-y-2 p-4">
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
      </div>
    )
  }
  if (draft.status === 'SUPPRESSED_LOW_COVERAGE' || draft.status === 'SUPPRESSED_NO_EVIDENCE') {
    return (
      <div className="p-4 text-[13px] text-text-muted">
        <p className="font-medium text-text">Not enough grounded evidence to draft a reply.</p>
        <p className="mt-1 text-text-subtle">{draft.suppressionReason}</p>
      </div>
    )
  }
  if (draft.status === 'FAILED') {
    return <p className="p-4 text-[13px] text-text-muted">The draft failed. Try again from the ticket.</p>
  }

  const covered = draft.claims.filter((c) => c.kept).length
  return (
    <div className="p-4">
      <div className="mb-2.5 flex items-center justify-between">
        <span className="text-[12px] font-semibold uppercase tracking-wide text-text-subtle">
          Suggested reply
        </span>
        <span className="text-[12px] text-text-muted">
          Coverage {covered}/{draft.claims.length}
        </span>
      </div>
      <div className="space-y-2">
        {draft.claims.map((c, i) => (
          <ClaimCard key={i} claim={c} index={i} />
        ))}
      </div>
      {draft.unresolvedAspects.length > 0 && (
        <div className="mt-3 rounded-md bg-surface-2 p-2.5 text-[12px] text-text-muted">
          <p className="mb-1 font-medium text-text">Not covered by the knowledge base:</p>
          <ul className="list-inside list-disc space-y-0.5">
            {draft.unresolvedAspects.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}
      {draft.assembledText && (
        <Button size="sm" className="mt-3 w-full" onClick={() => onUseReply(draft.assembledText!)}>
          Use reply
        </Button>
      )}
    </div>
  )
}

export function AiAssistPanel({
  ticketId,
  onUseReply,
}: {
  ticketId: number
  onUseReply: (text: string) => void
}) {
  const [collapsed, setCollapsed] = React.useState(() => {
    const stored = localStorage.getItem('ai-rail-collapsed')
    // No stored choice: open on wide screens, closed where it would crush the thread.
    return stored === null ? window.innerWidth < 1280 : stored === '1'
  })
  const [draftId, setDraftId] = React.useState<number | null>(null)
  const analysis = useTicketAnalysis(ticketId, { poll: true })
  const draft = useDraft(draftId)
  const requestDraft = useRequestDraft()

  function toggle() {
    setCollapsed((c) => {
      localStorage.setItem('ai-rail-collapsed', c ? '0' : '1')
      return !c
    })
  }

  async function handleRequestDraft() {
    const result = await requestDraft.mutateAsync({ ticketId })
    setDraftId(result.draftId)
  }

  if (collapsed) {
    return (
      <button
        onClick={toggle}
        className="flex w-11 flex-col items-center gap-2 border-l border-border bg-glass py-4 text-text-muted backdrop-blur-xl hover:text-text"
        aria-label="Expand AI assist"
      >
        <Sparkles className="size-4 text-primary" aria-hidden />
        <span className="text-[12px] font-medium [writing-mode:vertical-rl]">AI Assist</span>
        <ChevronRight className="size-3.5 rotate-180" aria-hidden />
      </button>
    )
  }

  return (
    <aside className="flex w-[340px] shrink-0 flex-col border-l border-border bg-glass backdrop-blur-xl max-xl:absolute max-xl:inset-y-0 max-xl:right-0 max-xl:z-20 max-xl:bg-surface max-xl:shadow-popover">
      <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
        <span className="flex items-center gap-1.5 text-[13px] font-semibold text-text">
          <Sparkles className="size-3.5 text-primary" aria-hidden /> AI Assist
        </span>
        <button onClick={toggle} className="text-text-subtle hover:text-text" aria-label="Collapse">
          <ChevronRight className="size-4" aria-hidden />
        </button>
      </div>

      <div className="border-b border-border">
        <div className="flex items-center justify-between px-4 pt-3">
          <span className="text-[12px] font-semibold uppercase tracking-wide text-text-subtle">Triage</span>
        </div>
        {analysis.data && <TriageSignals analysis={analysis.data} />}
      </div>

      <div className="flex-1 overflow-y-auto">
        {draftId && draft.data ? (
          <DraftPanel draft={draft.data} onUseReply={onUseReply} />
        ) : (
          <div className="p-4">
            <Button
              variant="secondary"
              size="sm"
              className="w-full"
              loading={requestDraft.isPending}
              onClick={handleRequestDraft}
            >
              <Sparkles className="size-3.5" aria-hidden /> Draft a reply
            </Button>
          </div>
        )}
      </div>
    </aside>
  )
}
