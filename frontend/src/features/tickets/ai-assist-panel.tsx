import { BookPlus, Check, ChevronDown, ChevronRight, PenLine, RefreshCw, SearchCheck, Sparkles } from 'lucide-react'
import * as React from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { ApiError } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import type { AnalysisView, CitationView, ClaimView, DraftView } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useMediaQuery } from '@/lib/use-media-query'
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
      <div className="p-4 text-sm text-text-muted">
        {analysis.reason ?? 'No analysis yet.'}
        {analysis.manualTriageRequired && (
          <p className="mt-1 text-text-subtle">This ticket needs manual triage.</p>
        )}
      </div>
    )
  }
  const s = analysis.signals
  const pretty = (v: string) => v.replace(/_/g, ' ').toLowerCase()
  const pct = Math.round(s.confidence * 100)
  // One line instead of four 60px tiles (audit H6) - the draft is what agents came for.
  const parts: { v: string; flag?: boolean }[] = [
    { v: pretty(s.category) },
    { v: pretty(s.reportedImpact) },
    { v: `${pretty(s.linguisticUrgency)} urgency`, flag: s.linguisticUrgency === 'HIGH' },
    ...(s.paymentAffected ? [{ v: 'payment affected', flag: true }] : []),
  ]
  return (
    <div className="space-y-2 px-4 pb-3 pt-1.5">
      <p className="text-sm leading-relaxed text-text first-letter:uppercase">
        {parts.map((p, i) => (
          <React.Fragment key={p.v}>
            {i > 0 && (
              <span className="px-1 text-text-subtle" aria-hidden>
                ·
              </span>
            )}
            <span className={cn(p.flag && 'font-medium text-warning')}>{p.v}</span>
          </React.Fragment>
        ))}
      </p>
      {(s.serviceDownClaimed || s.dataLossClaimed) && (
        <p className="rounded-lg bg-danger-bg px-2.5 py-2 text-xs text-danger">
          Customer reports {[s.serviceDownClaimed && 'service down', s.dataLossClaimed && 'data loss'].filter(Boolean).join(' and ')}.
        </p>
      )}
      <div className="flex items-center gap-2 text-xs text-text-subtle">
        <span id="triage-confidence">Model confidence</span>
        <div
          role="progressbar"
          aria-labelledby="triage-confidence"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={pct}
          aria-valuetext={`${pct}%${pct < 70 ? ', low' : ''}`}
          className="h-1 flex-1 overflow-hidden rounded-full bg-surface-2"
        >
          <div className={cn('h-full rounded-full', pct >= 70 ? 'bg-primary' : 'bg-warning')} style={{ width: `${pct}%` }} />
        </div>
        <span className="tabular-nums text-text-muted" aria-hidden>
          {pct}%
        </span>
      </div>
    </div>
  )
}

const CIRCLED = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩']

/** One chip per document: four chunks of one runbook are one source, not four. */
function uniqueByDocument(citations: CitationView[]): CitationView[] {
  const seen = new Set<number>()
  return citations.filter((c) => {
    if (seen.has(c.documentId)) return false
    seen.add(c.documentId)
    return true
  })
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
          'text-sm leading-relaxed',
          dropped ? 'text-text-subtle line-through decoration-danger/60' : 'text-text',
        )}
      >
        <span className="mr-1 font-medium text-text-subtle">{CIRCLED[index] ?? `${index + 1}.`}</span>
        {claim.text}
      </p>
      {dropped ? (
        <p className="mt-1.5 flex items-center gap-1 text-xs text-danger">
          Dropped &mdash; {claim.rejectionReason}
        </p>
      ) : (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {uniqueByDocument(claim.citations).map((c) => (
            <CitationPopover key={c.documentId} citation={c} />
          ))}
          {claim.verdict === 'PARTIAL' && (
            <span className="inline-flex items-center rounded-sm bg-warning-bg px-1.5 py-0.5 text-xs font-medium text-warning">
              Partial
            </span>
          )}
        </div>
      )}
    </div>
  )
}

type Example = { id: number; reference: string } | null | undefined

/**
 * A suppressed draft is ResolveAI working as designed — "a confidently wrong answer is
 * worse than no answer" — so it is framed as a decision with somewhere to go next, not
 * as an error full of engineering numbers (those sit behind "Why exactly?").
 */
