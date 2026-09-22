package com.resolveai.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.sla.SlaTestSupport;
import com.resolveai.ticketing.TicketTestSupport;
import com.resolveai.ticketing.domain.Category;
import com.resolveai.triage.TriageWorker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Doc 12 8C: confirm, reject, link, detach, resolve — over HTTP, against a real database. */
class IncidentLifecycleTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired IncidentTestSupport fixtures;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport slaSupport;
    @Autowired TriageWorker triageWorker;
    @Autowired WorkerRuntime runtime;
    @Autowired AiPolicyService policies;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("lifecycle");
        slaSupport.seedPolicies(tenant.tenantId(), "PRO");
        slaSupport.makeCalendarAlwaysOpen(tenant.tenantId());
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL "
                    + "WHERE tenant_id = ?", tenant.tenantId());
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());

        adminToken = auth.accessToken(rest, "lifecycle", "admin");
        agentToken = auth.accessToken(rest, "lifecycle", "agent");
        customerToken = auth.accessToken(rest, "lifecycle", "customer");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
    }

    /** Creates and triages {@code n} tickets, each with a live resolution clock. */
    private List<Long> triagedTickets(int n) {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            LlmStub.returnsSignals(Category.PAYMENT);
            ResponseEntity<Map> created = tickets.create(rest, customerToken,
                    "Payment issue #" + i, "Something went wrong with my payment #" + i);
            Long id = ((Number) created.getBody().get("id")).longValue();
            assertThat(runtime.runOnce(triageWorker)).isEqualTo(1);
            ids.add(id);
        }
        return ids;
    }

    @Test
    @DisplayName("confirm pauses resolution clocks, leaves first-response running, writes a positive eval case")
    void confirmPausesClocksAndLabelsPositive() {
        List<Long> ticketIds = triagedTickets(3);
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-CONFIRM-1", 3,
                OffsetDateTime.now());
        ticketIds.forEach(id -> fixtures.linkTicket(incidentId, id, 0.9));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), authedWithIfMatch(adminToken, incidentId)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> effects = (Map<String, Object>) response.getBody().get("effects");
        assertThat(effects.get("ticketsLinked")).isEqualTo(3);
        assertThat(effects.get("resolutionClocksPaused")).isEqualTo(3);
        assertThat(effects.get("firstResponseClocksUnaffected")).isEqualTo(3);
        assertThat(effects.get("evalLabelRecorded")).isEqualTo(true);

        for (Long id : ticketIds) {
            assertThat(slaState(id, "RESOLUTION")).isEqualTo("PAUSED");
            assertThat(slaState(id, "FIRST_RESPONSE")).isEqualTo("RUNNING");
        }

        Map<String, Object> evalCase = jdbc.queryForMap(
                "SELECT expected FROM eval_case WHERE suite = 'INCIDENT' AND name = 'INC-CONFIRM-1'");
        assertThat(evalCase.get("expected").toString()).contains("CONFIRMED");
    }

    @Test
    @DisplayName("confirming twice is a 409, not a second pause")
    void confirmTwiceIsConflict() {
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-CONFIRM-2", 0,
                OffsetDateTime.now());
        jdbc.update("UPDATE incident SET status = 'CONFIRMED' WHERE id = ?", incidentId);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), authedWithIfMatch(adminToken, incidentId)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_INCIDENT_STATE");
    }

    @Test
    @DisplayName("reject detaches every live link and writes a negative eval case")
    void rejectDetachesLinksAndLabelsNegative() {
        List<Long> ticketIds = triagedTickets(2);
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-REJECT-1", 2,
                OffsetDateTime.now());
        ticketIds.forEach(id -> fixtures.linkTicket(incidentId, id, 0.8));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/reject", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "These tickets are unrelated to each other"),
                        authedWithIfMatch(adminToken, incidentId)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer liveLinks = jdbc.queryForObject(
                "SELECT count(*) FROM incident_ticket WHERE incident_id = ? AND detached_at IS NULL",
                Integer.class, incidentId);
        assertThat(liveLinks).isZero();

        Map<String, Object> evalCase = jdbc.queryForMap(
                "SELECT expected FROM eval_case WHERE suite = 'INCIDENT' AND name = 'INC-REJECT-1'");
        assertThat(evalCase.get("expected").toString()).contains("REJECTED");

        // Resolution clocks were never touched by a reject.
        for (Long id : ticketIds) {
            assertThat(slaState(id, "RESOLUTION")).isEqualTo("RUNNING");
        }
    }

    @Test
    @DisplayName("a reason under 10 characters is a 400")
    void shortRejectReasonIsRejected() {
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-REJECT-2", 0,
                OffsetDateTime.now());

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/reject", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "no"), authedWithIfMatch(adminToken, incidentId)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("manual link succeeds once, then 409s; detach restores the clock and re-linking succeeds")
    void manualLinkAndDetach() {
        List<Long> ticketIds = triagedTickets(1);
        Long ticketId = ticketIds.get(0);
        Long incidentA = fixtures.seedIncident(tenant.tenantId(), "INC-LINK-A", 0,
                OffsetDateTime.now());
        Long incidentB = fixtures.seedIncident(tenant.tenantId(), "INC-LINK-B", 0,
                OffsetDateTime.now());

        ResponseEntity<Map> linkResponse = rest.exchange(
                "/api/v1/incidents/" + incidentA + "/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("ticketId", ticketId), authed(adminToken)), Map.class);
        assertThat(linkResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> conflict = rest.exchange(
                "/api/v1/incidents/" + incidentB + "/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("ticketId", ticketId), authed(adminToken)), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("errorCode")).isEqualTo("TICKET_ALREADY_LINKED");

        // Manually confirm A so the resolution clock is actually paused, to prove detach
        // resumes it rather than merely leaving it alone.
        rest.exchange("/api/v1/incidents/" + incidentA + "/confirm", HttpMethod.POST,
                new HttpEntity<>(Map.of(), authedWithIfMatch(adminToken, incidentA)), Map.class);
        assertThat(slaState(ticketId, "RESOLUTION")).isEqualTo("PAUSED");

        ResponseEntity<Void> detachResponse = rest.exchange(
                "/api/v1/incidents/" + incidentA + "/tickets/" + ticketId, HttpMethod.DELETE,
                new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Void.class);
        assertThat(detachResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(slaState(ticketId, "RESOLUTION")).isEqualTo("RUNNING");

        ResponseEntity<Map> relink = rest.exchange(
                "/api/v1/incidents/" + incidentB + "/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("ticketId", ticketId), authed(adminToken)), Map.class);
        assertThat(relink.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("resolve closes the incident and every still-open linked ticket, skipping an already-closed one")
    void resolveClosesIncidentAndLinkedTickets() {
        List<Long> ticketIds = triagedTickets(2);
        Long incidentId = fixtures.seedIncident(tenant.tenantId(), "INC-RESOLVE-1", 2,
                OffsetDateTime.now());
        ticketIds.forEach(id -> fixtures.linkTicket(incidentId, id, 0.9));
        jdbc.update("UPDATE incident SET status = 'CONFIRMED' WHERE id = ?", incidentId);

        // Auto-triage assigns but does not itself move a ticket off OPEN - only an
        // explicit /assign or /status call does. ASSIGNED is what a genuinely worked
        // ticket looks like, and it is RESOLVED-eligible per TicketStateMachine.
        jdbc.update("UPDATE ticket SET status = 'ASSIGNED' WHERE id = ?", ticketIds.get(0));
        // Move the second ticket straight to CLOSED so resolve has something to skip.
        jdbc.update("UPDATE ticket SET status = 'CLOSED' WHERE id = ?", ticketIds.get(1));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/incidents/" + incidentId + "/resolve", HttpMethod.POST,
                new HttpEntity<>(Map.of("resolutionNote", "Root cause fixed and deployed"),
                        authedWithIfMatch(adminToken, incidentId)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("RESOLVED");
        assertThat(response.getBody().get("resolvedTicketCount")).isEqualTo(1);
        assertThat((List<Object>) response.getBody().get("skipped")).containsExactly(
                ticketIds.get(1).intValue());

        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class,
                ticketIds.get(0))).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT status FROM incident WHERE id = ?", String.class,
                incidentId)).isEqualTo("RESOLVED");
    }

    private String incidentEtag(Long incidentId) {
        ResponseEntity<Map> response = rest.exchange("/api/v1/incidents/" + incidentId,
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Map.class);
        return response.getHeaders().getETag();
    }

    private HttpHeaders authedWithIfMatch(String token, Long incidentId) {
        HttpHeaders headers = authed(token);
        headers.set(HttpHeaders.IF_MATCH, incidentEtag(incidentId));
        return headers;
    }

    private String slaState(Long ticketId, String kind) {
        return jdbc.queryForObject("""
                SELECT state FROM sla_record WHERE ticket_id = ? AND kind = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, ticketId, kind);
    }

    private static HttpHeaders authed(String token) {
        HttpHeaders headers = AuthTestSupport.bearer(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
