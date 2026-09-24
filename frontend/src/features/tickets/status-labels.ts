import type { TicketStatus } from '@/lib/types'

export const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: 'Open',
  TRIAGED: 'Triaged',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In progress',
  WAITING_ON_CUSTOMER: 'Waiting on customer',
  PENDING_THIRD_PARTY: 'Pending third party',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

/**
 * Moving here needs text from the person doing it: WAITING_ON_CUSTOMER/PENDING_THIRD_PARTY
 * pause a clock and the server requires a reason; RESOLVED needs the actual resolution -
 * it becomes the closing message the customer reads, and is what the knowledge-base indexer
 * checks the length of.
 */
export const REASON_REQUIRED: TicketStatus[] = ['WAITING_ON_CUSTOMER', 'PENDING_THIRD_PARTY', 'RESOLVED']

/**
 * Moves that notify the customer and cannot be walked back through the state machine
 * (RESOLVED only goes forward to CLOSED or back to OPEN via a reopen). These are held for
 * a few seconds with an Undo instead of being committed on click (audit U14).
 */
export const DEFERRED: TicketStatus[] = ['RESOLVED', 'CLOSED']
