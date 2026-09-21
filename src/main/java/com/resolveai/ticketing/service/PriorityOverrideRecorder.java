package com.resolveai.ticketing.service;

import com.resolveai.ticketing.domain.Priority;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the row that records a human disagreeing with the policy.
 *
 * <h2>Why the reason is mandatory and 10 characters long</h2>
 *
 * <p>Because this table is <b>training data</b>, not an audit log. "wrong" is not a
 * label; "enterprise customer, contract says P1 for any payment issue" is. Paired with
 * the {@code priority_decision} row it disagrees with — which carries the model's
 * signals and the rule trace separately — a corpus of these is the only thing that
 * answers the question that actually matters after six months: <i>are agents overriding
 * because the model misreads tickets, or because the policy is wrong?</i>
 *
 * <p>A minimum length is a crude proxy for a considered answer and it is not nothing: it
 * stops the reason field becoming a required-field speed bump that everyone types "x"
 * into, which is what happens when the only validation is {@code @NotBlank}.
 *
 * <h2>Why it lives in {@code ticketing} and not in {@code triage}</h2>
 *
 * <p>{@code triage} already depends on {@code ticketing} — the worker reads tickets and
 * starts their clocks. Having {@code TicketService} reach back into {@code triage} to
 * write this row would close that into a cycle, and a cycle between the two largest
 * packages in the system is how a codebase stops being separable. An override is a human
 * action on a ticket, so it belongs on this side of the line anyway.
 */
@Component
public class PriorityOverrideRecorder {

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public PriorityOverrideRecorder(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /**
     * Records the override in the caller's transaction.
     *
     * <p>{@code MANDATORY}: the row and the priority change on the ticket must commit
     * together or not at all. A ticket at P1 with no row saying who raised it and why is
     * an unexplainable priority; a row with no priority change is a label for something
     * that never happened. Both are worse than the operation failing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long tenantId, Long ticketId, Priority from, Priority to,
                       String reason, Long userId) {
        jdbc.update("""
                INSERT INTO priority_override (ticket_id, tenant_id, from_priority,
                                               to_priority, reason, overridden_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ticketId, tenantId, from.name(), to.name(), reason, userId);

        // Tagged by direction, because the two mean different things. A pile of upward
        // overrides says the policy is too conservative; a pile of downward ones says
        // the model is over-reading impact. One untagged counter would average them into
        // a number that means nothing.
        metrics.counter("triage.priority.override",
                "direction", to.compareTo(from) < 0 ? "up" : "down",
                "from", from.name(), "to", to.name()).increment();
    }
}
