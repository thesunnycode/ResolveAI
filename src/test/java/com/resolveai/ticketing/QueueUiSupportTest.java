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
import org.springframework.http.ResponseEntity;

/**
 * The read-side additions the UI audit's fixes rely on: the requester filter (customer
 * context pane, H2), reference search (command palette, H3), the staff directory
 * ("Assign to…", U7/U18) and the last-public-reply summary (customer list, U20) - plus
 * the rule that a customer's message count never reveals internal notes.
 */
class QueueUiSupportTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("qui");
        adminToken = auth.accessToken(rest, "qui", "admin");
        agentToken = auth.accessToken(rest, "qui", "agent");
        customerToken = auth.accessToken(rest, "qui", "customer");
    }

    @Test
    @DisplayName("requesterId narrows the list to one customer's tickets; customers may not use it")
    void requesterFilter() {
        tickets.createId(rest, customerToken, "From the customer", "Body");
        tickets.createId(rest, adminToken, "Raised by admin", "Body");

        assertThat(data(get("/api/v1/tickets?requesterId=" + tenant.customerId(), adminToken)))
                .extracting(t -> t.get("subject")).containsExactly("From the customer");
        assertThat(get("/api/v1/tickets?requesterId=" + tenant.customerId(), customerToken)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("q matches a ticket reference exactly, case-insensitively")
    void referenceSearch() {
        Long id = tickets.createId(rest, customerToken, "Card declined", "Body");
        tickets.createId(rest, customerToken, "Something else", "Body");
        String reference = (String) tickets.get(rest, adminToken, id).getBody().get("reference");

        assertThat(data(get("/api/v1/tickets?q=" + reference.toLowerCase(), adminToken)))
                .extracting(t -> t.get("reference")).containsExactly(reference);
    }

    @Test
    @DisplayName("GET /agents lists support staff with load, and is not open to customers")
    @SuppressWarnings("unchecked")
    void staffDirectory() {
        ResponseEntity<List> staff = rest.exchange("/api/v1/agents", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), List.class);
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = staff.getBody();
        assertThat(rows).extracting(r -> ((Number) r.get("id")).longValue()).contains(tenant.agentId());
        assertThat(rows).allSatisfy(r -> assertThat(r).doesNotContainKey("email"));
        assertThat(rows).extracting(r -> r.get("role")).doesNotContain("CUSTOMER", "ADMIN");

        assertThat(rest.exchange("/api/v1/agents", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(customerToken)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the list carries the last public reply; a customer's count skips internal notes")
    void lastReplyAndPublicCount() {
        Long id = tickets.createId(rest, customerToken, "Refund missing", "Body");
        assertThat(firstRow(customerToken).get("lastPublicReply")).isNull();

        tickets.addMessage(rest, adminToken, id, "Looking into it now.", "PUBLIC");
        tickets.addMessage(rest, adminToken, id, "Probably the gateway again.", "INTERNAL");

        Map<String, Object> asCustomer = firstRow(customerToken);
        assertThat(((Number) asCustomer.get("messageCount")).longValue()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) asCustomer.get("lastPublicReply");
        assertThat(last.get("fromSupport")).isEqualTo(true);
        assertThat(last.get("at")).isNotNull();

        assertThat(((Number) firstRow(adminToken).get("messageCount")).longValue()).isEqualTo(2);
    }

    private Map<String, Object> firstRow(String token) {
        List<Map<String, Object>> rows = data(get("/api/v1/tickets", token));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
