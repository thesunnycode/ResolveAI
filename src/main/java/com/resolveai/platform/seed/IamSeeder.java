package com.resolveai.platform.seed;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.platform.seed.TenantBootstrapper.CalendarSpec;
import com.resolveai.platform.seed.TenantBootstrapper.TenantSpec;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds three tenants of Ledgerly - the fictional product from Phase 1 - with teams, users
 * across all four roles, and a business calendar each. The per-tenant work is
 * {@link TenantBootstrapper}'s, shared with the demo seeder.
 *
 * <p><b>Guarded three ways</b>: {@code @Profile("local")}, a property switch, and an
 * idempotence check on the {@code acme} slug. Any one of them alone eventually fails - a
 * profile gets activated by accident, a property gets defaulted the wrong way - and this
 * class writes users with a password that is printed to the console.
 *
 * <p><b>The seed password is shared and known.</b> That is what makes the demo work and it
 * is fine for a machine on someone's desk. It is noted in the README as well, because the
 * pattern is catastrophic the first time somebody copies this file into something real.
 *
 * <p><b>{@code @Order(10)}</b>: the tenant-scoped seeders (knowledge corpus, demo tickets)
 * run after it, because on an empty database they have no tenant to write into until this
 * has run.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "resolveai.seed.enabled", havingValue = "true", matchIfMissing = true)
@Order(10)
public class IamSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IamSeeder.class);

    /** Long enough to pass the registration rules, so seeded users can also change it. */
    private static final String SEED_PASSWORD = "resolveai-local-2026";

    private static final List<TenantSpec> TENANTS = List.of(
            new TenantSpec("Acme Retail", "acme", PlanTier.ENTERPRISE, "acme.com"),
            new TenantSpec("Bluestone Logistics", "bluestone", PlanTier.PRO, "bluestone.in"),
            new TenantSpec("Chai Corner", "chai", PlanTier.FREE, "chaicorner.in"));

    private final TenantRepository tenants;
    private final TenantBootstrapper bootstrapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;

    public IamSeeder(TenantRepository tenants, TenantBootstrapper bootstrapper,
                     PasswordEncoder passwordEncoder, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.bootstrapper = bootstrapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (tenants.existsBySlug("acme")) {
            log.info("IAM seed already present — skipping. Drop the database to reseed.");
            // Databases seeded before SLA policies were part of the seed have tenants whose
            // tickets can never get a clock; backfilling is idempotent and cheap.
            for (TenantSpec spec : TENANTS) {
                tenants.findBySlug(spec.slug()).ifPresent(
                        t -> bootstrapper.ensureSlaPolicies(t.getId(), t.getPlanTier()));
            }
            printLoginTable();
            return;
        }

        // Hashed once, not 87 times - see TenantBootstrapper#create.
        String hash = passwordEncoder.encode(SEED_PASSWORD);

        for (TenantSpec spec : TENANTS) {
            bootstrapper.create(spec, hash, CalendarSpec.officeHours());
        }

        log.info("Seeded {} tenants, {} users", TENANTS.size(), countUsers());
        printLoginTable();
    }

    private long countUsers() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Printed on every start, not just the first.
     *
     * <p>These credentials get typed a few hundred times over the next eight weeks, and
     * hunting for them in a seeder class each time is exactly the kind of small friction
     * that makes people stop using the local environment.
     */
    private void printLoginTable() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("  ┌────────────────────────────────────────────────────────────────────┐\n");
        sb.append("  │  LOCAL SEED LOGINS — password for every account below:             │\n");
        sb.append(String.format("  │  %-64s  │%n", SEED_PASSWORD));
        sb.append("  ├──────────────┬─────────────┬───────────────────────────────────────┤\n");
        sb.append("  │ tenantSlug   │ role        │ email                                 │\n");
        sb.append("  ├──────────────┼─────────────┼───────────────────────────────────────┤\n");
        for (TenantSpec spec : TENANTS) {
            row(sb, spec.slug(), "ADMIN", TenantBootstrapper.adminEmail(spec));
            row(sb, spec.slug(), "TEAM_LEAD",
                    TenantBootstrapper.emailFor(TenantBootstrapper.LEAD_NAMES[0], spec.domain()));
            row(sb, spec.slug(), "AGENT",
                    TenantBootstrapper.emailFor(TenantBootstrapper.AGENT_NAMES[0], spec.domain()));
            row(sb, spec.slug(), "CUSTOMER", TenantBootstrapper.customerEmail(1));
        }
        sb.append("  └──────────────┴─────────────┴───────────────────────────────────────┘\n");
        sb.append("  Or skip typing: the login page has one-click demo buttons (resolveai.demo).");
        log.info(sb.toString());
    }

    private static void row(StringBuilder sb, String slug, String role, String email) {
        sb.append(String.format("  │ %-12s │ %-11s │ %-37s │%n", slug, role, email));
    }
}