function SuppressedDraft({
  draft,
  isAdmin,
  coveredExample,
  onReplyManually,
  onRegenerate,
  regenerating,
}: {
  draft: DraftView
  isAdmin: boolean
  coveredExample: Example
  onReplyManually: () => void
  onRegenerate: () => void
  regenerating: boolean
}) {
  const noEvidence = draft.status === 'SUPPRESSED_NO_EVIDENCE'
  return (
    <div className="p-4 text-sm">
      <p className="flex items-center gap-1.5 font-medium text-text">
        <SearchCheck className="size-3.5 text-text-muted" aria-hidden />
        No draft — ResolveAI didn&apos;t guess
      </p>
      <p className="mt-1.5 leading-relaxed text-text-muted">
        {noEvidence
          ? 'Nothing in the knowledge base covers this ticket, so there was nothing to cite.'
          : "The knowledge base didn't back up enough of what a reply would need to say."}{' '}
        Unsupported answers are withheld on purpose — this one is yours to write.
      </p>
      <div className="mt-3 grid gap-2">
        <Button size="sm" className="w-full" onClick={onReplyManually}>
          <PenLine className="size-3.5" aria-hidden /> Reply manually
        </Button>
        {isAdmin && (
          <Button asChild variant="secondary" size="sm" className="w-full">
            <Link to="/knowledge/new">
              <BookPlus className="size-3.5" aria-hidden /> Add a knowledge article
            </Link>
          </Button>
        )}
        {coveredExample && (
          <Button asChild variant="ghost" size="sm" className="w-full">
            <Link to={`/tickets/${coveredExample.id}`}>See a ticket the knowledge base covers ({coveredExample.reference})</Link>
          </Button>
        )}
      </div>
      <details className="mt-3 text-xs text-text-subtle">
        <summary className="cursor-pointer select-none hover:text-text-muted">Why exactly?</summary>
        <p className="mt-1.5 leading-relaxed">{draft.suppressionReason}</p>
        <button
          type="button"
          onClick={onRegenerate}
          disabled={regenerating}
          className="mt-2 inline-flex min-h-6 items-center gap-1 text-text-muted hover:text-text disabled:opacity-50"
        >
          <RefreshCw className={cn('size-3', regenerating && 'animate-spin')} aria-hidden /> Try again (e.g. after adding an article)
        </button>
      </details>
    </div>
  )
}

const STAGES = ['Retrieving sources', 'Drafting', 'Checking each claim'] as const

/**
 * Staged progress for the ~4s a draft takes (audit F4). The steps are what the pipeline
 * really does, in order; the timing is an estimate, so the last step waits for the server.
 */
function DraftStages() {
  const [stage, setStage] = React.useState(0)
  React.useEffect(() => {
    const t1 = window.setTimeout(() => setStage(1), 1200)
    const t2 = window.setTimeout(() => setStage(2), 2800)
    return () => {
      window.clearTimeout(t1)
      window.clearTimeout(t2)
    }
  }, [])
  return (
    <ol className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs" aria-live="polite">
      {STAGES.map((label, i) => (
        <li
          key={label}
          className={cn(
            'flex items-center gap-1.5',
            i < stage ? 'text-text-muted' : i === stage ? 'font-medium text-text' : 'text-text-subtle',
          )}
        >
          {i > 0 && <span aria-hidden>→</span>}
          {i < stage ? (
            <Check className="size-3 text-success" aria-hidden />
          ) : i === stage ? (
            <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden />
          ) : null}
          <span>{label}</span>
          {i === stage && <span className="sr-only">(in progress)</span>}
        </li>
      ))}
    </ol>
  )
}

