import type { TicketStatus } from '@/lib/types'

/**
 * A client-side mirror of the backend's {@code TicketStateMachine} — used only to grey out
 * illegal options before a request is made. The server enforces the same table on
 * {@code POST /tickets/{id}/status} independently (see that class's own doc: "the UI also
 * greys out the buttons. That is a convenience, not a control"), so a stale copy here can
 * only make the dropdown too strict or too lenient by one release, never let an illegal
 * transition through.
 */
const TRANSITIONS: Record<TicketStatus, TicketStatus[]> = {
  OPEN: ['TRIAGED', 'ASSIGNED', 'CLOSED'],
  TRIAGED: ['ASSIGNED', 'CLOSED'],
  ASSIGNED: ['IN_PROGRESS', 'WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY', 'RESOLVED', 'OPEN'],
  IN_PROGRESS: ['WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY', 'RESOLVED', 'ASSIGNED'],
  WAITING_ON_CUSTOMER: ['IN_PROGRESS', 'RESOLVED', 'CLOSED'],
  PENDING_THIRD_PARTY: ['IN_PROGRESS', 'RESOLVED'],
  RESOLVED: ['CLOSED', 'OPEN'],
  CLOSED: [],
}

export function allowedTransitionsFrom(current: TicketStatus): TicketStatus[] {
  return TRANSITIONS[current] ?? []
}
