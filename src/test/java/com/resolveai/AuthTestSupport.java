package com.resolveai;

import com.resolveai.iam.domain.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Seeds tenants and users, and logs them in through the real HTTP endpoint.
 *
 * <p><b>Fixtures are written with raw SQL; tokens are obtained over HTTP.</b> That split is
 * deliberate. Seeding through the repositories would route fixture writes through the
 * tenant filter under test, so a broken filter could build a world the assertions then
 * agree with. Minting tokens directly from {@code JwtService} would skip the login path -
 * and login is where the tenant claim is put into the token, which is the thing every
 * cross-tenant assertion depends on.
 */
@Component
public class AuthTestSupport {

    /** Satisfies the registration rules, so seeded users can also exercise password change. */
    public static final String PASSWORD = "test-password-2026";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    public record SeededTenant(Long tenantId, String slug, Long adminId, Long agentId,
                               Long customerId, Long teamId) {
    }

    /** Removes every IAM row. Called by tests that need a known starting point. */
    public void wipe() {
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM agent_profile");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM business_calendar");
        jdbc.update("DELETE FROM tenant");
    }

    /** One tenant with a team, an admin, an agent (with profile) and a customer. */
    public SeededTenant seedTenant(String slug) {
        Long tenantId = jdbc.queryForObject("""
                INSERT INTO tenant (name, slug, plan_tier) VALUES (?, ?, 'PRO') RETURNING id
                """, Long.class, slug + " Ltd", slug);

        Long teamId = jdbc.queryForObject("""
                INSERT INTO team (tenant_id, name, skills, is_default)
                VALUES (?, 'Payments', '{PAYMENT,BILLING}', TRUE) RETURNING id
                """, Long.class, tenantId);

        // Hashed once and reused: BCrypt at strength 12 is ~250ms by design, and a test
        // class that seeds two tenants would otherwise spend a second and a half hashing
        // the same string six times.
        String hash = passwordEncoder.encode(PASSWORD);

        Long adminId = insertUser(tenantId, "admin@" + slug + ".test", "Admin " + slug,
                Role.ADMIN, teamId, hash);
        Long agentId = insertUser(tenantId, "agent@" + slug + ".test", "Agent " + slug,
                Role.AGENT, teamId, hash);
        Long customerId = insertUser(tenantId, "customer@" + slug + ".test", "Customer " + slug,
                Role.CUSTOMER, null, hash);

        jdbc.update("""
                INSERT INTO agent_profile (user_id, tenant_id, max_concurrent, shift_start, shift_end)
                VALUES (?, ?, 12, '09:00', '18:00')
                """, agentId, tenantId);

        jdbc.update("""
                INSERT INTO business_calendar (tenant_id) VALUES (?)
                """, tenantId);

        return new SeededTenant(tenantId, slug, adminId, agentId, customerId, teamId);
    }

    private Long insertUser(Long tenantId, String email, String name, Role role, Long teamId,
                            String hash) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role, team_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, tenantId, email, hash, name, role.name(), teamId);
    }

    /** Logs in over HTTP and returns the access token. */
    @SuppressWarnings("unchecked")
    public String accessToken(org.springframework.boot.resttestclient.TestRestTemplate rest,
                              String slug, String emailLocalPart) {
        Map<String, Object> body = rest.postForObject("/api/v1/auth/login",
                Map.of("tenantSlug", slug,
                        "email", emailLocalPart + "@" + slug + ".test",
                        "password", PASSWORD),
                Map.class);
        if (body == null || body.get("accessToken") == null) {
            throw new IllegalStateException("Login failed for " + emailLocalPart + "@" + slug
                    + ".test — response was " + body);
        }
        return (String) body.get("accessToken");
    }

    /** Logs in and returns both tokens, for the rotation tests. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> login(org.springframework.boot.resttestclient.TestRestTemplate rest,
                                     String slug, String emailLocalPart) {
        return rest.postForObject("/api/v1/auth/login",
                Map.of("tenantSlug", slug,
                        "email", emailLocalPart + "@" + slug + ".test",
                        "password", PASSWORD),
                Map.class);
    }

    public static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
