package com.resolveai.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Round-trips every IAM entity and checks the four things {@code ddl-auto: validate} does
 * <b>not</b> catch.
 *
 * <p>Validation compares column names, types and nullability. It has nothing to say about
 * whether an enum is stored as a string or an integer, whether a soft delete actually
 * deletes, or whether an array column round-trips as an array - and each of those is a
 * mapping mistake that works perfectly until the day it does not.
 */
class IamMappingTest extends IntegrationTestBase {

    @Autowired TenantRepository tenants;
    @Autowired TeamRepository teams;
    @Autowired AppUserRepository users;
    @Autowired AgentProfileRepository agentProfiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTestSupport auth;
    @Autowired TenantScope tenantScope;

    private Long tenantId;

    @BeforeEach
    void seedTenant() {
        // auth.wipe(), not a hand-rolled DELETE list: an earlier version missed
        // business_calendar and so passed only when this class happened to run
        // before anything that seeds one. One cleanup, one place to keep correct.
        auth.wipe();

                Tenant tenant = tenants.saveAndFlush(new Tenant("Mapping Co", "mapping", PlanTier.PRO));
        tenantId = tenant.getId();
    }

    @Test
    @DisplayName("Role persists as the string 'AGENT', never an ordinal")
    void roleIsStoredAsString() {
        Long id = tenantScope.inTenant(tenantId, () ->
                users.saveAndFlush(new AppUser("agent@mapping.test", "x".repeat(60),
                        "Agent Person", Role.AGENT)).getId());

        // Read through raw SQL, not through the mapping that is under test. Asking JPA
        // whether JPA stored the enum correctly answers a different question.
        String raw = jdbc.queryForObject(
                "SELECT role FROM app_user WHERE id = ?", String.class, id);

        assertThat(raw)
                .as("ordinal storage makes reordering the enum silently reinterpret every row")
                .isEqualTo("AGENT");
    }

    @Test
    @DisplayName("Team.skills round-trips as a real text[]")
    void skillsRoundTripAsArray() {
        Long id = tenantScope.inTenant(tenantId, () ->
                teams.saveAndFlush(new Team("Payments",
                        new String[]{"PAYMENT", "BILLING"}, false)).getId());

        String[] reloaded = tenantScope.inTenant(tenantId, () ->
                teams.findById(id).orElseThrow().getSkills());
        assertThat(reloaded).containsExactly("PAYMENT", "BILLING");

        // And it really is an array in the database, not a string that looks like one -
        // which is what makes the GIN index and the containment operator work.
        Integer cardinality = jdbc.queryForObject(
                "SELECT cardinality(skills) FROM team WHERE id = ?", Integer.class, id);
        assertThat(cardinality).isEqualTo(2);

        Integer matching = jdbc.queryForObject(
                "SELECT count(*) FROM team WHERE id = ? AND skills @> ARRAY['PAYMENT']",
                Integer.class, id);
        assertThat(matching).isEqualTo(1);
    }

    @Test
    @DisplayName("soft delete hides the row from JPA but keeps it in the table")
    void softDeleteKeepsTheRow() {
        Long id = tenantScope.inTenant(tenantId, () ->
                users.saveAndFlush(new AppUser("gone@mapping.test", "x".repeat(60),
                        "Gone Person", Role.CUSTOMER)).getId());

        tenantScope.inTenant(tenantId, () -> {
            users.deleteById(id);
            return null;
        });

        assertThat(tenantScope.inTenant(tenantId, () -> users.findById(id))).isEmpty();

        Integer stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE id = ?", Integer.class, id);
        assertThat(stillThere)
                .as("a hard delete would break the foreign keys that the audit trail depends on")
                .isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    @DisplayName("AgentProfile.version increments on update")
    void versionIncrements() {
        Long profileId = tenantScope.inTenant(tenantId, () -> {
            AppUser agent = users.saveAndFlush(new AppUser("cap@mapping.test", "x".repeat(60),
                    "Cap Person", Role.AGENT));
            AgentProfile profile = new AgentProfile(agent, 12);
            profile.setShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
            return agentProfiles.saveAndFlush(profile).getId();
        });

        int before = tenantScope.inTenant(tenantId, () ->
                agentProfiles.findById(profileId).orElseThrow().getVersion());

        tenantScope.inTenant(tenantId, () -> {
            AgentProfile profile = agentProfiles.findById(profileId).orElseThrow();
            profile.setOpenCount(3);
            return agentProfiles.saveAndFlush(profile);
        });

        int after = tenantScope.inTenant(tenantId, () ->
                agentProfiles.findById(profileId).orElseThrow().getVersion());

        assertThat(after)
                .as("without a version bump, two concurrent assignments both write openCount+1")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("every field survives a round trip through the database")
    void fieldsRoundTrip() {
        Long id = tenantScope.inTenant(tenantId, () -> {
            Team team = teams.saveAndFlush(new Team("Platform", new String[]{"API"}, true));
            AppUser user = new AppUser("full@mapping.test", "x".repeat(60), "Full Person",
                    Role.TEAM_LEAD);
            user.setTeam(team);
            return users.saveAndFlush(user).getId();
        });

        AppUser reloaded = tenantScope.inTenant(tenantId, () -> {
            AppUser u = users.findById(id).orElseThrow();
            u.getTeam().getName();   // force the lazy proxy inside the session
            return u;
        });

        assertThat(reloaded.getEmail()).isEqualTo("full@mapping.test");
        assertThat(reloaded.getFullName()).isEqualTo("Full Person");
        assertThat(reloaded.getRole()).isEqualTo(Role.TEAM_LEAD);
        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }
}
