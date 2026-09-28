import { ArrowLeft, Check, Copy, SearchX } from 'lucide-react'
import * as React from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useDocumentTitle } from '@/components/layout/route-a11y'
import { EmptyState, ErrorState } from '@/components/app/states'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { homeFor, useAuth } from '@/features/auth/auth-context'
import { useIsDemoWorkspace, useShowcase } from '@/features/demo/api'
import { useIncident } from '@/features/incidents/api'
import { ApiError, api } from '@/lib/api-client'
import { track } from '@/lib/analytics'
import { useHotkey } from '@/lib/hotkeys'
import type { MessageView, TicketDetail, TicketStatus } from '@/lib/types'
import { humanize } from '@/lib/labels'
import { cn } from '@/lib/utils'
import { AiAssistPanel } from './ai-assist-panel'
import { useAssignTicket, useCanSelfAssign, useSendMessage, useTicket } from './api'
import { AssignControl } from './assign-control'
import { CustomerContext } from './customer-context'
import { IncidentBanner } from './incident-banner'
import { MessageBubble } from './message-bubble'
import { MessageComposer } from './message-composer'
import { PriorityLabel } from './priority-badge'
import { PriorityRationale } from './priority-rationale'
import { ResponsePromise } from './response-promise'
import { SlaStrip } from './sla-chip'
import { StatusDropdown } from './status-dropdown'
import { REASON_REQUIRED } from './status-labels'
import { allowedTransitionsFrom } from './ticket-state-machine'
import { useStatusChanger } from './use-status-change'

const CUSTOMER_STATUS_LABEL: Record<string, string> = {
  OPEN: 'Open',
  TRIAGED: 'In progress',
  ASSIGNED: 'In progress',
  IN_PROGRESS: 'In progress',
  WAITING_ON_CUSTOMER: 'Waiting for your reply',
  PENDING_THIRD_PARTY: 'In progress',
  RESOLVED: 'Resolved',
  CLOSED: 'Resolved',
}

function openingMessage(ticket: TicketDetail): MessageView {
  // The ticket's opening description isn't itself a row in `messages` -
  // it's stored on the ticket as `body`. Synthesize it as the thread's
  // first entry so the requester's original report is never invisible.
  return {
    id: -1,
    authorId: ticket.requester?.id ?? -1,
    authorName: ticket.requester?.fullName ?? 'Customer',
    authorRole: 'CUSTOMER',
    body: ticket.body,
    visibility: 'PUBLIC',
    isFirstResponse: false,
    fromDraftId: null,
    createdAt: ticket.createdAt,
  }
}

/** Click to copy (audit F7) - references get pasted into Slack and incident notes all day. */
function CopyReference({ reference, className }: { reference: string; className?: string }) {
  const [copied, setCopied] = React.useState(false)
  const copy = React.useCallback(() => {
    void navigator.clipboard?.writeText(reference).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    })
  }, [reference])
  useHotkey('c', copy)
  return (
    <button
      type="button"
      onClick={copy}
      title="Copy reference (c)"
      className={cn(
        'group inline-flex min-h-6 w-fit items-center gap-1 rounded px-1 -mx-1 text-sm font-medium tabular-nums text-muted-foreground hover:bg-muted hover:text-foreground',
        className,
      )}
    >
      {reference}
      {copied ? (
        <Check className="size-3 text-success" aria-hidden />
      ) : (
        <Copy className="size-3 opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100" aria-hidden />
      )}
      <span className="sr-only" aria-live="polite">
        {copied ? 'Copied' : ''}
      </span>
    </button>
  )
}

