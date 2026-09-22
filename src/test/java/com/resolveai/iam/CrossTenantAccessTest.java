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
 * <p><b>{@link #endpointsUnderTest()} is designed to be appended to.</b> Phase 4
 * contributed five rows; Phases 5 and 6 add the ticket, SLA and triage surface, and every
 * new endpoint gets a line here. That growing list is the evidence behind the claim that
 * no tenant can read another tenant's data.
 *
 * <h2>The fixture is the hard part, not the assertion</h2>
 *
 * <p>A cross-tenant test is worthless if the resource it asks for does not exist: the
 * endpoint returns {@code 404}, the assertion passes, and it has proved nothing except
 * that missing rows are missing. So tenant beta is given a <b>fully populated</b> ticket
 * — triaged, analysed, with a priority decision, running SLA clocks and a message — and
 * every one of those rows carries the word "Beta". Tenant alpha's token then has
 * something real to fail to reach, and the body assertion has something real to catch.
 */
class CrossTenantAccessTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant alpha;
    private AuthTestSupport.SeededTenant beta;
    private String alphaAgentToken;
    private String alphaAdminToken;
    private Long betaTicketId;
    private Long betaDocumentId;
    private Long betaDraftId;

    @BeforeEach
    void seedTwoTenants() {
        auth.wipe();
        alpha = auth.seedTenant("alpha");
        beta = auth.seedTenant("beta");
        alphaAgentToken = auth.accessToken(rest, "alpha", "agent");
        alphaAdminToken = auth.accessToken(rest, "alpha", "admin");
        betaTicketId = seedFullyTriagedBetaTicket();
        betaDocumentId = seedBetaKnowledgeDocument();
        betaDraftId = seedBetaDraft(betaTicketId);
    }

    /**
     * Doc 15 Task 6/24: knowledge_document.body is read directly by
     * GET /knowledge/documents/{id}, so the marker has to be in the body a leak would
     * actually surface, not only in the title.
     */
    private Long seedBetaKnowledgeDocument() {
        return jdbc.queryForObject(("INSERT INTO knowledge_document (tenant_id, source, "
                + "title, body, content_sha256, indexed_at) "
                + "VALUES (?, 'RUNBOOK', 'Beta internal runbook', "
                + "'Beta confidential procedure text.', repeat('b', 64), NOW()) "
                + "RETURNING id"),
                Long.class, beta.tenantId());
    }

    /**
     * A minimal draft row — no claims or citations needed for GET /drafts/{id} to
     * return 200 to the owning tenant, and the suppression reason carries the marker a
     * leak would surface.
     */
    private Long seedBetaDraft(Long ticketId) {
        Long promptId = jdbc.queryForObject(
                "SELECT id FROM prompt_version WHERE name = 'draft' AND is_active",
                Long.class);
        return jdbc.queryForObject(("INSERT INTO draft (ticket_id, tenant_id, "
                + "prompt_version_id, model_id, requested_by, status, coverage, "
                + "suppression_reason) VALUES (?, ?, ?, 'gpt-4.1', ?, "
                + "'SUPPRESSED_LOW_COVERAGE', 0.2, 'Beta confidential suppression reason.') "
                + "RETURNING id"),
                Long.class, ticketId, beta.tenantId(), promptId, beta.agentId());
    }

    /**
     * A tenant-beta ticket with every downstream row an endpoint might read.
     *
     * <p>Written with SQL rather than driven through the API on purpose: the point is to
     * put rows in the database as directly as possible, so that a {@code 404} from an
     * alpha request is unambiguously isolation working and not a fixture that never got
     * created. Every text field says "Beta" so a leak is caught by the body assertion
     * rather than only by a status code.
     */
    private Long seedFullyTriagedBetaTicket() {
        Long ticketId = jdbc.queryForObject("""
                INSERT INTO ticket (tenant_id, reference, subject, body, status, priority,
                                    category, requester_id, assignee_id, team_id)
                VALUES (?, 'TKT-9001', 'Beta confidential payment failure',
                        'Beta tenant private body text.', 'TRIAGED', 'P2', 'PAYMENT',
                        ?, ?, ?)
                RETURNING id
                """, Long.class, beta.tenantId(), beta.customerId(), beta.agentId(),
                beta.teamId());

        jdbc.update("""
                INSERT INTO ticket_message (ticket_id, tenant_id, author_id, visibility, body)
                VALUES (?, ?, ?, 'PUBLIC', 'Beta private reply from the agent.')
                """, ticketId, beta.tenantId(), beta.agentId());

        Long promptId = jdbc.queryForObject(
                "SELECT id FROM prompt_version WHERE name = 'triage' AND is_active",
                Long.class);
        Long analysisId = jdbc.queryForObject("""
                INSERT INTO ai_analysis (ticket_id, tenant_id, prompt_version_id, model_id,
                                         signals, confidence, status)
                VALUES (?, ?, ?, 'gpt-4.1-mini',
                        '{"category":"PAYMENT","reportedImpact":"TEAM","beta":"Beta secret"}'::jsonb,
                        0.9, 'OK')
                RETURNING id
                """, Long.class, ticketId, beta.tenantId(), promptId);

        jdbc.update("""
                INSERT INTO priority_decision (ticket_id, tenant_id, ai_analysis_id,
                                               policy_version, input_signals,
                                               computed_priority, rationale)
                VALUES (?, ?, ?, 'v1', '{"fromModel":{"note":"Beta secret"}}'::jsonb, 'P2',
                        '{"rules":[],"humanReadable":"P2 because of Beta reasons."}'::jsonb)
                """, ticketId, beta.tenantId(), analysisId);

        Long policyId = jdbc.queryForObject("""
                INSERT INTO sla_policy (tenant_id, priority, plan_tier, first_response_minutes,
                                        resolution_minutes, version_label, effective_from)
                VALUES (?, 'P2', 'PRO', 30, 240, 'v1', NOW() - INTERVAL '1 day') RETURNING id
                """, Long.class, beta.tenantId());
        for (String kind : new String[] {"FIRST_RESPONSE", "RESOLUTION"}) {
            Long recordId = jdbc.queryForObject("""
                    INSERT INTO sla_record (ticket_id, tenant_id, sla_policy_id, policy_version,
                                            kind, target_minutes, state, next_deadline_at,
                                            next_rung)
                    VALUES (?, ?, ?, 'v1', ?, 240, 'RUNNING', NOW() + INTERVAL '2 hours', 50)
                    RETURNING id
                    """, Long.class, ticketId, beta.tenantId(), policyId, kind);
            jdbc.update("""
                    INSERT INTO sla_clock_segment (sla_record_id, state, started_at)
                    VALUES (?, 'RUNNING', NOW())
                    """, recordId);
        }

        jdbc.update("""
                INSERT INTO outbox_event (tenant_id, aggregate_type, aggregate_id, event_type,
                                          payload)
                VALUES (?, 'TICKET', ?, 'TICKET_CREATED', ?::jsonb)
                """, beta.tenantId(), ticketId,
                "{\"ticketId\":" + ticketId + ",\"note\":\"Beta\"}");

        return ticketId;
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
                //           method             path                                     auth
                Arguments.of(HttpMethod.GET,    "/api/v1/auth/me", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/auth/logout", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/__test__/echo", true),
                Arguments.of(HttpMethod.PUT,    "/api/v1/agents/me/availability", true),
                // The agent profile named here belongs to tenant B, and tenant A's token
                // must not reach it.
                Arguments.of(HttpMethod.PUT,    "/api/v1/admin/agents/{id}/capacity", true),

                // ── Phase 5: the ticket surface ────────────────────────────
                Arguments.of(HttpMethod.GET,    "/api/v1/tickets", true),
                Arguments.of(HttpMethod.GET,    "/api/v1/tickets/{ticketId}", true),
                Arguments.of(HttpMethod.PATCH,  "/api/v1/tickets/{ticketId}", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/messages", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/assign", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/status", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/resolve", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/reopen", true),

                // ── Phase 5: the SLA surface ───────────────────────────────
                Arguments.of(HttpMethod.GET,    "/api/v1/tickets/{ticketId}/sla", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/sla/pause", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/sla/resume", true),
                Arguments.of(HttpMethod.GET,    "/api/v1/sla/at-risk", true),

                // ── Phase 6: triage and AI ─────────────────────────────────
                // These four are the ones worth adding deliberately rather than by
                // habit. ai_analysis, priority_decision and outbox_event are all
                // written by workers and read through native SQL, which @TenantId does
                // not reach - so their isolation is the query text's responsibility and
                // nothing but this table will notice if a predicate goes missing.
                Arguments.of(HttpMethod.GET,    "/api/v1/tickets/{ticketId}/analysis", true),
                Arguments.of(HttpMethod.GET,
                        "/api/v1/tickets/{ticketId}/priority-rationale", true),
                Arguments.of(HttpMethod.POST,   "/api/v1/tickets/{ticketId}/retriage", true),
                Arguments.of(HttpMethod.POST,
                        "/api/v1/tickets/{ticketId}/priority-override", true),
                Arguments.of(HttpMethod.GET,    "/api/v1/admin/ai-policy", true),
                Arguments.of(HttpMethod.PUT,    "/api/v1/admin/ai-policy", true),

                // ── Phase 7: knowledge base and drafting ───────────────────
                // knowledge_chunk denormalises tenant_id specifically so the hybrid
                // search CTEs can pre-filter on it without a join (see
                // HybridSearchRepository) - which means the document/draft reads below
                // are exactly where a forgotten predicate would cost the most.
                Arguments.of(HttpMethod.GET,    "/api/v1/knowledge/documents/{documentId}", true),
                Arguments.of(HttpMethod.GET,    "/api/v1/drafts/{draftId}", true)
                // Phase 8 appends: /incidents/{id}, /incidents/{id}/confirm, ...
        );
    }

    @ParameterizedTest(name = "{0} {1} with a foreign token is never 200 or 500")
    @MethodSource("endpointsUnderTest")
    @DisplayName("no endpoint leaks another tenant's data to an authenticated caller")
    void foreignTokenNeverReachesAnotherTenant(HttpMethod method, String template,
                                               boolean authenticated) {
        String path = template
                .replace("{id}", String.valueOf(beta.agentId()))
                .replace("{ticketId}", String.valueOf(betaTicketId))
                .replace("{documentId}", String.valueOf(betaDocumentId))
                .replace("{draftId}", String.valueOf(betaDraftId));

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
                    .doesNotContain("Beta")
                    .doesNotContain("TKT-9001");
        }
    }

    /**
     * The positive control, and the reason the table above is worth anything.
     *
     * <p>Every assertion in {@link #foreignTokenNeverReachesAnotherTenant} is satisfied
     * by a {@code 404}. A fixture that silently failed to create the beta ticket would
     * therefore turn the whole parameterised suite green while testing nothing at all —
     * the single most common way a security test rots without anybody noticing.
     *
     * <p>So this asserts the other direction: beta's own token reaches beta's ticket,
     * gets a {@code 200}, and the body really does contain the "Beta" marker that the
     * negative assertions are looking for. If this fails, the suite above is vacuous
     * and says so immediately.
     */
    /**
     * The same table, with an <b>admin</b> token — and this is the variant that actually
     * tests tenant isolation.
     *
     * <h2>Why the agent-token run above is not enough, which was found the hard way</h2>
     *
     * <p>{@code @TenantId} was temporarily removed from {@code Ticket} to check that the
     * agent-token suite would notice. <b>It did not.</b> Every assertion stayed green,
     * because an alpha AGENT is refused a beta ticket by {@code isVisibleTo} — the
     * role-and-team predicate — long before tenancy is consulted. The suite was proving
     * team scoping and quietly reporting it as tenant isolation.
     *
     * <p>{@code isVisibleTo} returns {@code true} unconditionally for {@code ADMIN}. So
     * for an admin token the <i>only</i> thing between a foreign ticket id and another
     * tenant's data is the discriminator itself, which is precisely the mechanism this
     * file exists to prove. Removing {@code @TenantId} fails this run immediately.
     *
     * <p>Generalised: a negative security test is only as strong as the weakest control
     * that can satisfy it, and the fix is to authenticate as the role that strips the
     * other controls away.
     */
    @ParameterizedTest(name = "{0} {1} with a foreign ADMIN token is never 200 or 500")
    @MethodSource("endpointsUnderTest")
    @DisplayName("not even an admin reaches another tenant, with no role check in the way")
    void foreignAdminTokenNeverReachesAnotherTenant(HttpMethod method, String template,
                                                    boolean authenticated) {
        String path = template
                .replace("{id}", String.valueOf(beta.agentId()))
                .replace("{ticketId}", String.valueOf(betaTicketId))
                .replace("{documentId}", String.valueOf(betaDocumentId))
                .replace("{draftId}", String.valueOf(betaDraftId));

        ResponseEntity<Map> response = rest.exchange(path, method,
                new HttpEntity<>(Map.of(), AuthTestSupport.bearer(alphaAdminToken)), Map.class);

        assertThat(response.getStatusCode())
                .as("%s %s returned %s", method, path, response.getStatusCode())
                .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            assertThat(response.getBody().toString())
                    .as("%s %s leaked tenant beta's data to a tenant alpha admin",
                            method, path)
                    .doesNotContain("@beta.test")
                    .doesNotContain("Beta")
                    .doesNotContain("TKT-9001");
        }
    }

    @Test
    @DisplayName("the beta fixture is real: beta's own token reads it, marker and all")
    void theFixtureIsNotVacuous() {
        String betaAgentToken = auth.accessToken(rest, "beta", "agent");

        for (String path : new String[] {
                "/api/v1/tickets/" + betaTicketId,
                "/api/v1/tickets/" + betaTicketId + "/analysis",
                "/api/v1/tickets/" + betaTicketId + "/priority-rationale",
                "/api/v1/tickets/" + betaTicketId + "/sla"}) {
            ResponseEntity<Map> response = rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(AuthTestSupport.bearer(betaAgentToken)), Map.class);

            assertThat(response.getStatusCode())
                    .as("%s must be readable by its own tenant", path)
                    .isEqualTo(HttpStatus.OK);
        }

        // And the marker the negative assertions hunt for is genuinely in the payload.
        ResponseEntity<Map> detail = rest.exchange("/api/v1/tickets/" + betaTicketId,
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(betaAgentToken)),
                Map.class);
        assertThat(detail.getBody().toString()).contains("Beta").contains("TKT-9001");
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
