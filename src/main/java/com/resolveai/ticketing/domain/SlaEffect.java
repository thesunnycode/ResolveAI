package com.resolveai.ticketing.domain;

/**
 * What a status transition does to the resolution clock.
 *
 * <p>This enum is why {@link TicketStateMachine} owns clock behaviour rather than the
 * service layer. The alternative — {@code if (target == WAITING_ON_CUSTOMER) pause();}
 * scattered across three endpoints — means that adding a ninth status compiles fine,
 * passes review, and silently has no clock behaviour at all. Here, the transition table and
 * the clock effect are the same table, so a new status cannot be added without deciding.
 */
public enum SlaEffect {

    /** No clock change. */
    NONE,
    /** Entering a waiting state: the delay belongs to the customer or a third party. */
    PAUSE,
    /** Leaving a waiting state: the clock picks up where it left off. */
    RESUME,
    /** Terminal: the resolution clock stops and is judged MET or BREACHED. */
    STOP
}
