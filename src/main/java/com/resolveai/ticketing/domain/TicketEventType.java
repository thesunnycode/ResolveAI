package com.resolveai.ticketing.domain;

/**
 * Every kind of thing that can be recorded on a ticket's timeline.
 *
 * <p>The seventeen values here match {@code ck_event_type} exactly. Adding one means editing
 * a migration as well, which is the intended friction: the audit vocabulary is part of the
 * contract with anyone reading the history, not an implementation detail.
 */
public enum TicketEventType {

    CREATED,
    TRIAGED,
    ASSIGNED,
    STATUS_CHANGED,
    PRIORITY_CHANGED,
    MESSAGE_ADDED,
    SLA_STARTED,
    SLA_PAUSED,
    SLA_RESUMED,
    SLA_ESCALATED,
    SLA_BREACHED,
    SLA_MET,
    INCIDENT_LINKED,
    INCIDENT_DETACHED,
    RESOLVED,
    REOPENED,
    CLOSED
}
