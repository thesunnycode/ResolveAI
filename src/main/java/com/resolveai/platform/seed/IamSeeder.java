package com.resolveai.platform.seed;

import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.platform.tenant.TenantScope;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds three tenants of Ledgerly - the fictional product from Phase 1 - with teams, users
 * across all four roles, and a business calendar each.
 *
 * <p><b>Guarded three ways</b>: {@code @Profile("local")}, a property switch, and an
 * idempotence check on the {@code acme} slug. Any one of them alone eventually fails - a
 * profile gets activated by accident, a property gets defaulted the wrong way - and this
 * class writes users with a password that is printed to the console.
 *
 * <p><b>The seed password is shared and known.</b> That is what makes the demo work and it
 * is fine for a machine on someone's desk. It is noted in the README as well, because the
 * pattern is catastrophic the first time somebody copies this file into something real.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "resolveai.seed.enabled", havingValue = "true", matchIfMissing = true)
public class IamSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IamSeeder.class);

    /** Long enough to pass the registration rules, so seeded users can also change it. */
    private static final String SEED_PASSWORD = "resolveai-local-2026";

    private record TenantSpec(String name, String slug, PlanTier tier, String domain) {
    }

    private static final List<TenantSpec> TENANTS = List.of(
            new TenantSpec("Acme Retail", "acme", PlanTier.ENTERPRISE, "acme.com"),
            new TenantSpec("Bluestone Logistics", "bluestone", PlanTier.PRO, "bluestone.in"),
            new TenantSpec("Chai Corner", "chai", PlanTier.FREE, "chaicorner.in"));

    /** Mirrors seed/domain/taxonomy.yml, including the deliberate PAYMENT/BILLING overlap. */
    private static final List<String[]> TEAMS = List.of(
            new String[]{"Payments", "PAYMENT", "BILLING"},
            new String[]{"Platform", "API", "PERFORMANCE", "INTEGRATION"},
            new String[]{"Accounts", "AUTH", "DATA"},
            new String[]{"General", "OTHER"});

    private static final String[] AGENT_NAMES = {
            "Arjun Mehta", "Neha Kulkarni", "Rahul Iyer", "Fatima Sheikh",
            "Vikram Rao", "Ananya Bose"};
    private static final String[] LEAD_NAMES = {"Sana Qureshi", "Devendra Nair"};

    private final TenantRepository tenants;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final AgentProfileRepository agentProfiles;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final com.resolveai.platform.ai.AiPolicyService aiPolicies;

    public IamSeeder(TenantRepository tenants, TeamRepository teams, AppUserRepository users,
                     AgentProfileRepository agentProfiles, PasswordEncoder passwordEncoder,
                     JdbcTemplate jdbc, TenantScope tenantScope,
                     com.resolveai.platform.ai.AiPolicyService aiPolicies) {
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
        this.agentProfiles = agentProfiles;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.aiPolicies = aiPolicies;
    }

    @Override
    public void run(String... args) {
        if (tenants.existsBySlug("acme")) {
            log.info("IAM seed already present — skipping. Drop the database to reseed.");
            printLoginTable();
            return;
        }

        // Hashed once, not 87 times. BCrypt at strength 12 is ~250ms by design, and every
        // seeded user shares this password anyway; hashing per user would add 20 seconds to
        // every cold start for no benefit whatsoever.
        String hash = passwordEncoder.encode(SEED_PASSWORD);

        for (TenantSpec spec : TENANTS) {
            seedTenant(spec, hash);
        }

        log.info("Seeded {} tenants, {} teams, {} users",
                TENANTS.size(), TENANTS.size() * TEAMS.size(), countUsers());
        printLoginTable();
    }

    private void seedTenant(TenantSpec spec, String passwordHash) {
        Tenant tenant = tenants.saveAndFlush(new Tenant(spec.name(), spec.slug(), spec.tier()));

        // Every tenant gets an AI policy, because a tenant without one has AI disabled
        // (AiPolicyService fails closed) and a locally seeded tenant that cannot be
        // triaged would look like a broken pipeline rather than a missing row.
        aiPolicies.ensureExists(tenant.getId());

        // Everything below writes tenant-scoped rows, and Hibernate takes the tenant from
        // the resolver rather than from any field we set - so this block has to run inside
        // the right context or the inserts land under the NO_TENANT sentinel.
        tenantScope.inTenant(tenant.getId(), () -> {
            List<Team> created = new ArrayList<>();
            for (int i = 0; i < TEAMS.size(); i++) {
                String[] spec2 = TEAMS.get(i);
                String[] skills = java.util.Arrays.copyOfRange(spec2, 1, spec2.length);
                Team team = new Team(spec2[0], skills, i == TEAMS.size() - 1);
                created.add(teams.saveAndFlush(team));
            }

            AppUser admin = new AppUser("admin@" + spec.domain(), passwordHash,
                    "Priya Raman", Role.ADMIN);
            users.save(admin);

            for (int i = 0; i < LEAD_NAMES.length; i++) {
                AppUser lead = new AppUser(emailFor(LEAD_NAMES[i], spec.domain()), passwordHash,
                        LEAD_NAMES[i], Role.TEAM_LEAD);
                lead.setTeam(created.get(i % created.size()));
                users.save(lead);
            }

            for (int i = 0; i < AGENT_NAMES.length; i++) {
                AppUser agent = new AppUser(emailFor(AGENT_NAMES[i], spec.domain()), passwordHash,
                        AGENT_NAMES[i], Role.AGENT);
                agent.setTeam(created.get(i % created.size()));
                AppUser saved = users.saveAndFlush(agent);

                AgentProfile profile = new AgentProfile(saved, 10 + (i % 3) * 5);
                profile.setShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
                agentProfiles.save(profile);
            }

            for (int i = 1; i <= 20; i++) {
                users.save(new AppUser(
                        "customer" + i + "@example.com", passwordHash,
                        "Customer " + i, Role.CUSTOMER));
            }

            // Phase 5 needs a calendar the moment the SLA engine starts. Written with plain
            // SQL because there is no BusinessCalendar entity yet and inventing one here
            // would put a Phase 5 type in a Phase 4 commit.
            jdbc.update("""
                    INSERT INTO business_calendar (tenant_id, timezone, working_days, day_start, day_end)
                    VALUES (?, 'Asia/Kolkata', '{1,2,3,4,5}', '09:00', '18:00')
                    """, tenant.getId());
        });
    }

    private static String emailFor(String fullName, String domain) {
        return fullName.split(" ")[0].toLowerCase(Locale.ROOT) + "@" + domain;
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
            sb.append(String.format("  │ %-12s │ %-11s │ %-37s │%n",
                    spec.slug(), "ADMIN", "admin@" + spec.domain()));
            sb.append(String.format("  │ %-12s │ %-11s │ %-37s │%n",
                    spec.slug(), "TEAM_LEAD", emailFor(LEAD_NAMES[0], spec.domain())));
            sb.append(String.format("  │ %-12s │ %-11s │ %-37s │%n",
                    spec.slug(), "AGENT", emailFor(AGENT_NAMES[0], spec.domain())));
            sb.append(String.format("  │ %-12s │ %-11s │ %-37s │%n",
                    spec.slug(), "CUSTOMER", "customer1@example.com"));
        }
        sb.append("  └──────────────┴─────────────┴───────────────────────────────────────┘");
        log.info(sb.toString());
    }
}
