package com.resolveai.sla.domain;

/**
 * The two clocks a ticket carries.
 *
 * <p>They are separate records rather than two columns on one, because they start
 * together, stop independently, and are judged separately: a ticket can meet its
 * first-response target and breach its resolution target, and that is the common case
 * rather than an edge one.
 */
public enum SlaKind {

    /** Time until an agent first replies in public. Stopped by that reply, never paused. */
    FIRST_RESPONSE,

    /** Time until the ticket is resolved. Pauses while waiting on the customer. */
    RESOLUTION
}
