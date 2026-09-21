package com.resolveai.sla.domain;

/**
 * Why a clock stopped counting. Matches {@code ck_segment_reason}.
 *
 * <p><b>An enum rather than free text.</b> A pause reason typed by an agent makes "how
 * often do we pause for the customer, and for how long?" unanswerable within a year -
 * there will be forty spellings of "waiting for customer" and no way to group them. The
 * cost is that adding a reason means a migration, which is the right amount of friction
 * for a value that reporting depends on.
 */
public enum PauseReason {
    WAITING_ON_CUSTOMER,
    PENDING_THIRD_PARTY,
    /** The ticket is attached to an incident; its own clock waits on the incident. */
    INCIDENT_LINKED
}
