package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.iam.domain.Team;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.triage.routing.AgentAssigner;
import com.resolveai.triage.routing.RoutingPolicy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Team selection and agent claiming — the deterministic half of triage.
 *
 * <p>Nothing here involves a model. That is the claim being tested: the model proposed a
 * category, and everything that happens to the ticket afterwards is business
 * configuration a tenant can change without anyone touching a prompt.
 */
class RoutingPolicyTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired RoutingPolicy routing;
    @Autowired AgentAssigner assigner;
    @Autowired TransactionTemplate txTemplate;
    @Autowired DatabaseClock clock;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;

    @BeforeEach
    void seed() {
        auth.wipe();
        // The seeded team is "Payments" with skills {PAYMENT, BILLING} and is_default.
        tenant = auth.seedTenant("routing");
    }

    // ── Team selection ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A category matching a team's skills routes to that team")
    void routesBySkill() {
        Long apiTeam = team("API Support", "{API,INTEGRATION}", false);

        assertThat(select("API").map(Team::getId)).contains(apiTeam);
        assertThat(select("PAYMENT").map(Team::getId)).contains(tenant.teamId());
    }

    @Test
    @DisplayName("The most specific team wins when several match")
    void prefersTheSpecialist() {
        Long specialist = team("Refunds", "{PAYMENT}", false);
        team("Everything", "{PAYMENT,BILLING,API,DATA,AUTH}", false);

        // The catch-all also matches. Routing to it would mean the team formed to handle
        // payments stops seeing payment tickets, which nobody would notice for a while.
        assertThat(select("PAYMENT").map(Team::getId)).contains(specialist);
    }

    @Test
    @DisplayName("An unknown category falls back to the default team")
    void fallsBackToDefault() {
        assertThat(select("ONBOARDING").map(Team::getId)).contains(tenant.teamId());
        // Null too: a ticket nobody could classify still has to land somewhere.
        assertThat(select(null).map(Team::getId)).contains(tenant.teamId());
    }

    @Test
    @DisplayName("A tenant with no default team leaves the ticket unrouted rather than failing")
    void unroutedIsNotAnError() {
        jdbc.update("UPDATE team SET is_default = FALSE WHERE tenant_id = ?", tenant.tenantId());

        assertThat(select("ONBOARDING")).isEmpty();
    }

    @Test
    @DisplayName("A soft-deleted team is never routed to")
    void ignoresDeletedTeams() {
        Long deleted = team("Old API", "{API}", false);
        jdbc.update("UPDATE team SET deleted_at = NOW() WHERE id = ?", deleted);

        // Falls through to the default rather than handing work to a team that no
        // longer exists, which is where tickets go to be forgotten.
        assertThat(select("API").map(Team::getId)).contains(tenant.teamId());
    }

    @Test
    @DisplayName("Routing never crosses a tenant boundary")
    void isTenantScoped() {
        AuthTestSupport.SeededTenant other = auth.seedTenant("routing2");
        Long theirTeam = TenantContext.callAs(other.tenantId(),
                () -> team("Their API", "{API}", false));

        // Our tenant has no API team, so it must fall back to our own default - not to
        // theirs, however well it matches.
        Optional<Long> ours = select("API").map(Team::getId);
        assertThat(ours).contains(tenant.teamId());
        assertThat(ours.orElseThrow()).isNotEqualTo(theirTeam);
    }

    // ── Agent claiming ──────────────────────────────────────────────────────

    @Test
    @DisplayName("The least loaded agent is claimed and their capacity booked")
    void claimsTheLeastLoaded() {
        Long busy = tenant.agentId();
        jdbc.update("UPDATE agent_profile SET open_count = 7, shift_start = NULL, "
                    + "shift_end = NULL WHERE user_id = ?", busy);
        Long idle = agent("idle", 0);

        Optional<Long> claimed = claim(tenant.teamId());

        assertThat(claimed).contains(idle);
        assertThat(openCount(idle)).isEqualTo(1);
        assertThat(openCount(busy)).isEqualTo(7);
    }

    @Test
    @DisplayName("An agent at capacity is skipped")
    void respectsMaxConcurrent() {
        jdbc.update("UPDATE agent_profile SET open_count = 12, max_concurrent = 12, "
                    + "shift_start = NULL, shift_end = NULL WHERE user_id = ?",
                tenant.agentId());
        Long spare = agent("spare", 5);

        assertThat(claim(tenant.teamId())).contains(spare);
    }

    @Test
    @DisplayName("An unavailable agent is skipped")
    void respectsAvailability() {
        jdbc.update("UPDATE agent_profile SET is_available = FALSE WHERE user_id = ?",
                tenant.agentId());

        assertThat(claim(tenant.teamId())).isEmpty();
    }

    @Test
    @DisplayName("An agent outside their shift is skipped")
    void respectsTheShift() {
        // A one-minute shift, an hour ago. Nobody is on it now, whenever "now" is.
        jdbc.update("""
                UPDATE agent_profile SET shift_start = '03:00', shift_end = '03:01'
                 WHERE user_id = ?
                """, tenant.agentId());

        // The seeded window is narrow enough that this is only meaningful if the local
        // time is outside it, which it is for all but one minute of the day. Rather
        // than leave that to chance, the assertion is on the two possible answers being
        // consistent with the clock.
        boolean inWindow = isLocalTimeWithin("03:00", "03:01");
        if (inWindow) {
            assertThat(claim(tenant.teamId())).isPresent();
        } else {
            assertThat(claim(tenant.teamId())).isEmpty();
        }
    }

    @Test
    @DisplayName("Nobody available means no assignment, not a failure")
    void emptyTeamIsNotAnError() {
        Long emptyTeam = team("Nobody", "{DATA}", false);

        assertThat(claim(emptyTeam)).isEmpty();
    }

    @Test
    @DisplayName("Equal loads are broken deterministically by id")
    void tiebreakIsStable() {
        jdbc.update("UPDATE agent_profile SET open_count = 0, shift_start = NULL, "
                    + "shift_end = NULL WHERE tenant_id = ?", tenant.tenantId());
        agent("second", 0);

        // The first-seeded agent has the lowest id, so it wins every time. Without the
        // id tiebreak the winner would depend on physical row order, and the
        // load-distribution test could not assert anything at all.
        List<Long> winners = List.of(claimOnly(), claimOnly(), claimOnly());
        assertThat(winners).containsOnly(tenant.agentId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Optional<Team> select(String category) {
        return TenantContext.callAs(tenant.tenantId(),
                () -> routing.selectTeam(tenant.tenantId(), category));
    }

    private Optional<Long> claim(Long teamId) {
        OffsetDateTime now = clock.now();
        return TenantContext.callAs(tenant.tenantId(),
                () -> txTemplate.execute(status ->
                        assigner.claim(tenant.tenantId(), teamId, now)));
    }

    /** Claims and rolls the booking back, so repeated calls see the same starting state. */
    private Long claimOnly() {
        OffsetDateTime now = clock.now();
        return TenantContext.callAs(tenant.tenantId(),
                () -> txTemplate.execute(status -> {
                    Optional<Long> claimed = assigner.claim(tenant.tenantId(),
                            tenant.teamId(), now);
                    status.setRollbackOnly();
                    return claimed.orElse(null);
                }));
    }

    private Long team(String name, String skills, boolean isDefault) {
        return jdbc.queryForObject("""
                INSERT INTO team (tenant_id, name, skills, is_default)
                VALUES (?, ?, ?::text[], ?) RETURNING id
                """, Long.class, currentTenantId(), name, skills, isDefault);
    }

    private Long currentTenantId() {
        return TenantContext.isSet() ? TenantContext.getRequired() : tenant.tenantId();
    }

    private Long agent(String localPart, int openCount) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role, team_id)
                VALUES (?, ?, 'x', ?, 'AGENT', ?) RETURNING id
                """, Long.class, tenant.tenantId(), localPart + "@routing.test",
                "Agent " + localPart, tenant.teamId());
        jdbc.update("""
                INSERT INTO agent_profile (user_id, tenant_id, max_concurrent, open_count)
                VALUES (?, ?, 15, ?)
                """, userId, tenant.tenantId(), openCount);
        return userId;
    }

    private int openCount(Long userId) {
        return jdbc.queryForObject("SELECT open_count FROM agent_profile WHERE user_id = ?",
                Integer.class, userId);
    }

    private boolean isLocalTimeWithin(String start, String end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT (NOW() AT TIME ZONE (SELECT timezone FROM business_calendar
                                             WHERE tenant_id = ?))::time
                       BETWEEN ?::time AND ?::time
                """, Boolean.class, tenant.tenantId(), start, end));
    }
}
