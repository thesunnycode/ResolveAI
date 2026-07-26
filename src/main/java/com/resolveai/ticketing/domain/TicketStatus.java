package com.resolveai.ticketing.domain;

/**
 * The eight states a ticket can be in.
 *
 * <p>Persisted as a string, matched by {@code ck_ticket_status} in the database. The legal
 * moves between these values live in {@link TicketStateMachine} — deliberately not here, so
 * that the transition table is one readable object rather than eight scattered methods.
 *
 * <p>{@code CLOSED} is terminal. Reopening goes through {@code POST /reopen}, which is a
 * separate endpoint with its own side effects (a reopen counter and a fresh resolution
 * clock) rather than an ordinary status change.
 */
public enum TicketStatus {

    /** Created, not yet triaged or assigned. */
    OPEN,
    /** Priority and category are known. Set by Phase 6's triage worker. */
    TRIAGED,
    ASSIGNED,
    IN_PROGRESS,
    /** Entering pauses the resolution clock: the delay is the customer's, not the team's. */
    WAITING_ON_CUSTOMER,
    /** Same, for a vendor or upstream dependency. */
    PENDING_THIRD_PARTY,
    RESOLVED,
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** True for the two states in which the resolution clock does not run. */
    public boolean isWaiting() {
        return this == WAITING_ON_CUSTOMER || this == PENDING_THIRD_PARTY;
    }
}
