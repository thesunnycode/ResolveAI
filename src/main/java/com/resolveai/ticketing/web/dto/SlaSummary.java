package com.resolveai.ticketing.web.dto;

/**
 * The compact SLA view carried on a ticket row: enough to colour a queue, not enough to
 * debug a clock.
 *
 * <p>The full segment history lives on {@code GET /tickets/{id}/sla}. Putting it here would
 * mean every page of twenty-five tickets carried a few hundred segment rows nobody looks at.
 *
 * @param firstResponse null before triage has set a priority and started the clocks
 */
public record SlaSummary(Clock firstResponse, Clock resolution) {

    /**
     * @param remainingBusinessMinutes negative once the target is passed, rather than
     *                                 clamped to zero: "12 minutes over" and "just on time"
     *                                 are different situations and a queue should show
     *                                 which one it is looking at
     * @param atRisk                   from breach prediction, not from a fixed percentage
     */
    public record Clock(String state, Long remainingBusinessMinutes, boolean atRisk) {
    }
}
