package com.resolveai.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.tenant.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves Hibernate's discriminator does what the design claims.
 *
 * <p><b>Seeding is done with raw SQL on purpose.</b> Using the repositories to create the
 * fixture would route the inserts through the very filter under test, so a broken filter
 * could produce a consistent - and consistently wrong - world that the assertions then
 * agree with. {@code JdbcTemplate} writes both tenants' rows without the filter having any
 * say.
 */
class TenantIsolationTest extends IntegrationTestBase {

    @Autowired AppUserRepository users;
    @Autowired TeamRepository teams;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTestSupport auth;
    @Autowired TenantScope tenantScope;

    private Long tenantA;
    private Long tenantB;
    private Long userInB;

    @BeforeEach
    void seedTwoTenants() {
        // auth.wipe(), not a hand-rolled DELETE list: an earlier version missed
        // business_calendar and so passed only when this class happened to run
        // before anything that seeds one. One cleanup, one place to keep correct.
        auth.wipe();

                tenantA = insertTenant("Alpha", "alpha");
        tenantB = insertTenant("Beta", "beta");

        insertUser(tenantA, "a1@alpha.test", "A One");
        insertUser(tenantA, "a2@alpha.test", "A Two");
        userInB = insertUser(tenantB, "b1@beta.test", "B One");
        insertUser(tenantB, "b2@beta.test", "B Two");

        insertTeam(tenantA, "Alpha Team");
        insertTeam(tenantB, "Beta Team");
    }

    @Test
    @DisplayName("findAll returns only the current tenant's rows, with no WHERE in the repository")
    void listQueriesAreFiltered() {
        var inA = tenantScope.inTenant(tenantA, () -> users.findAll());
        assertThat(inA).hasSize(2);
        assertThat(inA).allSatisfy(u -> assertThat(u.getTenantId()).isEqualTo(tenantA));

        assertThat(tenantScope.inTenant(tenantA, () -> teams.findAll())).hasSize(1);
        assertThat(tenantScope.inTenant(tenantB, () -> users.findAll())).hasSize(2);
    }

    @Test
    @DisplayName("findById on another tenant's id returns empty, not the row")
    void directLookupIsFiltered() {
        var found = tenantScope.inTenant(tenantA, () -> users.findById(userInB));

        assertThat(found)
                .as("the direct-lookup path is the one a hand-rolled filter usually misses: "
                    + "it is a primary-key load, and the WHERE clause has to come from somewhere")
                .isEmpty();

        // The row certainly exists - it is simply not visible from tenant A.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?",
                Integer.class, userInB)).isEqualTo(1);
    }

    @Test
    @DisplayName("an insert lands in the context's tenant, whatever the caller intended")
    void insertsCannotEscapeTheTenant() {
        Long id = tenantScope.inTenant(tenantA, () ->
                users.saveAndFlush(new AppUser("new@alpha.test", "x".repeat(60),
                        "New Person", Role.CUSTOMER)).getId());

        Long stored = jdbc.queryForObject(
                "SELECT tenant_id FROM app_user WHERE id = ?", Long.class, id);

        assertThat(stored)
                .as("AppUser has no setter for tenantId at all — Hibernate owns the column, "
                    + "so writing into the wrong tenant is not expressible")
                .isEqualTo(tenantA);
    }

    @Test
    @DisplayName("no tenant in context means no rows, never all rows")
    void unsetContextReturnsNothing() {
        TenantContext.clear();

        // Documented behaviour, asserted rather than assumed: the resolver substitutes the
        // NO_TENANT sentinel, so queries run with `tenant_id = -1` and match nothing. The
        // failure mode this rules out is the dangerous one - an unset context silently
        // disabling the filter and returning every tenant's rows.
        assertThat(users.findAll()).isEmpty();
        assertThat(teams.findAll()).isEmpty();
        assertThat(users.findById(userInB)).isEmpty();
    }

    @Test
    @DisplayName("getRequired fails loudly rather than defaulting")
    void getRequiredThrowsWhenUnset() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::getRequired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    @Test
    @DisplayName("runAs restores the previous tenant even when the work throws")
    void runAsRestoresOnFailure() {
        TenantContext.set(tenantA);
        try {
            assertThatThrownBy(() -> TenantContext.runAs(tenantB, () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(TenantContext.get())
                    .as("a nested runAs that leaks would leave the caller in the wrong tenant")
                    .isEqualTo(tenantA);
        } finally {
            TenantContext.clear();
        }
    }

    // ── fixture helpers, deliberately raw SQL ───────────────────────────────

    private Long insertTenant(String name, String slug) {
        return jdbc.queryForObject("""
                INSERT INTO tenant (name, slug, plan_tier) VALUES (?, ?, 'PRO') RETURNING id
                """, Long.class, name, slug);
    }

    private Long insertUser(Long tenantId, String email, String fullName) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role)
                VALUES (?, ?, ?, ?, 'CUSTOMER') RETURNING id
                """, Long.class, tenantId, email, "x".repeat(60), fullName);
    }

    private void insertTeam(Long tenantId, String name) {
        jdbc.update("""
                INSERT INTO team (tenant_id, name, skills) VALUES (?, ?, '{OTHER}')
                """, tenantId, name);
    }
}
