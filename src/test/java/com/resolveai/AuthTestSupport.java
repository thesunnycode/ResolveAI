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

    /**
     * Removes every row a test could have written, in dependency order.
     *
     * <p><b>One shared wipe, not a per-test cleanup.</b> Hand-rolled teardown in each class
     * is how a suite comes to pass only in a favourable order: Phase 4 had exactly that,
     * missing {@code business_calendar}, and the failure looked like a flaky test rather
     * than a missing DELETE. Everything a test can create is deleted here, including the
     * tables a test never touches directly - {@code sla_clock_segment} arrives by cascade,
     * but naming it makes the list checkable against the schema.
     */
    public void wipe() {
        // The append-only tables cannot be deleted from while their guards are active -
        // trg_ticket_event_immutable and trg_escalation_immutable raise on DELETE, and a
        // cascade from the parent fires them too. Turning them off for the duration of the
        // wipe is the honest way to do this: an audit table you can empty by accident is
        // not append-only, and a test fixture is exactly the place where the exception
        // should have to be written down rather than assumed.
        withAppendOnlyGuardsDisabled(this::deleteEverything);
    }

    /**
     * Runs {@code work} with the append-only guards suspended.
     *
     * <p>Exposed because deleting a ticket is not possible without it: {@code ticket_event}
     * cascades from {@code ticket}, and a cascaded DELETE fires the row trigger just as a
     * direct one does. That is the guard working - an audit trail you can drop by deleting
     * its parent is not append-only - and any test that needs to remove a ticket has to say
     * so out loud, here.
     */
    public void withoutAppendOnlyGuards(Runnable work) {
        withAppendOnlyGuardsDisabled(work);
    }

    private void withAppendOnlyGuardsDisabled(Runnable work) {
        jdbc.execute("ALTER TABLE ticket_event DISABLE TRIGGER trg_ticket_event_immutable");
        jdbc.execute("ALTER TABLE sla_escalation DISABLE TRIGGER trg_escalation_immutable");
        jdbc.execute("ALTER TABLE sla_clock_segment DISABLE TRIGGER trg_segment_close_once");
        try {
            work.run();
        } finally {
            jdbc.execute("ALTER TABLE ticket_event ENABLE TRIGGER trg_ticket_event_immutable");
            jdbc.execute("ALTER TABLE sla_escalation ENABLE TRIGGER trg_escalation_immutable");
            jdbc.execute("ALTER TABLE sla_clock_segment ENABLE TRIGGER trg_segment_close_once");
        }
    }

    private void deleteEverything() {
        jdbc.update("DELETE FROM sla_escalation");
        jdbc.update("DELETE FROM sla_clock_segment");
        jdbc.update("DELETE FROM sla_record");
        jdbc.update("DELETE FROM sla_policy");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM incident_ticket");
        jdbc.update("DELETE FROM incident");
        jdbc.update("DELETE FROM attachment");
        jdbc.update("DELETE FROM ticket_event");
        jdbc.update("DELETE FROM ticket_entity");
        jdbc.update("DELETE FROM ticket_message");
        jdbc.update("DELETE FROM draft");
        // Before ticket, so any RESOLVED_TICKET knowledge_document's source_ticket_id
        // (ON DELETE SET NULL) never has to worry about ordering, and before tenant so
        // fk_kb_tenant (ON DELETE RESTRICT) does not block it below. Cascades away its
        // own chunks, which is also what lets it run after draft: draft's cascade has
        // already removed any citations pointing at those chunks.
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM ticket");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM tenant_sequence");
        jdbc.update("DELETE FROM refresh_token");
        jdbc.update("DELETE FROM agent_profile");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM business_holiday");
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
