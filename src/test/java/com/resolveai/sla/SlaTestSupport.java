package com.resolveai.sla;

import com.resolveai.ticketing.TicketTestSupport;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixtures for the SLA tests: a policy to resolve against, a way to triage a ticket so its
 * clocks start, and the handful of direct reads the assertions need.
 *
 * <h2>Why the clock is moved by rewriting timestamps, not by waiting</h2>
 *
 * <p>Every deadline in this engine is an absolute instant in a column. A test that wanted
 * to observe a rung fire "for real" would have to wait out a business-hours interval —
 * minutes at the very least, and hours for a resolution target. So the tests move the
 * <i>past</i> instead: {@link #rewind} shifts a record's segments and deadline backwards,
 * which is indistinguishable, to every line of production code, from time having passed.
 *
 * <p>This is only sound because nothing in the engine caches elapsed time. There is no
 * {@code elapsed_minutes} column to fall out of step with the segments — elapsed is
 * always derived from {@code sla_clock_segment} — so rewinding the segments rewinds the
 * clock completely. A design with a counter could not be tested this way, which is a
 * decent second argument for not having one.
 */
@Component
public class SlaTestSupport {

    private final JdbcTemplate jdbc;

    public SlaTestSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Seeding ─────────────────────────────────────────────────────────────

    /**
     * A live policy for every priority on the tenant's plan.
     *
     * <p>Targets are deliberately small — a 60-minute first response and a 240-minute
     * resolution — so that a rewind of an hour or two lands on a recognisable rung rather
     * than somewhere in the middle of a percentage nobody can check by eye.
     */
    public void seedPolicies(Long tenantId, String planTier) {
        for (String priority : List.of("P1", "P2", "P3", "P4")) {
            seedPolicy(tenantId, priority, planTier, 60, 240, "v1");
        }
    }

    /**
     * <p><b>{@code effective_from} is backdated a day, not left to default to
     * {@code NOW()}.</b> The resolver asks "which policy was in force at this instant",
     * with the instant read from the JVM, while the default would be read from the
     * database — and the two clocks are a container apart. A policy that became effective
     * a few hundred milliseconds in the JVM's future resolves to nothing, and the test
     * fails as {@code 422 SLA_POLICY_NOT_FOUND} in maybe one run in five. A policy in
     * force since yesterday is also the more realistic fixture.
     */
    public Long seedPolicy(Long tenantId, String priority, String planTier,
                           int firstResponseMinutes, int resolutionMinutes,
                           String versionLabel) {
        return jdbc.queryForObject("""
                INSERT INTO sla_policy (tenant_id, priority, plan_tier, first_response_minutes,
                                        resolution_minutes, version_label, effective_from)
                VALUES (?, ?, ?, ?, ?, ?, NOW() - INTERVAL '1 day') RETURNING id
                """, Long.class, tenantId, priority, planTier, firstResponseMinutes,
                resolutionMinutes, versionLabel);
    }

    /** Everything about a record, for an assertion message that explains itself. */
    public String describe(Long recordId) {
        return String.valueOf(jdbc.queryForMap("""
                SELECT id, kind, state, target_minutes, next_rung, next_deadline_at,
                       NOW() AS db_now
                  FROM sla_record WHERE id = ?
                """, recordId));
    }

    /** A 24×7 calendar, so a test asserting on minutes need not care what day it runs on. */
    public void makeCalendarAlwaysOpen(Long tenantId) {
        jdbc.update("""
                UPDATE business_calendar
                   SET working_days = '{1,2,3,4,5,6,7}', day_start = '00:00', day_end = '23:59'
                 WHERE tenant_id = ?
                """, tenantId);
    }

    // ── Driving the API ─────────────────────────────────────────────────────

    /**
     * Gives a ticket a priority, which is what starts its clocks in Phase 5.
     *
     * <p>Through the endpoint rather than an UPDATE: the clocks start inside
     * {@code overridePriority}'s transaction, so an UPDATE would produce a triaged ticket
     * with no SLA and every subsequent assertion would be testing a fixture that cannot
     * occur in production.
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> triage(TestRestTemplate rest, TicketTestSupport tickets,
                                      String token, Long ticketId, String priority) {
        ResponseEntity<Map> response = tickets.post(rest, token, ticketId, "priority-override",
                Map.of("priority", priority, "reason", "Test fixture"));
        // Loudly, because a triage that quietly fails produces a ticket with no clocks and
        // the test then dies twenty lines later on an empty result set, pointing at the
        // read rather than at the write that did not happen.
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Triage of ticket " + ticketId + " failed: "
                    + response.getStatusCode() + " " + response.getBody());
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    public ResponseEntity<Map> sla(TestRestTemplate rest, String token, Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/sla", HttpMethod.GET,
                new HttpEntity<>(com.resolveai.AuthTestSupport.bearer(token)), Map.class);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public Long recordId(Long ticketId, String kind) {
        return jdbc.queryForObject("""
                SELECT id FROM sla_record
                 WHERE ticket_id = ? AND kind = ? AND state <> 'CANCELLED'
                """, Long.class, ticketId, kind);
    }

    public String state(Long recordId) {
        return jdbc.queryForObject("SELECT state FROM sla_record WHERE id = ?",
                String.class, recordId);
    }

    public Short nextRung(Long recordId) {
        return jdbc.queryForObject("SELECT next_rung FROM sla_record WHERE id = ?",
                Short.class, recordId);
    }

    public int segmentCount(Long recordId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_clock_segment WHERE sla_record_id = ?",
                Integer.class, recordId);
    }

    public int openSegmentCount(Long recordId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sla_clock_segment
                 WHERE sla_record_id = ? AND ended_at IS NULL
                """, Integer.class, recordId);
    }

    public List<Integer> firedRungs(Long recordId) {
        return jdbc.queryForList("""
                SELECT rung FROM sla_escalation WHERE sla_record_id = ? ORDER BY rung
                """, Integer.class, recordId);
    }

    public int notificationCount(Long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM notification
                 WHERE tenant_id = ? AND kind IN ('SLA_ESCALATION', 'SLA_BREACH')
                """, Integer.class, tenantId);
    }

    // ── Time travel ─────────────────────────────────────────────────────────

    /**
     * Moves a record {@code minutes} into the past: every segment boundary and the
     * deadline shift back by the same interval.
     *
     * <p>Shifting the segments <b>and</b> the deadline together is what keeps the fixture
     * coherent. Moving only the deadline would make the record due while its elapsed time
     * said it was nowhere near its rung — and {@code EscalationService} re-checks exactly
     * that, so the rung would be pushed forward again and the test would assert on the
     * safety net rather than on the ladder.
     */
    public void rewind(Long recordId, long minutes) {
        String interval = minutes + " minutes";
        // trg_segment_close_once refuses any UPDATE to a segment that is already closed —
        // the append-only rule enforced in the database rather than trusted to the
        // application. It is doing its job here; the fixture is the one anomaly the rule
        // was not written for, so it is disabled for exactly this statement and turned
        // straight back on. Anything in production hitting this error has a real bug.
        jdbc.execute("ALTER TABLE sla_clock_segment DISABLE TRIGGER trg_segment_close_once");
        try {
            jdbc.update("""
                    UPDATE sla_clock_segment
                       SET started_at = started_at - ?::interval,
                           ended_at   = ended_at   - ?::interval
                     WHERE sla_record_id = ?
                    """, interval, interval, recordId);
        } finally {
            jdbc.execute("ALTER TABLE sla_clock_segment ENABLE TRIGGER trg_segment_close_once");
        }
        jdbc.update("""
                UPDATE sla_record
                   SET next_deadline_at = next_deadline_at - ?::interval
                 WHERE id = ?
                """, interval, recordId);
    }

    /** Puts the deadline in the past without touching elapsed — a clock that is simply due. */
    public void makeDue(Long recordId, long minutesAgo) {
        jdbc.update("""
                UPDATE sla_record SET next_deadline_at = NOW() - ?::interval WHERE id = ?
                """, minutesAgo + " minutes", recordId);
    }
}