function DraftPanel({
  draft,
  isAdmin,
  coveredExample,
  onUseReply,
  onReplyManually,
  onRegenerate,
  regenerating,
}: {
  draft: DraftView
  isAdmin: boolean
  coveredExample: Example
  onUseReply: (text: string, draftId: number) => void
  onReplyManually: () => void
  onRegenerate: () => void
  regenerating: boolean
}) {
  if (draft.status === 'PENDING') {
    return (
      <div className="space-y-2 p-4">
        <DraftStages />
        <Skeleton className="h-3.5 w-1/3" />
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-14 w-full" />
      </div>
    )
  }
  if (draft.status === 'SUPPRESSED_LOW_COVERAGE' || draft.status === 'SUPPRESSED_NO_EVIDENCE') {
    return (
      <SuppressedDraft
        draft={draft}
        isAdmin={isAdmin}
        coveredExample={coveredExample}
        onReplyManually={onReplyManually}
        onRegenerate={onRegenerate}
        regenerating={regenerating}
      />
    )
  }
  if (draft.status === 'FAILED') {
    return (
      <div className="p-4 text-sm text-text-muted">
        <p>The draft couldn&apos;t be generated this time.</p>
        <Button variant="secondary" size="sm" className="mt-3 w-full" loading={regenerating} onClick={onRegenerate}>
          <RefreshCw className="size-3.5" aria-hidden /> Try again
        </Button>
      </div>
    )
  }

  const covered = draft.claims.filter((c) => c.kept).length
  return (
    <div className="p-4">
      <div className="mb-2.5 flex items-center justify-between">
        <span className="text-xs font-semibold uppercase tracking-wide text-text-subtle">Suggested reply</span>
        <span className="text-xs text-text-muted" title="Claims backed by a cited source">
          {covered} of {draft.claims.length} cited
        </span>
      </div>
      <div className="space-y-2">
        {draft.claims.map((c, i) => (
          <ClaimCard key={i} claim={c} index={i} />
        ))}
      </div>
      {draft.unresolvedAspects.length > 0 && (
        <div className="mt-3 rounded-md bg-surface-2 p-2.5 text-xs text-text-muted">
          <p className="mb-1 font-medium text-text">Not covered by the knowledge base — answer these yourself:</p>
          <ul className="list-inside list-disc space-y-0.5">
            {draft.unresolvedAspects.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}
      {draft.assembledText && (
        <Button size="sm" className="mt-3 w-full" onClick={() => onUseReply(draft.assembledText!, draft.id)}>
          Use reply
        </Button>
      )}
      <button
        type="button"
        onClick={onRegenerate}
        disabled={regenerating}
        className="mt-2 flex min-h-8 w-full items-center justify-center gap-1.5 rounded-md text-xs text-text-subtle hover:bg-surface-2 hover:text-text disabled:opacity-50"
      >
        <RefreshCw className={cn('size-3', regenerating && 'animate-spin')} aria-hidden /> Regenerate
      </button>
    </div>
  )
}

function draftErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return 'Could not request a draft. Try again.'
  switch (err.problem.errorCode) {
    case 'AI_BUDGET_EXHAUSTED':
      return "This workspace's AI budget for the month is used up, so no new drafts can be generated. Existing drafts still work."
    case 'DRAFT_IN_PROGRESS':
      return 'A draft for this ticket is already being written — it will appear here in a moment.'
    case 'AI_DISABLED_BY_POLICY':
      return 'AI drafting is switched off for this workspace by its admin.'
    case 'NO_KNOWLEDGE_BASE':
      return 'This workspace has no knowledge base yet, so there is nothing to draft from.'
    case 'RATE_LIMITED':
      return 'Too many drafts requested just now. Wait a moment and try again.'
    default:
      return err.problem.detail || 'Could not request a draft. Try again.'
  }
}

export function AiAssistPanel({
  ticketId,
  latestDraftId,
  isAdmin,
  coveredExample,
  onUseReply,
  onReplyManually,
  context,
}: {
  ticketId: number
  /**
   * A draft that already exists for this ticket — shown straight away instead of an empty
   * rail. Read once on mount; the parent keys this component by ticket id, so moving to
   * another ticket starts fresh from that ticket's draft.
   */
  latestDraftId?: number | null
  isAdmin: boolean
  /** A ticket the knowledge base does cover, offered when this one's draft is suppressed. */
  coveredExample?: Example
  onUseReply: (text: string, draftId: number) => void
  onReplyManually: () => void
  /** Shown above triage - the customer-context pane (audit H2). */
  context?: React.ReactNode
}) {
  const { push } = useToast()
  const mobile = useMediaQuery('(max-width: 767px)')
  const [collapsed, setCollapsed] = React.useState(() => {
    const stored = localStorage.getItem('ai-rail-collapsed')
    // No stored choice: open at every width where it fits beside or over the thread. It
    // used to start collapsed below 1280px, hiding the product's main feature on most
    // laptops; now only a remembered collapse, or a phone, hides it.
    // Phones always start with the sheet closed - it covers the thread.
    if (window.innerWidth < 768) return true
    return stored === null ? false : stored === '1'
  })
  const [draftId, setDraftId] = React.useState<number | null>(latestDraftId ?? null)
  const analysis = useTicketAnalysis(ticketId, { poll: true })
  const draft = useDraft(draftId)
  const requestDraft = useRequestDraft()
  const reported = React.useRef<number | null>(null)

  React.useEffect(() => {
    const d = draft.data
    if (!d || d.status === 'PENDING' || reported.current === d.id) return
    reported.current = d.id
    track('draft_result', { status: d.status, coverage: d.coverage ?? undefined, claims: d.claims.length })
  }, [draft.data])

  function toggle() {
    setCollapsed((c) => {
      // The phone sheet is transient; only the desktop rail's choice is remembered.
      if (!mobile) localStorage.setItem('ai-rail-collapsed', c ? '0' : '1')
      return !c
    })
  }

  async function handleRequestDraft() {
    track('draft_requested', { regenerate: draftId !== null })
    try {
      const result = await requestDraft.mutateAsync({ ticketId })
      setDraftId(result.draftId)
    } catch (err) {
      // Used to be unhandled: on a 402/429/5xx the button stopped spinning and nothing
      // happened, at the exact moment a new user was trying the main feature.
      push('error', draftErrorMessage(err))
    }
  }

  if (collapsed) {
    // Phones get a floating button that opens a bottom sheet (audit R3); wider screens
    // keep the slim vertical strip beside the thread.
    if (mobile) {
      return (
        <button
          onClick={toggle}
          className="absolute right-3 top-2.5 z-20 flex min-h-11 items-center gap-1.5 rounded-full bg-primary px-4 text-sm font-medium text-primary-fg shadow-popover"
          aria-label="Open AI assist"
        >
          <Sparkles className="size-4" aria-hidden /> AI Assist
        </button>
      )
    }
    return (
      <button
        onClick={toggle}
        className="flex w-11 flex-col items-center gap-2 border-l border-border bg-glass py-4 text-text-muted backdrop-blur-xl hover:text-text"
        aria-label="Expand AI assist"
      >
        <Sparkles className="size-4 text-primary" aria-hidden />
        <span className="text-xs font-medium [writing-mode:vertical-rl]">AI Assist</span>
        <ChevronRight className="size-3.5 rotate-180" aria-hidden />
      </button>
    )
  }

  return (
    <>
      {mobile && <div className="fixed inset-0 z-[55] bg-black/30" onClick={toggle} aria-hidden />}
      <aside
        aria-label="AI assist"
        className={cn(
          'flex shrink-0 flex-col backdrop-blur-xl',
          mobile
            ? 'fixed inset-x-0 bottom-0 z-[60] max-h-[82svh] rounded-t-2xl border-t border-border bg-surface pb-[env(safe-area-inset-bottom)] shadow-popover'
            : 'w-[340px] border-l border-border bg-glass max-xl:absolute max-xl:inset-y-0 max-xl:right-0 max-xl:z-20 max-xl:bg-surface max-xl:shadow-popover',
        )}
      >
        <div className="flex items-center justify-between border-b border-border py-1.5 pl-4 pr-2">
          <span className="flex items-center gap-1.5 text-sm font-semibold text-text">
            <Sparkles className="size-3.5 text-primary" aria-hidden /> AI Assist
          </span>
          <button
            onClick={toggle}
            className="flex size-8 items-center justify-center rounded-md text-text-subtle hover:bg-surface-2 hover:text-text"
            aria-label="Collapse AI assist"
          >
            {mobile ? <ChevronDown className="size-4" aria-hidden /> : <ChevronRight className="size-4" aria-hidden />}
          </button>
        </div>

        {/* One scroll region for the whole rail (audit U12), not a scroller inside a scroller. */}
        <div className="flex-1 overflow-y-auto">
          {context}
      <div className="border-b border-border">
        <div className="flex items-center justify-between px-4 pt-3">
          <span className="text-xs font-semibold uppercase tracking-wide text-text-subtle">Triage</span>
        </div>
        {analysis.data && <TriageSignals analysis={analysis.data} />}
      </div>

      <div>
        {draftId && draft.data ? (
          <DraftPanel
            draft={draft.data}
            isAdmin={isAdmin}
            coveredExample={coveredExample && coveredExample.id !== ticketId ? coveredExample : null}
            onUseReply={(text, id) => {
              track('draft_used', { coverage: draft.data?.coverage ?? undefined })
              onUseReply(text, id)
            }}
            onReplyManually={onReplyManually}
            onRegenerate={handleRequestDraft}
            regenerating={requestDraft.isPending}
          />
        ) : (
          <div className="p-4">
            <p className="mb-3 text-sm leading-relaxed text-text-muted">
              Drafts a reply from your knowledge base. Every sentence cites its source; anything the sources
              don&apos;t support is dropped — or no draft is shown at all.
            </p>
            <Button size="sm" className="w-full" loading={requestDraft.isPending} onClick={handleRequestDraft}>
              <Sparkles className="size-3.5" aria-hidden /> Draft a reply
            </Button>
          </div>
        )}
      </div>
        </div>
      </aside>
    </>
  )
}