export function TicketDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const navigate = useNavigate()
  const { push } = useToast()
  const location = useLocation()
  const isCustomerUser = user?.role === 'CUSTOMER'
  const { data: ticket, isLoading, isError, error, refetch } = useTicket(id!, {
    // Customer view: keep checking until triage has set the response promise.
    pollWhile: (t) => isCustomerUser && !t.responseTarget && !['RESOLVED', 'CLOSED'].includes(t.status),
  })
  const sendMessage = useSendMessage()
  const assign = useAssignTicket()
  const status = useStatusChanger()
  const [pendingTo, setPendingTo] = React.useState<TicketStatus | null>(null)
  const [prefill, setPrefill] = React.useState<string | undefined>()
  // The draft the prefilled reply came from, sent as fromDraftId so the server records
  // how much the agent edited it (agent_draft_action) — the draft-quality signal.
  const [prefillDraftId, setPrefillDraftId] = React.useState<number | undefined>()
  const [focusSignal, setFocusSignal] = React.useState(0)
  // Optimistic bubble while a reply is in flight (audit F2).
  const [sendingMessage, setSendingMessage] = React.useState<MessageView | null>(null)
  const inDemo = useIsDemoWorkspace(user?.tenantSlug)
  const showcase = useShowcase(inDemo && !isCustomerUser)
  const incident = useIncident(ticket?.incident?.id ?? '')
  const justCreated = (location.state as { justCreated?: boolean } | null)?.justCreated === true
  const isStaff = !!user && !isCustomerUser
  const canAssignOthers = user?.role === 'TEAM_LEAD' || user?.role === 'ADMIN'
  const canSelfAssign = useCanSelfAssign(user)

  useDocumentTitle(ticket ? `${ticket.reference} · ${ticket.subject}` : 'Ticket')

  React.useEffect(() => {
    if (ticket) track('ticket_opened', { role: user?.role, priority: ticket.priority, hasDraft: !!ticket.latestDraftId })
    // Once per ticket, not on every poll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ticket?.id])

  // Keyboard (audit F6): r reply, a assign, e resolve, Esc back.
  useHotkey('r', () => setFocusSignal((n) => n + 1), { enabled: !!ticket })
  useHotkey('a', () => void assignToMe(), { enabled: canSelfAssign && !!ticket && !ticket.assignee })
  useHotkey('e', () => ticket && changeStatus('RESOLVED'), {
    enabled: isStaff && !!ticket && !pendingTo && allowedTransitionsFrom(ticket.status).includes('RESOLVED'),
  })
  useHotkey('Escape', () => navigate(isCustomerUser ? '/tickets' : '/queue'), { enabled: !!ticket })

  if (isLoading) {
    return (
      <div className="mx-auto max-w-5xl space-y-3 px-5 py-6" aria-busy="true">
        <Skeleton className="h-5 w-32" />
        <Skeleton className="h-7 w-2/3" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-24 w-full" />
      </div>
    )
  }
  if (isError && error instanceof ApiError && error.problem.status === 404) {
    // Also what another customer's ticket, or another team's, looks like: the API answers
    // 404 rather than 403 so a ticket's existence isn't confirmed. "Retry" would be a lie.
    return (
      <div className="mx-auto max-w-md px-5 py-16">
        <div className="glass rounded-xl">
          <EmptyState
            icon={SearchX}
            title="Ticket not found"
            body="It may have been removed, or it belongs to a queue you don't have access to."
            action={
              <Button variant="secondary" size="sm" onClick={() => navigate(user ? homeFor(user.role) : '/')}>
                Back to my home page
              </Button>
            }
          />
        </div>
      </div>
    )
  }
  if (isError || !ticket) {
    return (
      <div className="mx-auto max-w-5xl px-5 py-6">
        <ErrorState error={error} onRetry={() => refetch()} />
      </div>
    )
  }

  const t = ticket
  const isCustomer = isCustomerUser

  async function assignToMe() {
    try {
      const res = await api.get(`/tickets/${t.id}`)
      await assign.mutateAsync({ id: t.id, etag: res.headers.etag })
      push('success', `${t.reference} is yours.`)
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not assign this ticket.')
    }
  }

  function changeStatus(to: TicketStatus, reason?: string) {
    if (REASON_REQUIRED.includes(to) && !reason) return
    setPendingTo(to)
    status.request({
      ticketId: t.id,
      reference: t.reference,
      from: t.status,
      to,
      reason,
      onSettled: () => setPendingTo(null),
    })
  }

  async function handleSend(body: string, visibility: 'PUBLIC' | 'INTERNAL') {
    const fromDraftId = visibility === 'PUBLIC' ? prefillDraftId : undefined
    const closesFirstResponse =
      visibility === 'PUBLIC' && !isCustomer && t.sla?.clocks?.some((c) => c.kind === 'FIRST_RESPONSE' && c.state === 'RUNNING')
    setSendingMessage({
      id: -2,
      authorId: user?.id ?? -1,
      authorName: user?.fullName ?? 'You',
      authorRole: user?.role ?? 'AGENT',
      body,
      visibility,
      isFirstResponse: false,
      fromDraftId: null,
      createdAt: new Date().toISOString(),
    })
    try {
      await sendMessage.mutateAsync({ ticketId: t.id, body, visibility, fromDraftId })
      if (fromDraftId) track('reply_sent_from_draft')
      setPrefill(undefined)
      setPrefillDraftId(undefined)
      if (closesFirstResponse) push('success', 'Sent · first response met ✓')
    } finally {
      setSendingMessage(null)
    }
  }

  const thread = (messages: MessageView[]) => (
    <>
      <MessageBubble message={openingMessage(t)} />
      {messages.map((m) => (
        <MessageBubble key={m.id} message={m} />
      ))}
      {sendingMessage && <MessageBubble message={sendingMessage} pending />}
    </>
  )

  if (isCustomer) {
    const closed = ['RESOLVED', 'CLOSED'].includes(t.status)
    return (
      <div className="mx-auto flex min-h-[calc(100svh-7rem)] max-w-3xl flex-col px-5 pt-8 sm:px-8 md:min-h-svh lg:pt-10 animate-slide-up">
        <Link to="/tickets" className="mb-4 inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3.5" aria-hidden /> My tickets
        </Link>
        <h1 tabIndex={-1} className="text-2xl font-semibold leading-tight tracking-[-0.025em] text-foreground">
          {t.subject}
        </h1>
        <p className="mt-2 flex items-center gap-2 text-sm text-muted-foreground">
          <CopyReference reference={t.reference} />
          <span className="rounded-md bg-secondary px-2 py-0.5 text-xs font-medium text-primary">{CUSTOMER_STATUS_LABEL[t.status]}</span>
        </p>
        <ResponsePromise target={t.responseTarget} status={t.status} justCreated={justCreated} />
        {/* TicketCustomerResponse carries no incident field yet - a customer-safe incident
            hint (without exposing linkage confidence or internal detail) is backend work
            this pass didn't add. CustomerIncidentBanner is kept for when it does. */}
        <div className="mt-7 flex-1 space-y-4 pb-6">{thread(t.messages.filter((m) => m.visibility === 'PUBLIC'))}</div>
        {!closed && (
          // Audit R7: the reply box stays in reach at the end of a long thread.
          <div className="sticky bottom-16 z-10 -mx-2 bg-background/90 px-2 pb-4 pt-2 backdrop-blur md:bottom-0">
            <MessageComposer key={t.id} ticketId={t.id} allowInternal={false} onSend={handleSend} />
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="flex h-[calc(100svh-7.5rem-1px)] flex-col md:h-svh">
      <div className="border-b border-border bg-popover/80 px-4 py-3 backdrop-blur-xl sm:px-6">
        {/* Audit U4: the link is as wide as its text, not the whole header. */}
        <Link to="/queue" className="inline-flex min-h-6 w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-3.5" aria-hidden /> Queue
        </Link>
        {/* Audit R1: reference above the title on phones, beside it from sm up. */}
        <div className="mt-1 flex flex-col gap-0.5 sm:flex-row sm:items-baseline sm:gap-3">
          <CopyReference reference={t.reference} className="shrink-0" />
          <h1 tabIndex={-1} className="min-w-0 text-lg font-semibold leading-snug tracking-[-0.02em] text-foreground sm:text-xl">
            {t.subject}
          </h1>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
          <span className="flex items-center gap-0.5">
            <PriorityLabel priority={t.priority} showName />
            {t.priority !== 'UNTRIAGED' && <PriorityRationale ticketId={t.id} />}
          </span>
          {t.category && <span className="text-muted-foreground">{humanize(t.category)}</span>}
          <AssignControl
            ticketId={t.id}
            reference={t.reference}
            assignee={t.assignee}
            currentUserId={user?.id}
            canAssignOthers={canAssignOthers}
            canSelfAssign={canSelfAssign}
          />
          <StatusDropdown
            current={t.status}
            allowed={allowedTransitionsFrom(t.status)}
            pendingTo={pendingTo}
            onChange={(s, reason) => changeStatus(s, reason)}
          />
        </div>
      </div>

      <SlaStrip clocks={t.sla?.clocks} />

      {t.incident && (
        <IncidentBanner incidentId={t.incident.id} reference={t.incident.reference} title={incident.data?.title} />
      )}

      <div className="relative flex min-h-0 flex-1">
        <div className="flex min-w-0 flex-1 flex-col overflow-y-auto">
          <div className="mx-auto w-full max-w-3xl flex-1 space-y-4 px-4 py-6 max-md:pt-16 sm:px-6">{thread(t.messages)}</div>
          {/* Audit R3: the composer is pinned to the bottom of the thread, never below the fold. */}
          <div className="sticky bottom-0 z-10 border-t border-border bg-background/90 px-4 py-3 backdrop-blur sm:px-6">
            <div className="mx-auto w-full max-w-3xl">
              <MessageComposer key={t.id} ticketId={t.id} allowInternal prefill={prefill} focusSignal={focusSignal} onSend={handleSend} />
            </div>
          </div>
        </div>

        <AiAssistPanel
          key={t.id}
          ticketId={t.id}
          latestDraftId={t.latestDraftId}
          isAdmin={user?.role === 'ADMIN'}
          coveredExample={showcase.data?.draftTicket}
          context={<CustomerContext requester={t.requester} currentTicketId={t.id} />}
          onReplyManually={() => setFocusSignal((n) => n + 1)}
          onUseReply={(text, draftId) => {
            // The draft is verified claims only; the greeting and sign-off are the agent's.
            setPrefillDraftId(draftId)
            setPrefill(
              `Hi ${t.requester?.fullName.split(' ')[0] ?? 'there'},

${text}

Best regards,
${user?.fullName ?? ''}`,
            )
          }}
        />
      </div>
    </div>
  )
}
