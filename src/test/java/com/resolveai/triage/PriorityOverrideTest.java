package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.sla.SlaTestSupport;
import com.resolveai.ticketing.TicketTestSupport;
import com.resolveai.ticketing.domain.Category;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.OffsetDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A human disagreeing with the policy, and a human asking for another go.
 *
 * <p>Both endpoints exist because the automated path will sometimes be wrong, and a
 * system that cannot be corrected by the people using it gets worked around instead.
 * The interesting assertions are the consequences: an override that does not move the
 * SLA deadline is a ticket escalated to P1 that keeps being judged on a P3 promise, and
 * a retriage that overwrites the old analysis destroys the evidence that the prompt
 * change did anything.
 */
class PriorityOverrideTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport slaSupport;
    @Autowired TriageWorker worker;
    @Autowired WorkerRuntime runtime;
    @Autowired AiPolicyService policies;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("override");
        // P1 resolves in an hour, P3 in a day: enough of a gap that a deadline that did
        // not move is unmistakable.
        slaSupport.seedPolicy(tenant.tenantId(), "P1", "PRO", 15, 60, "v1");
        slaSupport.seedPolicy(tenant.tenantId(), "P2", "PRO", 30, 240, "v1");
        slaSupport.seedPolicy(tenant.tenantId(), "P3", "PRO", 120, 1440, "v1");
        slaSupport.seedPolicy(tenant.tenantId(), "P4", "PRO", 240, 2880, "v1");
        slaSupport.makeCalendarAlwaysOpen(tenant.tenantId());
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL "
                    + "WHERE tenant_id = ?", tenant.tenantId());

        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());

        agentToken = auth.accessToken(rest, "override", "agent");
        customerToken = auth.accessToken(rest, "override", "customer");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        // Six failures in the retriage test trip the provider breaker, and it stays
        // tripped into the next test in the class. Resetting per test keeps each one
        // about the thing it is testing.
        breakers.circuitBreaker("openai").reset();
    }

    // ── Override ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An override records the reason and shortens the SLA deadline")
    void overrideRetargetsTheClock() {
        // Triage lands this at P3: SINGLE_USER, nothing else claimed.
        LlmStub.returnsSignals(Category.AUTH, "SINGLE_USER", "LOW", false, false, false, 0.8);
        Long ticketId = tickets.createId(rest, customerToken, "Cannot log in", "2FA loop.");
        runtime.runOnce(worker);

        assertThat(priority(ticketId)).isEqualTo("P3");
        assertThat(resolutionTarget(ticketId)).isEqualTo(1440);
        OffsetDateTime before = resolutionDeadline(ticketId);

        ResponseEntity<Map> response = override(ticketId, "P1",
                "Enterprise pilot account, contract says P1 for any login failure");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(priority(ticketId)).isEqualTo("P1");
        // The promise changed, so the clock it is measured against has to change with it.
        // Leaving the P3 target in place would report this ticket as comfortably within
        // SLA for the next twenty-three hours.
        assertThat(resolutionTarget(ticketId)).isEqualTo(60);
        assertThat(resolutionDeadline(ticketId)).isBefore(before);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT from_priority, to_priority, reason, overridden_by "
                + "FROM priority_override WHERE ticket_id = ?", ticketId);
        assertThat(row.get("from_priority")).isEqualTo("P3");
        assertThat(row.get("to_priority")).isEqualTo("P1");
        assertThat(row.get("reason")).asString().contains("contract says P1");
        assertThat(row.get("overridden_by")).isEqualTo(tenant.agentId());
    }

    @Test
    @DisplayName("Elapsed time survives the override rather than being handed back")
    void retargetKeepsElapsed() {
        LlmStub.returnsSignals(Category.AUTH, "SINGLE_USER", "LOW", false, false, false, 0.8);
        Long ticketId = tickets.createId(rest, customerToken, "Cannot log in", "2FA loop.");
        runtime.runOnce(worker);

        // Rewind the open segment so the clock has already burned 90 minutes - more than
        // the whole P1 budget of 60.
        jdbc.update("""
                UPDATE sla_clock_segment SET started_at = NOW() - INTERVAL '90 minutes'
                 WHERE sla_record_id = (SELECT id FROM sla_record
                                         WHERE ticket_id = ? AND kind = 'RESOLUTION')
                """, ticketId);

        override(ticketId, "P1", "Escalating after an internal review of the impact");

        // Overdue, not "sixty minutes from now". Computing the new deadline from the
        // full P1 budget would hand the ticket back the hour and a half it has already
        // spent, and an escalation would look like an improvement in SLA performance.
        //
        // Compared against the DATABASE clock, not the JVM's. The deadline was written
        // from NOW() inside Postgres, and a container whose clock is a second ahead of
        // the host makes a JVM-side comparison fail intermittently - which this project
        // has already paid for once, in the SLA poller.
        assertThat(jdbc.queryForObject("""
                SELECT next_deadline_at <= NOW() FROM sla_record
                 WHERE ticket_id = ? AND kind = 'RESOLUTION' AND state <> 'CANCELLED'
                """, Boolean.class, ticketId))
                .as("the new deadline must already be due, not %s",
                        resolutionDeadline(ticketId))
                .isTrue();
    }

    @Test
    @DisplayName("A one-word reason is rejected")
    void reasonMustBeSubstantial() {
        LlmStub.returnsSignals(Category.AUTH);
        Long ticketId = tickets.createId(rest, customerToken, "Cannot log in", "2FA loop.");
        runtime.runOnce(worker);

        // "wrong" is not a label, and this table is training data.
        assertThat(override(ticketId, "P1", "wrong").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(priority(ticketId)).isNotEqualTo("P1");
    }

    @Test
    @DisplayName("The rationale still explains the policy decision after an override")
    void rationaleSurvivesAnOverride() {
        LlmStub.returnsSignals(Category.AUTH, "SINGLE_USER", "LOW", false, false, false, 0.8);
        Long ticketId = tickets.createId(rest, customerToken, "Cannot log in", "2FA loop.");
        runtime.runOnce(worker);
        override(ticketId, "P1", "Enterprise pilot account, escalated by the account team");

        Map<String, Object> rationale = getJson(
                "/api/v1/tickets/" + ticketId + "/priority-rationale");

        // Still P3: the rationale explains what the POLICY decided, not what the ticket
        // currently is. Rewriting it to match the override would destroy the only record
        // of the disagreement - which is the thing worth keeping.
        assertThat(rationale.get("computedPriority")).isEqualTo("P3");
        assertThat(priority(ticketId)).isEqualTo("P1");
    }

    // ── Retriage ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Retriage after a failure produces a second analysis, and /analysis "
                 + "returns the newer one")
    void retriageProducesANewAttempt() {
        LlmStub.fails(503);
        Long ticketId = tickets.createId(rest, customerToken, "Sync broken", "Tally sync fails.");
        // Exhaust the retries so nothing is pending.
        for (int i = 0; i < 6; i++) {
            jdbc.update("UPDATE outbox_event SET next_attempt_at = NOW() - INTERVAL '1 minute' "
                        + "WHERE aggregate_id = ?", ticketId);
            runtime.runOnce(worker);
        }
        assertThat(outboxStatus(ticketId)).isEqualTo("DEAD");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        LlmStub.returnsSignals(Category.INTEGRATION);
        // Six consecutive 503s opened the provider breaker, which is exactly what it is
        // for - and it means the retriage would be refused without a network call. An
        // operator retrying after an outage waits for the half-open probe; a test
        // closes it directly rather than sleeping through the wait duration.
        breakers.circuitBreaker("openai").reset();

        ResponseEntity<Map> accepted = post("/api/v1/tickets/" + ticketId + "/retriage");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("attempt")).isEqualTo(1);

        runtime.runOnce(worker);

        Map<String, Object> analysis = getJson("/api/v1/tickets/" + ticketId + "/analysis");
        assertThat(analysis.get("status")).isEqualTo("READY");
        // INTEGRATION, SINGLE_USER, no money involved: base P3 and nothing bumps it.
        assertThat(priority(ticketId)).isEqualTo("P3");
        assertThat(jdbc.queryForObject("SELECT category FROM ticket WHERE id = ?",
                String.class, ticketId)).isEqualTo("INTEGRATION");
    }

    @Test
    @DisplayName("Retriage keeps the old analysis and adds a new one")
    void retriageDoesNotOverwriteHistory() {
        LlmStub.returnsSignals(Category.AUTH);
        Long ticketId = tickets.createId(rest, customerToken, "Login", "Cannot sign in.");
        runtime.runOnce(worker);

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        LlmStub.returnsSignals(Category.API);

        assertThat(post("/api/v1/tickets/" + ticketId + "/retriage").getBody().get("attempt"))
                .isEqualTo(2);
        runtime.runOnce(worker);

        // Two rows. "The model said AUTH in March and API in June after the prompt
        // change" is the evidence that the change did something; overwriting would
        // destroy it.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?",
                Integer.class, ticketId)).isEqualTo(2);
        assertThat(getJson("/api/v1/tickets/" + ticketId + "/analysis").get("status"))
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
                SELECT signals->>'category' FROM ai_analysis WHERE ticket_id = ?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, String.class, ticketId)).isEqualTo("API");
    }

    @Test
    @DisplayName("Retriage while one is queued is a 409")
    void retriageIsExclusive() {
        LlmStub.returnsSignals(Category.AUTH);
        Long ticketId = tickets.createId(rest, customerToken, "Login", "Cannot sign in.");
        // Not processed: the TICKET_CREATED event is still PENDING.

        ResponseEntity<Map> conflict = post("/api/v1/tickets/" + ticketId + "/retriage");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("errorCode")).isEqualTo("TRIAGE_IN_PROGRESS");
    }

    @Test
    @DisplayName("Retriage is rate limited to five an hour per ticket")
    void retriageIsRateLimited() {
        LlmStub.returnsSignals(Category.AUTH);
        Long ticketId = tickets.createId(rest, customerToken, "Login", "Cannot sign in.");
        runtime.runOnce(worker);

        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/v1/tickets/" + ticketId + "/retriage").getStatusCode())
                    .as("retriage %d should be accepted", i + 1)
                    .isEqualTo(HttpStatus.ACCEPTED);
            // Consume it so the next request is not a 409 instead.
            jdbc.update("UPDATE outbox_event SET status = 'DONE' "
                        + "WHERE aggregate_id = ? AND status = 'PENDING'", ticketId);
        }

        ResponseEntity<Map> limited = post("/api/v1/tickets/" + ticketId + "/retriage");
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("A customer cannot retriage or read a rationale")
    void bothEndpointsAreAgentOnly() {
        LlmStub.returnsSignals(Category.AUTH);
        Long ticketId = tickets.createId(rest, customerToken, "Login", "Cannot sign in.");
        runtime.runOnce(worker);

        assertThat(rest.exchange("/api/v1/tickets/" + ticketId + "/priority-rationale",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(customerToken)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/v1/tickets/" + ticketId + "/retriage",
                HttpMethod.POST, new HttpEntity<>(AuthTestSupport.bearer(customerToken)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> override(Long ticketId, String priority, String reason) {
        return tickets.post(rest, agentToken, ticketId, "priority-override",
                Map.of("priority", priority, "reason", reason));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class).getBody();
    }

    private String priority(Long ticketId) {
        return jdbc.queryForObject("SELECT priority FROM ticket WHERE id = ?", String.class,
                ticketId);
    }

    private int resolutionTarget(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT target_minutes FROM sla_record
                 WHERE ticket_id = ? AND kind = 'RESOLUTION' AND state <> 'CANCELLED'
                """, Integer.class, ticketId);
    }

    private OffsetDateTime resolutionDeadline(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT next_deadline_at FROM sla_record
                 WHERE ticket_id = ? AND kind = 'RESOLUTION' AND state <> 'CANCELLED'
                """, OffsetDateTime.class, ticketId);
    }

    private String outboxStatus(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT status FROM outbox_event WHERE aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, ticketId);
    }
}
