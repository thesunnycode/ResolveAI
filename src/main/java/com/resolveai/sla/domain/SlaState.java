package com.resolveai.sla.domain;

/**
 * Where a clock is.
 *
 * <p>{@code RUNNING} is the only state the deadline poller looks at - the partial index
 * {@code idx_sla_poller} is {@code WHERE state = 'RUNNING'} - which is why pausing sets
 * {@code next_deadline_at} to null and the state together. Either one alone would leave a
 * paused clock either still being polled or still holding a deadline nobody honours.
 */
public enum SlaState {

    RUNNING,
    PAUSED,
    MET,
    BREACHED,
    /** The ticket went away before the clock could be judged. Excluded from uq_sla_ticket_kind. */
    CANCELLED;

    /** True once the clock has been judged and will never move again. */
    public boolean isTerminal() {
        return this == MET || this == BREACHED || this == CANCELLED;
    }
}
