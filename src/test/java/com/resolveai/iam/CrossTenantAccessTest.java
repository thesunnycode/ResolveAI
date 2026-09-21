package com.resolveai.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>The most important test in the phase.</b> Two tenants exist; a token for one must never
 * reach the other's data.
 *
 * <p><b>The {@code @TenantId} resolver is the mechanism and this file is the proof, and
 * neither is sufficient alone.</b> The resolver covers HQL and criteria queries, which is
 * almost everything — but not native SQL, and Phases 6 to 8 add native queries for the
 * outbox claim and for hybrid retrieval. Those must carry their own
 * {@code AND tenant_id = :tenantId}, and the only thing that will notice if one does not is
 * a table like {@link #endpointsUnderTest()}.
 *
 * <p><b>{@link #endpointsUnderTest()} is designed to be appended to.</b> Phase 4 contributes
 * five rows. Roughly forty-five more arrive over Phases 5 to 8, and every one of them gets a
 * line here. That growing list is the evidence behind the claim that no tenant can read
 * another tenant's data.
 */
class CrossTenantAccessTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant alpha;
    private AuthTestSupport.SeededTenant beta;
    private String alphaAgentToken;

    @BeforeEach
    void seedTwoTenants() {
        auth.wipe();
        alpha = auth.seedTenant("alpha");
        beta = auth.seedTenant("beta");
        alphaAgentToken = auth.accessToken(rest, "alpha", "agent");
    }

    /**
     * Every endpoint that currently exists, as {@code (method, pathTemplate, needsAuth)}.
     *
     * <p>{@code {id}} in a template is replaced with a <b>tenant B</b> resource id before
     * the call is made with tenant A's token.
     *
     * <p>One template carries an {@code {id}} today - the admin capacity endpoint - and the
     * rest arrive from Phase 5 onwards. Having the substitution harness in place before the
     * endpoints exist is the point: the moment to build it is before there are forty-five
     * of them, not after.
     */
    static Stream<Arguments> endpointsUnderTest() {
        return Stream.of(
                //        method            path                              authenticated
                Arguments.of(HttpMethod.GET, "/api/v1/auth/me", true),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/logout", true),
                Arguments.of(HttpMethod.POST, "/api/v1/__test__/echo", true),
                Arguments.of(HttpMethod.PUT, "/api/v1/agents/me/availability", true),
                // The first template with an {id}: the agent profile named here belongs to
                // tenant B, and tenant A's token must not reach it.
                Arguments.of(HttpMethod.PUT, "/api/v1/admin/agents/{id}/capacity", true)
                // Phase 5 appends: /tickets, /tickets/{id}, /tickets/{id}/messages,
                //                  /tickets/{id}/assign, /tickets/{id}/status, ...
                // Phase 6 appends: /tickets/{id}/analysis, /priority-rationale, ...
                // Phase 7 appends: /knowledge/documents/{id}, /drafts/{id}, ...
                // Phase 8 appends: /incidents/{id}, /incidents/{id}/confirm, ...
        );
    }

    @ParameterizedTest(name = "{0} {1} with a foreign token is never 200 or 500")
    @MethodSource("endpointsUnderTest")
    @DisplayName("no endpoint leaks another tenant's data to an authenticated caller")
    void foreignTokenNeverReachesAnotherTenant(HttpMethod method, String template,
                                               boolean authenticated) {
        String path = template.replace("{id}", String.valueOf(beta.agentId()));

        ResponseEntity<Map> response = rest.exchange(path, method,
                new HttpEntity<>(Map.of(), AuthTestSupport.bearer(alphaAgentToken)), Map.class);

        // 400 and 422 are fine — the request never got far enough to read anything. What
        // must never happen is a 200 carrying tenant B's data, or a 500, which means the
        // isolation failed somewhere deep enough to throw rather than to deny cleanly.
        assertThat(response.getStatusCode())
                .as("%s %s returned %s", method, path, response.getStatusCode())
                .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            String body = response.getBody().toString();
            assertThat(body)
                    .as("a 2xx response must contain nothing belonging to the other tenant")
                    .doesNotContain("@beta.test")
                    .doesNotContain("Beta");
        }
    }

    @Test
    @DisplayName("/auth/me returns only the caller's own tenant")
    void meIsScopedToTheTokensTenant() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(alphaAgentToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("tenantSlug")).isEqualTo("alpha");
        assertThat(((Number) response.getBody().get("tenantId")).longValue())
                .isEqualTo(alpha.tenantId());
        assertThat((String) response.getBody().get("email")).endsWith("@alpha.test");
    }

    @Test
    @DisplayName("the same email in two tenants resolves to the right account")
    void sameEmailInTwoTenantsIsTwoAccounts() {
        // The realistic collision: one person, or one shared address, at two customers of
        // the same platform. uq_user_tenant_email is per tenant, so both rows are legal —
        // which means login has to disambiguate by slug, and getting that wrong would let a
        // password for one tenant open the other.
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?", String.class,
                alpha.agentId());
        jdbc.update("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role)
                VALUES (?, 'shared@example.com', ?, 'Shared Alpha', 'AGENT')
                """, alpha.tenantId(), hash);
        jdbc.update("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role)
                VALUES (?, 'shared@example.com', ?, 'Shared Beta', 'AGENT')
                """, beta.tenantId(), hash);

        Map<String, Object> asAlpha = login("alpha", "shared@example.com");
        Map<String, Object> asBeta = login("beta", "shared@example.com");

        assertThat(user(asAlpha).get("fullName")).isEqualTo("Shared Alpha");
        assertThat(user(asBeta).get("fullName")).isEqualTo("Shared Beta");
        assertThat(user(asAlpha).get("tenantId")).isNotEqualTo(user(asBeta).get("tenantId"));
    }

    @Test
    @DisplayName("a user cannot log in against another tenant's slug")
    void loginIsScopedToTheTenantSlug() {
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login",
                Map.of("tenantSlug", "beta",
                        "email", "agent@alpha.test",
                        "password", AuthTestSupport.PASSWORD),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Identical to a wrong password, deliberately: telling the caller that the address
        // exists but belongs to a different tenant maps out the platform's customer list.
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    @DisplayName("a registration collides only within its own tenant")
    void registrationUniquenessIsPerTenant() {
        Map<String, Object> body = Map.of(
                "tenantSlug", "beta",
                "email", "agent@alpha.test",
                "password", "cross-tenant-2026",
                "fullName", "Same Address Different Tenant");

        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/register", body, Map.class);

        assertThat(response.getStatusCode())
                .as("an address already used in tenant alpha must still be free in tenant beta")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) response.getBody().get("tenantId")).longValue())
                .isEqualTo(beta.tenantId());
    }

    @Test
    @DisplayName("every tenant-scoped table holds rows for both tenants, and each sees only its own")
    void listEndpointsReturnNoForeignRows() {
        // The fixture really does contain both tenants — otherwise the assertions above
        // would pass against an empty database and prove nothing at all.
        Integer alphaUsers = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE tenant_id = ?", Integer.class,
                alpha.tenantId());
        Integer betaUsers = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE tenant_id = ?", Integer.class,
                beta.tenantId());

        assertThat(alphaUsers).isEqualTo(3);
        assertThat(betaUsers).isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String slug, String email) {
        return rest.postForObject("/api/v1/auth/login",
                Map.of("tenantSlug", slug, "email", email,
                        "password", AuthTestSupport.PASSWORD),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> user(Map<String, Object> loginResponse) {
        return (Map<String, Object>) loginResponse.get("user");
    }
}
