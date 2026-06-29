package com.resolveai.platform.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reports how far behind the transactional outbox is.
 *
 * <p><b>This is the health check that matters most in this system, and it is wired now —
 * in Phase 3, before anything writes to the outbox — on purpose.</b> `db: UP` and
 * `redis: UP` only say the sockets are open. The failure this design can actually suffer is
 * the poller dying or wedging while HTTP keeps happily accepting work: every request returns
 * 202, every ticket lands, and nothing is ever triaged. From the outside that looks perfectly
 * healthy right up until someone notices the queue is cold.
 *
 * <p>Phase 6 filled in the threshold and added the matching Micrometer gauges in
 * {@code WorkerRuntime}. The query works against an empty table, so a system with nothing
 * queued reports {@code UP} with an age of zero rather than an error.
 */
@Component("outboxLag")
public class OutboxLagHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagHealthIndicator.class);

    /**
     * Above this, the pipeline is not keeping up.
     *
     * <p>Five minutes, calibrated against what the queue actually does rather than
     * against a round number: workers poll every second and a triage takes a handful of
     * seconds, so a healthy oldest-pending age is measured in seconds. Five minutes is
     * far enough above that to survive a burst or a restart, and far enough below a
     * customer noticing that the alert still arrives first.
     *
     * <p><b>Age, not depth.</b> A depth of five hundred draining steadily is a healthy
     * system under load; a depth of three where the oldest is twenty minutes old is
     * something wedged in a retry loop. Only one of those is worth waking somebody for,
     * and depth cannot tell them apart.
     */
    private final long degradedAfterSeconds;

    /**
     * Written against the real schema rather than from memory of the design: outbox_event
     * has a {@code status} column with PENDING / IN_FLIGHT / DONE / DEAD, not the
     * {@code attempts >= max_attempts} pair an earlier draft of this class assumed.
     * IN_FLIGHT counts as lag — an event locked by a worker that then died is exactly the
     * case this indicator exists to surface.
     */
    private static final String OLDEST_PENDING = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0)::bigint
              FROM outbox_event
             WHERE status IN ('PENDING', 'IN_FLIGHT')
            """;

    /** Served by idx_outbox_dlq, the partial index on status = 'DEAD'. */
    private static final String DEAD_COUNT =
            "SELECT count(*) FROM outbox_event WHERE status = 'DEAD'";

    private final JdbcTemplate jdbc;

    public OutboxLagHealthIndicator(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value(
                    "${resolveai.workers.lag-degraded-after-seconds:300}")
            long degradedAfterSeconds) {
        this.jdbc = jdbc;
        this.degradedAfterSeconds = degradedAfterSeconds;
    }

    @Override
    public Health health() {
        try {
            Long ageSeconds = jdbc.queryForObject(OLDEST_PENDING, Long.class);
            Long dead = jdbc.queryForObject(DEAD_COUNT, Long.class);
            long age = ageSeconds == null ? 0L : ageSeconds;

            Health.Builder builder = age > degradedAfterSeconds ? Health.down() : Health.up();
            return builder
                    .withDetail("oldestPendingEventAgeSeconds", age)
                    .withDetail("degradedAfterSeconds", degradedAfterSeconds)
                    .withDetail("deadLetterCount", dead == null ? 0L : dead)
                    .build();
        } catch (Exception e) {
            // A health indicator that throws takes the whole endpoint to 500, which loses the
            // db and redis results too — so the one component that failed reports DOWN and
            // the rest of the report survives.
            log.warn("Outbox lag check failed", e);
            return Health.down(e).build();
        }
    }
}
