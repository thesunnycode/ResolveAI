package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.HashMap;
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

/**
 * {@code PATCH /tickets/{id}} — the three-state semantics, and the mass-assignment guard.
 *
 * <p><b>{@link #patchingOneFieldLeavesTheOthersAlone()} is the reason this file exists.</b>
 * The first implementation used {@code Optional} components on a record to tell an absent
 * field from an explicit null. Jackson supplies {@code Optional.empty()} for <i>both</i>, so
 * {@code PATCH {"subject":"..."}} silently cleared the ticket's category and team, returned
 * {@code 200}, and made the ticket invisible to the agent who had just edited it. The
 * mechanism chosen to prevent unmentioned fields being wiped was the thing wiping them.
 */
class TicketPatchTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;
    private Long ticketId;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("patch");
        agentToken = auth.accessToken(rest, "patch", "agent");
        customerToken = auth.accessToken(rest, "patch", "customer");
        ticketId = tickets.createId(rest, customerToken, "Original subject", "Body");
        patch(Map.of("category", "PAYMENT"));
    }

    @Test
    @DisplayName("patching only the subject leaves category and team untouched")
    void patchingOneFieldLeavesTheOthersAlone() {
        patch(Map.of("subject", "Edited subject"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT subject, category, team_id FROM ticket WHERE id = ?", ticketId);

        assertThat(row.get("subject")).isEqualTo("Edited subject");
        assertThat(row.get("category")).isEqualTo("PAYMENT");
        assertThat(row.get("team_id")).isEqualTo(tenant.teamId());
    }

    @Test
    @DisplayName("an explicit null clears the field, which is a different request")
    void explicitNullClears() {
        // Sent as raw JSON. The application's ObjectMapper is configured with
        // default-property-inclusion: non_null, and TestRestTemplate shares it - so a
        // Map with a null value serialises to {} and the test would silently exercise
        // the absent case instead of the explicit-null one it is named after.
        assertThat(patchRaw("{\"category\": null}").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT category FROM ticket WHERE id = ?",
                String.class, ticketId)).isNull();
    }

    @Test
    @DisplayName("subject cannot be cleared, because a ticket with no subject is unreadable")
    void subjectCannotBeCleared() {
        ResponseEntity<Map> response = patchRaw("{\"subject\": null}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a field the DTO does not expose is ignored, not applied")
    void massAssignmentIsImpossible() {
        // The textbook attack on a PATCH handler bound to an entity. Every one of these is
        // a field with its own endpoint, its own authorization and its own side effects;
        // none is a component of UpdateTicketRequest, so Jackson has nowhere to put them.
        HashMap<String, Object> body = new HashMap<>();
        body.put("subject", "Legitimate edit");
        body.put("priority", "P1");
        body.put("status", "RESOLVED");
        body.put("assigneeId", tenant.agentId());
        body.put("tenantId", 999);
        body.put("reopenCount", 42);

        assertThat(patch(body).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT subject, priority, status, assignee_id, tenant_id, reopen_count
                  FROM ticket WHERE id = ?
                """, ticketId);
        assertThat(row.get("subject")).isEqualTo("Legitimate edit");
        assertThat(row.get("priority")).isEqualTo("UNTRIAGED");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("assignee_id")).isNull();
        assertThat(row.get("tenant_id")).isEqualTo(tenant.tenantId());
        assertThat(row.get("reopen_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("an empty patch body is a 400 rather than a silent no-op")
    void emptyPatchIsRejected() {
        ResponseEntity<Map> response = patch(Map.of());

        // A 200 here would tell the client its change was applied when nothing was even
        // requested, which is the same lie as an unrecognised field being accepted.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a customer cannot patch their own ticket")
    void customersCannotEdit() {
        HttpHeaders headers = TicketTestSupport.authed(customerToken);
        headers.set(HttpHeaders.IF_MATCH, tickets.etag(rest, agentToken, ticketId));

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("subject", "Renamed by the customer"), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("each changed field produces its own audit event")
    void everyChangeIsAudited() {
        long before = countEvents();

        patch(Map.of("subject", "New subject", "category", "BILLING"));

        assertThat(countEvents() - before).isEqualTo(2);
    }

    @Test
    @DisplayName("setting a field to the value it already has records nothing")
    void noOpChangesAreNotAudited() {
        long before = countEvents();

        patch(Map.of("category", "PAYMENT"));

        // A timeline full of "category changed from PAYMENT to PAYMENT" is a timeline
        // nobody reads, which makes the entries that matter harder to find.
        assertThat(countEvents()).isEqualTo(before);
    }

    private long countEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ticket_event WHERE ticket_id = ? AND event_type "
                + "= 'STATUS_CHANGED'", Long.class, ticketId);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> patchRaw(String json) {
        HttpHeaders headers = TicketTestSupport.authed(agentToken);
        headers.set(HttpHeaders.IF_MATCH, tickets.etag(rest, agentToken, ticketId));
        return rest.exchange("/api/v1/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>(json, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> patch(Map<String, Object> body) {
        HttpHeaders headers = TicketTestSupport.authed(agentToken);
        headers.set(HttpHeaders.IF_MATCH, tickets.etag(rest, agentToken, ticketId));
        return rest.exchange("/api/v1/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>(new HashMap<>(body), headers), Map.class);
    }
}
