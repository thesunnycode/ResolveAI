package com.resolveai.sla.web.dto;

import java.util.List;

/**
 * Both clocks on a ticket, with their full segment histories.
 *
 * <p><b>The segment arrays are exposed deliberately.</b> They are the design made visible:
 * there is no {@code elapsed_minutes} column anywhere, and the elapsed figure on each clock
 * is a sum over these rows. Publishing them means a wrong clock can be diagnosed from a
 * single API call rather than from a database session, and it means the append-only
 * property is something a reviewer can check rather than something the README asserts.
 *
 * @param calendar the tenant's working hours, included because every minute figure in this
 *                 response is a <i>business</i> minute and is meaningless without it
 */
public record SlaResponse(Long ticketId, CalendarView calendar, List<ClockView> clocks) {

    public record CalendarView(String timezone, List<Integer> workingDays,
                               String dayStart, String dayEnd) {
    }

    /**
     * @param remainingBusinessMinutes {@code target - elapsed}. Goes negative after a
     *                                 breach rather than clamping: how far past is the
     *                                 interesting number once it is past.
     * @param nextDeadlineAt           {@code null} while paused or terminal - which is
     *                                 exactly why the poller, which reads
     *                                 {@code WHERE state = 'RUNNING'}, skips those records.
     * @param prediction               {@code null} until there is enough history to
     *                                 predict from. An absent prediction is honest; a
     *                                 confident one from four samples is not.
     */
    public record ClockView(
            String kind,
            String state,
            String policyVersion,
            int targetBusinessMinutes,
            long elapsedBusinessMinutes,
            long remainingBusinessMinutes,
            String metAt,
            String breachedAt,
            String nextDeadlineAt,
            Short nextRung,
            List<SegmentView> segments,
            List<EscalationView> escalationsFired,
            PredictionView prediction) {
    }

    /**
     * @param businessMinutes the contribution of this segment to the elapsed total. Zero
     *                        for a {@code PAUSED} segment by definition, and zero for a
     *                        {@code RUNNING} segment that happens to span only a weekend.
     */
    public record SegmentView(String state, String startedAt, String endedAt,
                              long businessMinutes, String pauseReason) {
    }

    public record EscalationView(short rung, String firedAt, int elapsedMinutesAtFire) {
    }

    /**
     * @param basis names the statistic and the sample size, e.g.
     *              {@code "p75 over 90 days for (PAYMENT, P2, Payments team), n=214"}.
     *              <b>This is not an LLM output.</b> It is a percentile over historical
     *              resolution times - faster, cheaper, more accurate and explainable, which
     *              is why the model was considered here and rejected.
     */
    public record PredictionView(long predictedResolutionBusinessMinutes, String basis,
                                 boolean atRisk) {
    }
}
