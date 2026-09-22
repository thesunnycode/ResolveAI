package com.resolveai.incidents.domain;

/**
 * The five states an incident can be in. Matches {@code ck_incident_status}.
 *
 * <p>{@code PROPOSED} is the gate's own output — no human has looked at it yet.
 * {@code MITIGATED} exists between {@code CONFIRMED} and {@code RESOLVED} for the case
 * doc 12 does not force a workflow through: a team lead can mark a storm as "the cause is
 * understood and traffic is recovering" before every linked ticket is individually closed.
 * Nothing in Phase 8 transitions a ticket into it automatically; it is a manual annotation.
 */
public enum IncidentStatus {
    PROPOSED,
    CONFIRMED,
    REJECTED,
    MITIGATED,
    RESOLVED;

    /** {@code PROPOSED}, {@code CONFIRMED} and {@code MITIGATED} — a live incident can still gain or lose tickets. */
    public boolean isLive() {
        return this == PROPOSED || this == CONFIRMED || this == MITIGATED;
    }
}
