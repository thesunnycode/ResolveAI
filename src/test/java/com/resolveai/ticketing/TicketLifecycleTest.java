package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Create, assign, reply, change status, resolve, reopen — the whole path, over HTTP.
 *
 * <p>This is the 5A checkpoint expressed as a test. If it is green, the ticket lifecycle
 * works end to end with no SLA engine behind it, which is the demoable stopping point the
 * plan asks for before Phase 5B starts.
 */
class TicketLifecycleTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("life");
        agentToken = auth.accessToken(rest, "life", "agent");
        customerToken = auth.accessToken(rest, "life", "customer");
        adminToken = auth.accessToken(rest, "life", "admin");
    }

    @Test
    @DisplayName("a customer creates a ticket and it comes back with a TKT- reference")
    void createReturnsAReference() {
        ResponseEntity<Map> response = tickets.create(rest, customerToken,
                "Payment deducted but order still pending",
                "I paid via UPI at 14:03 and the money left my account.");

        // 202, not 201: the ticket exists but priority, category, assignee and team are
        // all still empty and will fill in without the client doing anything. 201 would
        // claim this representation is final.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("analysisStatus")).isEqualTo("PROCESSING");
        assertThat((Map<String, Object>) response.getBody().get("links"))
                .containsKeys("self", "analysis");
        assertThat((String) response.getBody().get("reference")).startsWith("TKT-");
        assertThat(response.getBody().get("status")).isEqualTo("OPEN");
        assertThat(response.getBody().get("priority")).isEqualTo("UNTRIAGED");
        // Routed to the default team on creation, so it is visible to somebody immediately.
        // Without it an unrouted ticket is invisible to every agent and every team lead.
        assertThat(response.getBody().get("team")).isNotNull();

        // The CREATED event is written in the same transaction as the ticket.
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM ticket_event WHERE event_type = 'CREATED'", Integer.class);
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("an over-length body is a 400 with a populated errors[]")
    void overLengthBodyIsRejected() {
        ResponseEntity<Map> response = tickets.create(rest, customerToken,
                "Too much", "x".repeat(20_001));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody()
                .get("errors");
        assertThat(errors).extracting(e -> e.get("field")).contains("body");
        // The 20,000-character cap is a token-cost control, not a style rule: from Phase 6
        // every ticket body is sent to a model.
        assertThat((String) errors.get(0).get("rejectedValue"))
                .as("an oversized value is reported by length, never echoed back")
                .contains("chars");
    }

    @Test
    @DisplayName("a customer cannot file a ticket on somebody else's behalf")
    void customerCannotUseOnBehalfOf() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("subject", "Impersonation attempt",
                        "body", "Filed as somebody else",
                        "onBehalfOf", tenant.adminId()),
                        TicketTestSupport.authed(customerToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("errorCode")).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("an agent can file on a customer's behalf, and the customer is the requester")
    void agentCanUseOnBehalfOf() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(Map.of("subject", "Reported by phone",
                        "body", "Customer called about a failed refund",
                        "onBehalfOf", tenant.customerId()),
                        TicketTestSupport.authed(agentToken)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> requester = (Map<String, Object>) response.getBody().get("requester");
        assertThat(((Number) requester.get("id")).longValue()).isEqualTo(tenant.customerId());
    }

    @Test
    @DisplayName("the full lifecycle: create, assign, reply, wait, resume, resolve, reopen")
    void fullLifecycle() {
        Long id = tickets.createId(rest, customerToken, "Cannot log in",
                "Password reset email never arrives.");

        // ── assign ──────────────────────────────────────────────────────────
        ResponseEntity<Map> assigned = tickets.post(rest, agentToken, id, "assign", Map.of());
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().get("status")).isEqualTo("ASSIGNED");
        assertThat(((Number) ((Map<String, Object>) assigned.getBody().get("assignee")).get("id"))
                .longValue()).isEqualTo(tenant.agentId());
        assertThat(openCount(tenant.agentId())).isEqualTo(1);

        // ── the agent's first public reply ──────────────────────────────────
        ResponseEntity<Map> reply = tickets.addMessage(rest, agentToken, id,
                "Thanks - I can see the reset email bounced. Sending a new one now.", "PUBLIC");
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reply.getBody().get("isFirstResponse")).isEqualTo(true);

        // ── in progress, then waiting on the customer ───────────────────────
        assertThat(status(id, "IN_PROGRESS", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> waiting = status(id, "WAITING_ON_CUSTOMER",
                "Asked the customer to confirm the address");
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(waiting.getBody().get("status")).isEqualTo("WAITING_ON_CUSTOMER");

        // ── back to work ────────────────────────────────────────────────────
        assertThat(status(id, "IN_PROGRESS", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // ── resolve ─────────────────────────────────────────────────────────
        ResponseEntity<Map> resolved = tickets.post(rest, agentToken, id, "resolve",
                Map.of("resolution", "Corrected the address on file; reset email delivered."));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("status")).isEqualTo("RESOLVED");
        // The work has left the agent's desk, so their queue shrinks now rather than when
        // somebody closes the ticket days later.
        assertThat(openCount(tenant.agentId())).isZero();

        // ── the customer says it is not fixed ───────────────────────────────
        ResponseEntity<Map> reopened = tickets.post(rest, customerToken, id, "reopen",
                Map.of("reason", "Still not receiving the email."));
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reopened.getBody().get("status")).isEqualTo("OPEN");

        Integer reopenCount = jdbc.queryForObject(
                "SELECT reopen_count FROM ticket WHERE id = ?", Integer.class, id);
        assertThat(reopenCount).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM ticket WHERE id = ?",
                Object.class, id)).isNull();

        // ── the timeline recorded every step ────────────────────────────────
        List<String> timeline = jdbc.queryForList(
                "SELECT event_type FROM ticket_event WHERE ticket_id = ? ORDER BY id",
                String.class, id);
        assertThat(timeline).containsExactly(
                "CREATED", "ASSIGNED", "MESSAGE_ADDED", "STATUS_CHANGED", "STATUS_CHANGED",
                "STATUS_CHANGED", "RESOLVED", "REOPENED");
    }

    @Test
    @DisplayName("an illegal transition is a 409 carrying the transitions that are legal")
    void illegalTransitionReturnsAllowedSet() {
        Long id = tickets.createId(rest, customerToken, "Closing straight away", "Body");
        tickets.post(rest, agentToken, id, "status", Map.of("status", "CLOSED"));

        ResponseEntity<Map> response = tickets.post(rest, agentToken, id, "status",
                Map.of("status", "IN_PROGRESS"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("errorCode")).isEqualTo("ILLEGAL_TRANSITION");
        // The empty array is the useful answer here, not a missing field: it tells the
        // client that CLOSED is terminal, so it can stop offering status buttons entirely.
        assertThat((List<String>) response.getBody().get("allowedTransitions")).isEmpty();
        assertThat(response.getHeaders().getContentType()
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    @DisplayName("a legal transition from OPEN lists exactly the three legal targets")
    void allowedTransitionsAreUsefulForRecovery() {
        Long id = tickets.createId(rest, customerToken, "Wrong move", "Body");

        ResponseEntity<Map> response = tickets.post(rest, agentToken, id, "status",
                Map.of("status", "RESOLVED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<String>) response.getBody().get("allowedTransitions"))
                .containsExactly("ASSIGNED", "CLOSED", "TRIAGED");
    }

    @Test
    @DisplayName("moving to a waiting state without a reason is a 400")
    void waitingStatesRequireAReason() {
        Long id = tickets.createId(rest, customerToken, "No reason given", "Body");
        tickets.post(rest, agentToken, id, "assign", Map.of());

        ResponseEntity<Map> response = tickets.post(rest, agentToken, id, "status",
                Map.of("status", "WAITING_ON_CUSTOMER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a customer cannot drive the state machine")
    void customerCannotChangeStatus() {
        Long id = tickets.createId(rest, customerToken, "Mine", "Body");

        ResponseEntity<Map> response = tickets.post(rest, customerToken, id, "status",
                Map.of("status", "ASSIGNED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("posting to a closed ticket is a 409 TICKET_CLOSED")
    void cannotReplyToAClosedTicket() {
        Long id = tickets.createId(rest, customerToken, "Closed", "Body");
        tickets.post(rest, agentToken, id, "status", Map.of("status", "CLOSED"));

        ResponseEntity<Map> response = tickets.addMessage(rest, agentToken, id, "Hello", "PUBLIC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("errorCode")).isEqualTo("TICKET_CLOSED");
    }

    private ResponseEntity<Map> status(Long id, String target, String reason) {
        return tickets.post(rest, agentToken, id, "status", reason == null
                ? Map.of("status", target)
                : Map.of("status", target, "reason", reason));
    }

    private int openCount(Long userId) {
        return jdbc.queryForObject("SELECT open_count FROM agent_profile WHERE user_id = ?",
                Integer.class, userId);
    }
}
