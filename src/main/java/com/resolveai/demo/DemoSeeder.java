package com.resolveai.demo;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.knowledge.service.KnowledgeCorpusLoader;
import com.resolveai.platform.seed.TenantBootstrapper;
import com.resolveai.platform.seed.TenantBootstrapper.CalendarSpec;
import com.resolveai.platform.seed.TenantBootstrapper.TenantSpec;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Makes sure the demo tenant exists and has something in it - in any profile, because the
 * live demo runs under {@code prod}.
 *
 * <p>This is what closes the "production has no data" gap: every other seeder is
 * {@code @Profile("local")}, so a fresh production database had no tenant, no user and no
 * ticket, and every visitor failed at the login screen. It is switched on only by
 * {@code resolveai.demo.enabled}, and it never touches any tenant but the demo one.
 *
 * <p>Order 30: after the local IAM (10) and knowledge (20) seeders, so locally - where the
 * demo tenant is the seeded {@code acme} - the tenant and its corpus already exist and only
 * the tickets are added.
 *
 * <h2>The demo tenant's calendar is 24/7, in UTC</h2>
 *
 * <p>A reviewer can open the demo at any hour from any timezone. On office hours, every
 * clock would sit frozen outside 09:00-18:00 IST and the demo would look broken half the
 * day. Real tenants keep real calendars; this is a property of the demo tenant only.
 */
@Component
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
@Order(30)
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    static final CalendarSpec ALWAYS_OPEN =
            new CalendarSpec("UTC", new int[]{1, 2, 3, 4, 5, 6, 7}, "00:00", "23:59");

    private final DemoSettings settings;
    private final DemoWorkspace workspace;
    private final TenantBootstrapper bootstrapper;
    private final KnowledgeCorpusLoader corpus;
    private final DemoTicketSeeder ticketSeeder;
    private final DemoStormService storm;
    private final PasswordEncoder passwordEncoder;

    public DemoSeeder(DemoSettings settings, DemoWorkspace workspace,
                      TenantBootstrapper bootstrapper, KnowledgeCorpusLoader corpus,
                      DemoTicketSeeder ticketSeeder, DemoStormService storm,
                      PasswordEncoder passwordEncoder) {
        this.settings = settings;
        this.workspace = workspace;
        this.bootstrapper = bootstrapper;
        this.corpus = corpus;
        this.ticketSeeder = ticketSeeder;
        this.storm = storm;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Idempotent: creates what is missing and leaves what exists alone. */
    public void seed() {
        Optional<Tenant> existing = workspace.tenant();
        Tenant tenant;
        if (existing.isPresent()) {
            tenant = existing.get();
        } else if (!settings.seedTenant()) {
            log.warn("Demo mode is on but tenant '{}' does not exist and seed-tenant is off — "
                    + "the demo buttons will not work until it does", settings.tenantSlug());
            return;
        } else if (settings.password() == null || settings.password().length() < 10) {
            log.error("Demo mode is on but resolveai.demo.password (DEMO_PASSWORD) is unset or "
                    + "shorter than 10 characters — refusing to create a public tenant without one");
            return;
        } else {
            tenant = bootstrapper.create(
                    new TenantSpec("Ledgerly Demo", settings.tenantSlug(), PlanTier.ENTERPRISE,
                            "ledgerly.demo"),
                    passwordEncoder.encode(settings.password()), ALWAYS_OPEN);
            log.info("Demo tenant '{}' created", tenant.getSlug());
            corpus.loadInto(tenant.getId(), tenant.getSlug());
        }

        if (ticketSeeder.isEmpty(tenant)) {
            log.info("Demo tenant '{}' has no tickets — seeding the showcase queue",
                    tenant.getSlug());
            Tenant seeded = tenant;
            ticketSeeder.seedInBackground(tenant, false, () -> {
                // One storm on a fresh tenant, so the incident board shows a real
                // correlation from the first visit instead of an empty state.
                if (settings.stormOnSeed()) {
                    storm.start(seeded);
                }
            });
        } else if (ticketSeeder.isShowcaseMissing(tenant)) {
            log.info("Demo tenant '{}' has tickets but no showcase — adding the showcase tickets",
                    tenant.getSlug());
            ticketSeeder.seedInBackground(tenant, true, null);
        }
    }
}
