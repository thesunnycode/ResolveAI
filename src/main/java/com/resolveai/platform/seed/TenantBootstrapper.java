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
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.tenant.TenantScope;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates one complete tenant: teams, users across all four roles, a business calendar and
 * an AI policy.
 *
 * <p>Shared by {@link IamSeeder} (three tenants, locally) and the demo seeder (one tenant,
 * anywhere the demo is switched on), so the two cannot drift into producing tenants that
 * differ in some way only one of them was taught about. It has no profile guard of its own
 * on purpose: it only ever runs when one of its callers - each of which is guarded - asks.
 */
@Component
public class TenantBootstrapper {

    public record TenantSpec(String name, String slug, PlanTier tier, String domain) {
    }

    /** ISO-8601 working days, Monday = 1. */
    public record CalendarSpec(String timezone, int[] workingDays, String dayStart,
                               String dayEnd) {

        /** The local seed's calendar: an Indian working week. */
        public static CalendarSpec officeHours() {
            return new CalendarSpec("Asia/Kolkata", new int[]{1, 2, 3, 4, 5}, "09:00", "18:00");
        }
    }

    /** Mirrors seed/domain/taxonomy.yml, including the deliberate PAYMENT/BILLING overlap. */
    private static final List<String[]> TEAMS = List.of(
            new String[]{"Payments", "PAYMENT", "BILLING"},
            new String[]{"Platform", "API", "PERFORMANCE", "INTEGRATION"},
            new String[]{"Accounts", "AUTH", "DATA"},
            new String[]{"General", "OTHER"});

    static final String[] AGENT_NAMES = {
            "Arjun Mehta", "Neha Kulkarni", "Rahul Iyer", "Fatima Sheikh",
            "Vikram Rao", "Ananya Bose"};
    static final String[] LEAD_NAMES = {"Sana Qureshi", "Devendra Nair"};
    static final int CUSTOMERS = 20;

    private final TenantRepository tenants;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final AgentProfileRepository agentProfiles;
    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final AiPolicyService aiPolicies;

    public TenantBootstrapper(TenantRepository tenants, TeamRepository teams,
                              AppUserRepository users, AgentProfileRepository agentProfiles,
                              JdbcTemplate jdbc, TenantScope tenantScope,
                              AiPolicyService aiPolicies) {
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
        this.agentProfiles = agentProfiles;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.aiPolicies = aiPolicies;
    }

    /**
     * @param passwordHash one BCrypt hash shared by every user. BCrypt at strength 12 is
     *                     ~250ms by design; hashing per user would add seconds per tenant
     *                     for accounts that all share the password anyway.
     */
    public Tenant create(TenantSpec spec, String passwordHash, CalendarSpec calendar) {
        Tenant tenant = tenants.saveAndFlush(new Tenant(spec.name(), spec.slug(), spec.tier()));

        // Every tenant gets an AI policy, because a tenant without one has AI disabled
        // (AiPolicyService fails closed) and a seeded tenant that cannot be triaged would
        // look like a broken pipeline rather than a missing row.
        aiPolicies.ensureExists(tenant.getId());

        // Everything below writes tenant-scoped rows, and Hibernate takes the tenant from
        // the resolver rather than from any field we set - so this block has to run inside
        // the right context or the inserts land under the NO_TENANT sentinel.
        tenantScope.inTenant(tenant.getId(), () -> {
            List<Team> created = new ArrayList<>();
            for (int i = 0; i < TEAMS.size(); i++) {
                String[] teamSpec = TEAMS.get(i);
                String[] skills = Arrays.copyOfRange(teamSpec, 1, teamSpec.length);
                created.add(teams.saveAndFlush(new Team(teamSpec[0], skills, i == TEAMS.size() - 1)));
            }

            users.save(new AppUser(adminEmail(spec), passwordHash, "Priya Raman", Role.ADMIN));

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

            for (int i = 1; i <= CUSTOMERS; i++) {
                users.save(new AppUser(customerEmail(i), passwordHash, "Customer " + i,
                        Role.CUSTOMER));
            }

            ensureCalendar(tenant.getId(), calendar);
            ensureSlaPolicies(tenant.getId(), spec.tier());
        });
        return tenant;
    }

    /**
     * A business calendar row for the tenant, if it has none yet.
     *
     * <p>Plain SQL because there is no {@code BusinessCalendar} entity - the seed never
     * needed one, and the only other caller ({@link com.resolveai.iam.service.BusinessRegistrationService})
     * doesn't either.
     */
    public void ensureCalendar(Long tenantId, CalendarSpec calendar) {
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM business_calendar WHERE tenant_id = ?", Long.class, tenantId);
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO business_calendar (tenant_id, timezone, working_days, day_start, day_end)
                VALUES (?, ?, ?::smallint[], ?::time, ?::time)
                """, tenantId, calendar.timezone(), toPgArray(calendar.workingDays()),
                calendar.dayStart(), calendar.dayEnd());
    }

    /** ENTERPRISE targets in business minutes: {priority, first response, resolution}. */
    private static final Object[][] ENTERPRISE_TARGETS = {
            {"P1", 30, 240}, {"P2", 60, 480}, {"P3", 120, 1440}, {"P4", 240, 2880}};

    /**
     * One live policy per priority for the tenant's own plan, if it has none yet.
     *
     * <p>Without these a seeded tenant has <b>no SLA at all</b>: {@code SlaPolicyResolver}
     * deliberately has no fallback (a made-up default would be a deadline nobody agreed
     * to), so triage decides a priority and then no clock can start. Lower tiers get
     * proportionally longer targets - PRO twice ENTERPRISE, FREE four times.
     *
     * <p>{@code effective_from} is a day in the past for the reason
     * {@code SlaTestSupport#seedPolicy} documents: a policy that starts "now" by the
     * database clock can be a few milliseconds in the future to the first ticket's lookup.
     */
    public void ensureSlaPolicies(Long tenantId, PlanTier tier) {
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM sla_policy WHERE tenant_id = ?", Long.class, tenantId);
        if (existing != null && existing > 0) {
            return;
        }
        int factor = switch (tier) {
            case ENTERPRISE -> 1;
            case PRO -> 2;
            case FREE -> 4;
        };
        for (Object[] target : ENTERPRISE_TARGETS) {
            jdbc.update("""
                    INSERT INTO sla_policy (tenant_id, priority, plan_tier, first_response_minutes,
                                            resolution_minutes, version_label, effective_from)
                    VALUES (?, ?, ?, ?, ?, 'v1', NOW() - INTERVAL '1 day')
                    """, tenantId, target[0], tier.name(), (int) target[1] * factor,
                    (int) target[2] * factor);
        }
    }

    static String adminEmail(TenantSpec spec) {
        return "admin@" + spec.domain();
    }

    static String customerEmail(int n) {
        return "customer" + n + "@example.com";
    }

    static String emailFor(String fullName, String domain) {
        return fullName.split(" ")[0].toLowerCase(Locale.ROOT) + "@" + domain;
    }

    private static String toPgArray(int[] values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append('}').toString();
    }
}
