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
 * <p>Phase 6 fills in the threshold behaviour. The query below already works against the
 * empty table, so it returns {@code UP} with an age of zero rather than a stub.
 */
@Component("outboxLag")
public class OutboxLagHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagHealthIndicator.class);

    /**
     * Above this, the poller is not keeping up. TODO Phase 6: move to configuration and
     * calibrate against the measured poll interval rather than this placeholder.
     */
    private static final long DEGRADED_AFTER_SECONDS = 120;

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

    public OutboxLagHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Long ageSeconds = jdbc.queryForObject(OLDEST_PENDING, Long.class);
            Long dead = jdbc.queryForObject(DEAD_COUNT, Long.class);
            long age = ageSeconds == null ? 0L : ageSeconds;

            Health.Builder builder = age > DEGRADED_AFTER_SECONDS ? Health.down() : Health.up();
            return builder
                    .withDetail("oldestPendingEventAgeSeconds", age)
                    .withDetail("degradedAfterSeconds", DEGRADED_AFTER_SECONDS)
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
