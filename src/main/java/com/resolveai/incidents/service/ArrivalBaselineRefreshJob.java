package com.resolveai.incidents.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly {@code REFRESH MATERIALIZED VIEW CONCURRENTLY ticket_arrival_baseline}.
 *
 * <p>{@code CONCURRENTLY} needs the unique index {@code uq_arrival_baseline} — created
 * alongside the view in V13 — and is what lets the refresh run without locking out the
 * correlation sweep's reads while it works.
 */
@Component
public class ArrivalBaselineRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(ArrivalBaselineRefreshJob.class);

    private final JdbcTemplate jdbc;

    public ArrivalBaselineRefreshJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 03:00 server time — low traffic, and well clear of any tenant's business hours everywhere it matters for a demo. */
    @Scheduled(cron = "${resolveai.incidents.baseline-refresh-cron:0 0 3 * * *}")
    public void refresh() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            log.error("Arrival baseline refresh failed; tomorrow's baseline stays stale "
                     + "until the next attempt", e);
        }
    }

    /** Callable directly — by a seeding script or a test — without waiting for the cron. */
    public void refreshNow() {
        jdbc.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY ticket_arrival_baseline");
        log.info("Refreshed ticket_arrival_baseline");
    }
}
