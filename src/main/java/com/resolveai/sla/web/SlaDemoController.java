package com.resolveai.sla.web;

import com.resolveai.common.security.IsAdmin;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Time travel, for demos. <b>{@code local} profile only.</b>
 *
 * <h2>Why this exists</h2>
 *
 * <p>The escalation ladder is the most interesting thing in the SLA engine and the least
 * demoable: showing a rung fire honestly means waiting out half of a business-hours
 * target. This endpoint moves a ticket's clocks into the past so the next poll — ten
 * seconds away — does what an hour of waiting would have done.
 *
 * <h2>Why it rewrites the segments and not just the deadline</h2>
 *
 * <p>Because the deadline is a prediction and the segments are the truth.
 * {@code EscalationService} re-checks elapsed against the rung under the row lock before
 * firing, precisely so that a pause landing between claim and process cannot fire a rung
 * the clock has not reached. Moving only the deadline would trip that guard and
 * reschedule, and the demo would show nothing.
 *
 * <h2>Why it is safe</h2>
 *
 * <p>{@code @Profile("local")} — the bean does not exist under {@code prod} or
 * {@code test}, the same guard the seed loader uses. It is also {@code @IsAdmin}, and it
 * logs loudly, because an endpoint that rewrites audit history should never be quiet
 * about it even on a laptop.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("local")
public class SlaDemoController {

    private static final Logger log = LoggerFactory.getLogger(SlaDemoController.class);

    private final JdbcTemplate jdbc;

    public SlaDemoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ages both of a ticket's clocks by {@code minutes}.
     *
     * @param minutes how far into the past to move them. 40 puts a P2 first-response
     *                clock (60-minute target) past its 50% rung; 500 puts a resolution
     *                clock past all four.
     */
    @PostMapping("/tickets/{id}/sla/fast-forward")
    @IsAdmin
    @Transactional
    public Map<String, Object> fastForward(@PathVariable Long id,
                                           @RequestParam(defaultValue = "60") long minutes) {
        log.warn("DEMO: fast-forwarding ticket {} SLA clocks by {} minutes", id, minutes);
        String interval = minutes + " minutes";

        // trg_segment_close_once refuses to touch a closed segment - the append-only rule,
        // enforced by the database rather than trusted to the application. Rewriting
        // history is exactly what it exists to prevent, so the demo turns it off for the
        // one statement and straight back on. Nothing else in the system does this.
        jdbc.execute("ALTER TABLE sla_clock_segment DISABLE TRIGGER trg_segment_close_once");
        int segments;
        try {
            segments = jdbc.update("""
                    UPDATE sla_clock_segment
                       SET started_at = started_at - ?::interval,
                           ended_at   = ended_at   - ?::interval
                     WHERE sla_record_id IN (SELECT id FROM sla_record WHERE ticket_id = ?)
                    """, interval, interval, id);
        } finally {
            jdbc.execute("ALTER TABLE sla_clock_segment ENABLE TRIGGER trg_segment_close_once");
        }

        int records = jdbc.update("""
                UPDATE sla_record
                   SET next_deadline_at = next_deadline_at - ?::interval
                 WHERE ticket_id = ? AND next_deadline_at IS NOT NULL
                """, interval, id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticketId", id);
        response.put("movedBackMinutes", minutes);
        response.put("segmentsShifted", segments);
        response.put("deadlinesShifted", records);
        response.put("note", "The next poll (<=10s) will fire whatever rungs this crossed.");
        return response;
    }
}
