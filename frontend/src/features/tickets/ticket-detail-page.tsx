import { ArrowLeft, SearchX } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorBanner } from '@/components/ui/error-banner'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/features/auth/auth-context'
import { ApiError, api } from '@/lib/api-client'
import type { MessageView, TicketDetail, TicketStatus } from '@/lib/types'
import { AiAssistPanel } from './ai-assist-panel'
import { useChangeStatus, useSendMessage, useTicket } from './api'
import { IncidentBanner } from './incident-banner'
import { MessageBubble } from './message-bubble'
import { MessageComposer } from './message-composer'
import { PriorityLabel } from './priority-badge'
import { PriorityRationale } from './priority-rationale'
import { SlaStrip } from './sla-chip'
import { StatusDropdown } from './status-dropdown'
import { allowedTransitionsFrom } from './ticket-state-machine'

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

export function TicketDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const navigate = useNavigate()
  const { push } = useToast()
  const { data: ticket, isLoading, isError, error, refetch } = useTicket(id!)
  const sendMessage = useSendMessage()
  const changeStatus = useChangeStatus()
  const [prefill, setPrefill] = React.useState<string | undefined>()

  if (isLoading) {
    return (
      <div className="mx-auto max-w-5xl space-y-3 px-5 py-6">
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
            description="It may have been removed, or it belongs to a queue you don't have access to."
            action={{ label: 'Back to my home page', onClick: () => navigate('/') }}
          />
        </div>
      </div>
    )
  }
  if (isError || !ticket) {
    return (
      <div className="mx-auto max-w-5xl px-5 py-6">
        <ErrorBanner message="Couldn't load this ticket." onRetry={() => refetch()} />
      </div>
    )
  }

  const isCustomer = user?.role === 'CUSTOMER'

  async function handleSend(body: string, visibility: 'PUBLIC' | 'INTERNAL') {
    await sendMessage.mutateAsync({ ticketId: ticket!.id, body, visibility })
    setPrefill(undefined)
  }

  async function handleStatusChange(status: TicketStatus, reason?: string) {
    try {
      const res = await api.get(`/tickets/${ticket!.id}`)
      await changeStatus.mutateAsync({ ticketId: ticket!.id, status, reason, etag: res.headers.etag })
      push('success', 'Status updated.')
    } catch (err) {
      push('error', err instanceof ApiError ? err.problem.detail : 'Could not change status.')
    }
  }

  if (isCustomer) {
    return (
      <div className="mx-auto max-w-3xl px-5 py-8 sm:px-8 lg:py-10 animate-slide-up">
        <button onClick={() => navigate('/my-tickets')} className="mb-4 flex items-center gap-1 text-[13px] text-text-muted hover:text-text">
          <ArrowLeft className="size-3.5" aria-hidden /> My tickets
        </button>
        <h1 className="text-[26px] font-semibold leading-tight tracking-[-0.025em] text-text">{ticket.subject}</h1>
        <p className="mt-2 flex items-center gap-2 text-[13px] text-text-muted">
          <span>{ticket.reference}</span>
          <span className="rounded-md bg-primary-bg px-2 py-0.5 text-[11.5px] font-medium text-primary">{CUSTOMER_STATUS_LABEL[ticket.status]}</span>
        </p>
        {/* TicketCustomerResponse carries no incident field yet - a customer-safe incident
            hint (without exposing linkage confidence or internal detail) is backend work
            this pass didn't add. CustomerIncidentBanner is kept for when it does. */}
        <div className="mt-7 space-y-4">
          <MessageBubble message={openingMessage(ticket)} />
          {ticket.messages
            .filter((m) => m.visibility === 'PUBLIC')
            .map((m) => (
              <MessageBubble key={m.id} message={m} />
            ))}
        </div>
        {!['RESOLVED', 'CLOSED'].includes(ticket.status) && (
          <div className="mx-auto mt-6 w-full max-w-3xl">
            <MessageComposer allowInternal={false} onSend={handleSend} />
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="flex h-[calc(100svh-96px)] flex-col md:h-svh">
      <div className="border-b border-border bg-glass px-6 py-4 backdrop-blur-xl">
        <Link to="/queue" className="flex items-center gap-1 text-[13px] text-text-muted hover:text-text">
          <ArrowLeft className="size-3.5" aria-hidden /> Queue
        </Link>
        <div className="mt-1.5 flex items-center gap-3">
          <span className="text-[13px] font-medium text-text-subtle">{ticket.reference}</span>
          <h1 className="text-[20px] font-semibold tracking-[-0.02em] text-text">{ticket.subject}</h1>
        </div>
        <div className="mt-2.5 flex flex-wrap items-center gap-3 text-[13px]">
          <span className="flex items-center gap-1">
            <PriorityLabel priority={ticket.priority} />
            {ticket.priority !== 'UNTRIAGED' && <PriorityRationale ticketId={ticket.id} />}
          </span>
          {ticket.category && <span className="text-text-muted">{ticket.category}</span>}
          <span className="text-text-muted">{ticket.assignee?.fullName ?? 'Unassigned'}</span>
          <StatusDropdown
            current={ticket.status}
            allowed={allowedTransitionsFrom(ticket.status)}
            onChange={handleStatusChange}
          />
        </div>
      </div>

      <SlaStrip clocks={ticket.sla?.clocks} />

      {ticket.incident && (
        <IncidentBanner
          incidentId={ticket.incident.id}
          reference={ticket.incident.reference}
          title="linked to this incident"
        />
      )}

      <div className="relative flex flex-1 overflow-hidden">
        <div className="flex flex-1 flex-col overflow-y-auto px-6 py-6">
          <div className="mx-auto w-full max-w-3xl flex-1 space-y-4">
            <MessageBubble message={openingMessage(ticket)} />
            {ticket.messages.map((m) => (
              <MessageBubble key={m.id} message={m} />
            ))}
          </div>
          <div className="mx-auto mt-6 w-full max-w-3xl">
            <MessageComposer allowInternal prefill={prefill} onSend={handleSend} />
          </div>
        </div>

        <AiAssistPanel
          ticketId={ticket.id}
          onUseReply={(text) =>
            // The draft is verified claims only; the greeting and sign-off are the agent's.
            setPrefill(
              `Hi ${ticket.requester?.fullName.split(' ')[0] ?? 'there'},

${text}

Best regards,
${user?.fullName ?? ''}`,
            )
          }
        />
      </div>
    </div>
  )
}
