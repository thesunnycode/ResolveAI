package com.resolveai.ticketing.domain;

/**
 * Ticket priority, with {@code UNTRIAGED} as an explicit fifth value rather than a null.
 *
 * <p><b>{@code UNTRIAGED} is the reason this enum has five constants and {@code sla_policy}
 * has four.</b> A ticket has no SLA until its priority is known, and modelling "not yet
 * decided" as {@code null} would make every read site handle a null it would eventually
 * forget about. As a value it is unmissable: {@code SlaPolicy} simply has no row for it, so
 * starting a clock on an untriaged ticket fails loudly instead of quietly defaulting to P4.
 */
public enum Priority {

    UNTRIAGED,
    P1,
    P2,
    P3,
    P4;

    /** True once triage (or a human) has decided. Only then can an SLA policy be resolved. */
    public boolean isTriaged() {
        return this != UNTRIAGED;
    }
}
