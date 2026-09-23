package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.sla.SlaTestSupport;
import com.resolveai.sla.service.SlaDeadlinePoller;
import com.resolveai.ticketing.TicketTestSupport;
import com.resolveai.triage.routing.UnassignedTicketSweeper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A ticket that triage could not place is not left waiting for a human to notice it.
 *
 * <p>The sweeper retries the claim; the escalation ladder tells the team lead when there
 * is still nobody to tell.
 */
class UnassignedTicketSweeperTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport sla;
    @Autowired UnassignedTicketSweeper sweeper;
    @Autowired SlaDeadlinePoller poller;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("sweep");
        sla.makeCalendarAlwaysOpen(tenant.tenantId());
        sla.seedPolicies(tenant.tenantId(), "PRO");
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL "
                    + "WHERE tenant_id = ?", tenant.tenantId());
        agentToken = auth.accessToken(rest, "sweep", "agent");
        customerToken = auth.accessToken(rest, "sweep", "customer");
    }

    @Test
    @DisplayName("A waiting ticket is assigned once an agent becomes available, and only once")
    void assignsWhenAnAgentFreesUp() {
        Long ticketId = routedButUnassigned("Card declined");
        setAvailable(false);

        assertThat(sweeper.sweepOnce()).isZero();
        assertThat(assignee(ticketId)).isNull();

        setAvailable(true);
        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(assignee(ticketId)).isEqualTo(tenant.agentId());
        assertThat(status(ticketId)).isEqualTo("ASSIGNED");
        assertThat(openCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_event
                 WHERE ticket_id = ? AND event_type = 'ASSIGNED'
                   AND payload ->> 'source' = 'AUTO_ROUTING_RETRY'
                """, Integer.class, ticketId)).isEqualTo(1);

        // Nothing left to do; a second pass must not touch the count.
        assertThat(sweeper.sweepOnce()).isZero();
        assertThat(openCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Higher priority tickets are placed first when capacity is short")
    void highestPriorityFirst() {
        Long p3 = routedButUnassigned("Invoice question");
        Long p1 = routedButUnassigned("Checkout down");
        jdbc.update("UPDATE ticket SET priority = 'P3' WHERE id = ?", p3);
        jdbc.update("UPDATE ticket SET priority = 'P1' WHERE id = ?", p1);
        jdbc.update("UPDATE agent_profile SET max_concurrent = 1 WHERE tenant_id = ?",
                tenant.tenantId());

        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(assignee(p1)).isEqualTo(tenant.agentId());
        assertThat(assignee(p3)).isNull();
    }

    @Test
    @DisplayName("An escalation on an unassigned ticket goes to the team lead")
    void escalatesToTheLead() {
        Long leadId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role, team_id)
                VALUES (?, 'lead@sweep.test', 'x', 'Lead sweep', 'TEAM_LEAD', ?) RETURNING id
                """, Long.class, tenant.tenantId(), tenant.teamId());
        Long ticketId = routedButUnassigned("Nobody on shift");
        for (String kind : List.of("FIRST_RESPONSE", "RESOLUTION")) {
            sla.rewind(sla.recordId(ticketId, kind), 500);
        }

        int passes = 0;
        while (poller.pollOnce() > 0 && passes < 10) {
            passes++;
        }

        List<Long> recipients = jdbc.queryForList("""
                SELECT DISTINCT recipient_id FROM notification
                 WHERE tenant_id = ? AND kind IN ('SLA_ESCALATION', 'SLA_BREACH')
                """, Long.class, tenant.tenantId());
        assertThat(recipients).containsExactly(leadId);
    }

    /** Triaged and routed to the team, with nobody claimed - the state triage leaves off shift. */
    private Long routedButUnassigned(String subject) {
        Long ticketId = tickets.createId(rest, customerToken, subject, "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        jdbc.update("""
                UPDATE ticket SET team_id = ?, assignee_id = NULL,
                       status = CASE WHEN status IN ('OPEN','TRIAGED') THEN status ELSE 'TRIAGED' END
                 WHERE id = ?
                """, tenant.teamId(), ticketId);
        return ticketId;
    }

    private void setAvailable(boolean available) {
        jdbc.update("UPDATE agent_profile SET is_available = ? WHERE tenant_id = ?",
                available, tenant.tenantId());
    }

    private Long assignee(Long ticketId) {
        return jdbc.queryForObject("SELECT assignee_id FROM ticket WHERE id = ?",
                Long.class, ticketId);
    }

    private String status(Long ticketId) {
        return jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?",
                String.class, ticketId);
    }

    private int openCount() {
        return jdbc.queryForObject("SELECT open_count FROM agent_profile WHERE user_id = ?",
                Integer.class, tenant.agentId());
    }
}
