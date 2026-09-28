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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Who sees what.
 *
 * <p>Two separate guarantees, and both are here because both are one mistake away from a
 * disclosure:
 *
 * <ol>
 *   <li><b>Row visibility</b> — the role scoping table. A customer sees their own tickets; an
 *       agent their assignments and their team's queue; a lead their team; an admin the
 *       tenant.
 *   <li><b>Field visibility</b> — {@code TicketCustomerResponse} has no component for an
 *       internal note, a priority rationale or a draft id. {@link #customerNeverSeesInternalFields()}
 *       is the assertion that the omission is real and not just intended.
 * </ol>
 */
class TicketVisibilityTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String adminToken;
    private String customerToken;
    private String otherCustomerToken;
    private Long otherCustomerId;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("vis");
        // A second customer in the same tenant. The interesting leak is not cross-tenant
        // (the discriminator handles that) but customer-to-customer inside one tenant,
        // where every row is legitimately visible to the query.
        otherCustomerId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role)
                SELECT ?, 'other@vis.test', password_hash, 'Other Customer', 'CUSTOMER'
                  FROM app_user WHERE id = ? RETURNING id
                """, Long.class, tenant.tenantId(), tenant.customerId());

        agentToken = auth.accessToken(rest, "vis", "agent");
        adminToken = auth.accessToken(rest, "vis", "admin");
        customerToken = auth.accessToken(rest, "vis", "customer");
        otherCustomerToken = auth.accessToken(rest, "vis", "other");
    }

    @Test
    @DisplayName("a customer sees only their own tickets in the list")
    void customerSeesOnlyTheirOwn() {
        tickets.createId(rest, customerToken, "Mine", "Body");
        tickets.createId(rest, otherCustomerToken, "Theirs", "Body");

        List<Map<String, Object>> mine = data(list(customerToken));
        List<Map<String, Object>> theirs = data(list(otherCustomerToken));

        assertThat(mine).extracting(t -> t.get("subject")).containsExactly("Mine");
        assertThat(theirs).extracting(t -> t.get("subject")).containsExactly("Theirs");
    }

    @Test
    @DisplayName("an admin sees every ticket in the tenant")
    void adminSeesEverything() {
        tickets.createId(rest, customerToken, "Mine", "Body");
        tickets.createId(rest, otherCustomerToken, "Theirs", "Body");

        assertThat(data(list(adminToken))).hasSize(2);
    }

    @Test
    @DisplayName("fetching another customer's ticket is 404, not 403")
    void anotherCustomersTicketIsNotFound() {
        Long id = tickets.createId(rest, otherCustomerToken, "Not yours", "Body");

        ResponseEntity<Map> response = tickets.get(rest, customerToken, id);

        // 403 would confirm the ticket exists. Walk the id space, collect the 403s, and
        // you have a count of every tenant's tickets and a set of live ids to try
        // elsewhere. 404 says nothing.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("errorCode")).isEqualTo("TICKET_NOT_FOUND");
    }

    /**
     * <b>The highest-consequence assertion in the project.</b>
     *
     * <p>An agent writes an internal note about a customer. The customer opens their own
     * ticket. The note must not be in the response — and neither must the fields that only
     * exist for agents, which must be <i>absent</i> rather than present and null: a null
     * {@code latestDraftId} still tells the customer that drafts exist.
     */
    @Test
    @DisplayName("a customer's ticket detail contains no internal message and no agent fields")
    void customerNeverSeesInternalFields() {
        Long id = tickets.createId(rest, customerToken, "Refund not received",
                "I was told five days ago it had been processed.");
        tickets.post(rest, agentToken, id, "assign", Map.of());
        tickets.addMessage(rest, agentToken, id,
                "Checked with finance - this customer has chargebacked twice before.",
                "INTERNAL");
        tickets.addMessage(rest, agentToken, id,
                "Thanks for waiting - the refund is on its way.", "PUBLIC");

        ResponseEntity<Map> asAgent = tickets.get(rest, agentToken, id);
        ResponseEntity<Map> asCustomer = tickets.get(rest, customerToken, id);

        List<Map<String, Object>> agentThread =
                (List<Map<String, Object>>) asAgent.getBody().get("messages");
        List<Map<String, Object>> customerThread =
                (List<Map<String, Object>>) asCustomer.getBody().get("messages");

        assertThat(agentThread).hasSize(2);
        assertThat(agentThread).extracting(m -> m.get("visibility"))
                .contains("INTERNAL", "PUBLIC");

        assertThat(customerThread).hasSize(1);
        assertThat(customerThread).extracting(m -> m.get("visibility")).containsExactly("PUBLIC");
        assertThat(asCustomer.getBody().toString()).doesNotContain("chargebacked");

        // Absent, not null. A null field advertises that something is being withheld, and
        // it is the shape a shared DTO with conditional nulls would produce - which is
        // precisely the design this asserts we did not use.
        assertThat(asCustomer.getBody())
                .doesNotContainKeys("priorityRationale", "analysisStatus", "latestDraftId",
                        "timeline", "sla", "team", "incident");
        assertThat(asAgent.getBody()).containsKeys("analysisStatus", "team");
    }

    @Test
    @DisplayName("a customer cannot widen their scope with teamId or assigneeId")
    void customerCannotPassScopeFilters() {
        ResponseEntity<Map> withTeam = rest.exchange(
                "/api/v1/tickets?teamId=" + tenant.teamId(), HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(customerToken)), Map.class);

        // 403 rather than silently ignoring the parameter: quietly dropping a filter
        // returns a result set the caller did not ask for and has no way to detect.
        assertThat(withTeam.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an agent sees their team's queue as well as their own assignments")
    void agentSeesTheTeamQueue() {
        // Created by a customer, routed to the default team, assigned to nobody. If an
        // agent could not see this, they could never pick up unclaimed work.
        tickets.createId(rest, customerToken, "Unclaimed", "Body");

        List<Map<String, Object>> visible = data(list(agentToken));

        assertThat(visible).extracting(t -> t.get("subject")).containsExactly("Unclaimed");
    }

    @Test
    @DisplayName("assigneeId=none finds the unclaimed work")
    void unassignedFilter() {
        Long claimed = tickets.createId(rest, customerToken, "Claimed", "Body");
        tickets.createId(rest, customerToken, "Unclaimed", "Body");
        tickets.post(rest, agentToken, claimed, "assign", Map.of());

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets?assigneeId=none",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class);

        assertThat(data(response)).extracting(t -> t.get("subject")).containsExactly("Unclaimed");
    }

    @Test
    @DisplayName("assigneeId=me finds what the caller is holding")
    void meFilter() {
        Long claimed = tickets.createId(rest, customerToken, "Claimed", "Body");
        tickets.createId(rest, customerToken, "Unclaimed", "Body");
        tickets.post(rest, agentToken, claimed, "assign", Map.of());

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets?assigneeId=me",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class);

        assertThat(data(response)).extracting(t -> t.get("subject")).containsExactly("Claimed");
    }

    @Test
    @DisplayName("a customer cannot add an internal note")
    void customerCannotWriteInternalNotes() {
        Long id = tickets.createId(rest, customerToken, "Mine", "Body");

        ResponseEntity<Map> response = tickets.addMessage(rest, customerToken, id,
                "Trying to write an internal note", "INTERNAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> list(String token) {
        return rest.exchange("/api/v1/tickets", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }
}
