package com.resolveai.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives the public demo a clean queue on a schedule.
 *
 * <h2>Rotation, not deletion</h2>
 *
 * <p>Deleting a tenant here would mean unpicking ~39 tables, most of them
 * {@code ON DELETE RESTRICT} and several append-only by trigger - the audit trail and the
 * SLA segments are designed to be impossible to rewrite. So the reset <b>retires</b> the
 * tenant instead: the current demo tenant is renamed {@code <slug>-archived-<id>} and
 * deactivated, which frees the slug, and {@link DemoSeeder} builds a fresh one under it.
 * Login and the demo buttons resolve active tenants by slug, so visitors land in the new
 * one immediately; sessions in the old one end when their 15-minute access token does.
 *
 * <p>The cost is that retired tenants' rows stay in the database. At ~75 tickets a day
 * that is a few megabytes a year - an honest trade for never writing a delete against the
 * append-only tables.
 *
 * <p>Off by default ({@code reset-cron} = {@code -}), and only possible when this
 * application owns the demo tenant's lifecycle ({@code seed-tenant=true}): locally the demo
 * tenant is {@code acme}, which must never be rotated away.
 */
@Component
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoResetJob {

    private static final Logger log = LoggerFactory.getLogger(DemoResetJob.class);

    private final DemoSettings settings;
    private final DemoSeeder seeder;
    private final JdbcTemplate jdbc;

    public DemoResetJob(DemoSettings settings, DemoSeeder seeder, JdbcTemplate jdbc) {
        this.settings = settings;
        this.seeder = seeder;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${resolveai.demo.reset-cron:-}", zone = "UTC")
    public void reset() {
        if (!settings.seedTenant()) {
            log.warn("Demo reset skipped: seed-tenant is off, so tenant '{}' is not the demo's "
                    + "to rotate", settings.tenantSlug());
            return;
        }
        int retired = retire();
        log.info("Demo reset: retired {} tenant(s) named '{}', seeding a fresh one", retired,
                settings.tenantSlug());
        seeder.seed();
    }

    /** One statement, so it is atomic on its own; no transaction to manage. */
    int retire() {
        return jdbc.update("""
                UPDATE tenant
                   SET slug = slug || '-archived-' || id, is_active = FALSE, updated_at = NOW()
                 WHERE slug = ?
                """, settings.tenantSlug());
    }
}
