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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>The architectural claim of the whole project, made executable: AI failure degrades
 * the product, it never stops it.</b>
 *
 * <p>Everything in this file is a way for the model layer to fail, and in every one of
 * them the assertion is the same underneath: a support agent can still do their job.
 * Raise a ticket, reply to the customer, assign it, move it through the state machine,
 * have the SLA clock run and stop — all of it, with the provider returning 503 on every
 * call.
 *
 * <p>That is the difference between an AI feature and an AI dependency, and it is not
 * something you can assert by reading the code. It is the reason the triage path is a
 * worker behind an outbox rather than a call inside {@code POST /tickets}: a synchronous
 * classification would turn a provider outage into a total outage, and the request that
 * created the ticket would be the one that failed.
 */
class FailureModeTest extends IntegrationTestBase {

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
    @Autowired SlaFallbackSweeper sweeper;
    @Autowired AiPolicyService policies;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("degrade");
        slaSupport.seedPolicies(tenant.tenantId(), "PRO");
        slaSupport.makeCalendarAlwaysOpen(tenant.tenantId());
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL "
                    + "WHERE tenant_id = ?", tenant.tenantId());

        policies.ensureExists(tenant.tenantId());
        policies.update(tenant.tenantId(), true, List.of("openai"), true,
                1_000_000_000L, 30);
        policies.evict(tenant.tenantId());

        agentToken = auth.accessToken(rest, "degrade", "agent");
        customerToken = auth.accessToken(rest, "degrade", "customer");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        breakers.circuitBreaker("openai").reset();
    }

    // ── 1. The one that matters ─────────────────────────────────────────────

    @Test
    @DisplayName("With the provider down, a full ticket lifecycle still completes")
    void theProductWorksWithoutTheModel() {
        LlmStub.fails(503);

        // Create.
        Long ticketId = tickets.createId(rest, customerToken, "Refund not received",
                "The refund was promised five days ago.");

        // Triage tries and keeps failing, exactly as it should.
        for (int i = 0; i < 6; i++) {
            jdbc.update("UPDATE outbox_event SET next_attempt_at = NOW() - INTERVAL '1 minute' "
                        + "WHERE aggregate_id = ?", ticketId);
            runtime.runOnce(worker);
        }
        assertThat(outboxStatus(ticketId)).isEqualTo("DEAD");
        assertThat(analysis(ticketId).get("status")).isEqualTo("UNAVAILABLE");
        assertThat(analysis(ticketId).get("manualTriageRequired")).isEqualTo(true);

        // An agent triages it by hand - exactly what they would have done before any of
        // this existed.
        assertThat(tickets.post(rest, agentToken, ticketId, "priority-override",
                Map.of("priority", "P2", "reason", "Triage unavailable; graded by hand"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Assign, reply, move through the state machine, resolve.
        assertThat(tickets.post(rest, agentToken, ticketId, "assign", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tickets.addMessage(rest, agentToken, ticketId,
                "We have reissued the refund; it will land in 3-5 days.", "PUBLIC")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "IN_PROGRESS")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", "Refund reissued.")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // And the SLA engine tracked the whole thing. The first-response clock stopped
        // when the agent replied, and the resolution clock stopped when they resolved -
        // neither of which involves a model at any point.
        Map<String, Object> clocks = slaStates(ticketId);
        assertThat(clocks).containsKeys("FIRST_RESPONSE", "RESOLUTION");
        assertThat(clocks.get("FIRST_RESPONSE")).isIn("MET", "BREACHED");
        assertThat(clocks.get("RESOLUTION")).isIn("MET", "BREACHED");

        // Nothing reached the provider successfully, and nothing needed to.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?", Integer.class,
                ticketId)).isZero();
    }

    @Test
    @DisplayName("A ticket raised during an outage is still SLA-tracked within 15 minutes")
    void outageTicketsAreNotLostToTheSlaEngine() {
        LlmStub.fails(503);
        Long ticketId = tickets.createId(rest, customerToken, "Sync failing", "Tally sync.");
        runtime.runOnce(worker);

        jdbc.update("UPDATE ticket SET created_at = NOW() - INTERVAL '20 minutes' WHERE id = ?",
                ticketId);
        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(slaStates(ticketId)).hasSize(2);
        assertThat(priority(ticketId)).isEqualTo("P3");
    }

    // ── 2. The breaker ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Five failures open the breaker and the sixth call never leaves the JVM")
    void theBreakerStopsTheBleeding() {
        LlmStub.fails(503);
        CircuitBreaker breaker = breakers.circuitBreaker("openai");

        // Enough tickets to trip it. The threshold and window come from
        // application.yml; what is asserted here is the consequence, not the numbers.
        for (int i = 0; i < 8; i++) {
            tickets.createId(rest, customerToken, "Outage " + i, "Body " + i);
        }
        for (int pass = 0; pass < 4; pass++) {
            jdbc.update("UPDATE outbox_event SET next_attempt_at = NOW() - INTERVAL '1 minute'");
            runtime.runOnce(worker);
        }

        assertThat(breaker.getState())
                .as("repeated provider failures must open the breaker")
                .isIn(CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);

        int callsBefore = LlmStub.chatCallCount();
        breaker.transitionToOpenState();

        Long ticketId = tickets.createId(rest, customerToken, "One more", "Body");
        runtime.runOnce(worker);

        // This is the point of a breaker: the work is refused locally, so a degraded
        // provider cannot consume every worker's full retry budget and turn an AI
        // problem into an application-wide slowdown.
        assertThat(LlmStub.chatCallCount())
                .as("an open breaker means no network request at all")
                .isEqualTo(callsBefore);
        assertThat(analysis(ticketId).get("status")).isIn("PROCESSING", "UNAVAILABLE");
    }

    // ── 3-5. Budget, malformed output, policy ───────────────────────────────

    @Test
    @DisplayName("An exhausted budget holds the analysis and leaves ticketing untouched")
    void budgetExhaustionDegradesOnlyTheAi() {
        policies.update(tenant.tenantId(), true, List.of("openai"), true, 0L, 30);
        policies.evict(tenant.tenantId());
        LlmStub.returnsSignals(Category.PAYMENT);

        Long ticketId = tickets.createId(rest, customerToken, "Payment", "UPI failed.");
        runtime.runOnce(worker);

        assertThat(analysisStatus(ticketId)).isEqualTo("BUDGET_HELD");
        assertThat(LlmStub.chatCallCount())
                .as("the whole point of a budget is that the call does not happen")
                .isZero();

        // Ticketing carries on exactly as before.
        assertThat(tickets.post(rest, agentToken, ticketId, "assign", Map.of())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tickets.get(rest, agentToken, ticketId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Malformed output is repaired once, then recorded - never half-parsed")
    void malformedOutputNeverProducesPartialSignals() {
        LlmStub.returnsMalformed();
        Long ticketId = tickets.createId(rest, customerToken, "Confusing", "Ambiguous text.");
        runtime.runOnce(worker);

        assertThat(analysisStatus(ticketId)).isEqualTo("PARSE_FAILED");
        assertThat(LlmStub.chatCallCount())
                .as("one attempt and exactly one repair; a third costs money for the "
                    + "same answer")
                .isEqualTo(2);

        // The dangerous alternative is a TriageSignals with three of eight fields set:
        // it satisfies the type system, the policy computes a confident priority from
        // it, and the result looks exactly like a real classification.
        assertThat(priority(ticketId)).isEqualTo("UNTRIAGED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM priority_decision WHERE ticket_id = ?", Integer.class,
                ticketId)).isZero();
    }

    @Test
    @DisplayName("A repaired response succeeds on the second attempt")
    void oneRepairIsEnoughWhenTheModelCooperates() {
        LlmStub.returnsMalformedThenValid(Category.BILLING);
        Long ticketId = tickets.createId(rest, customerToken, "Invoice", "Wrong plan charged.");
        runtime.runOnce(worker);

        assertThat(analysisStatus(ticketId)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("SELECT category FROM ticket WHERE id = ?",
                String.class, ticketId)).isEqualTo("BILLING");
    }

    @Test
    @DisplayName("AI disabled by policy is a clean UNAVAILABLE, not a 500")
    void policyRefusalIsCleanlyReported() {
        policies.update(tenant.tenantId(), false, List.of(), true, 1_000_000L, 30);
        policies.evict(tenant.tenantId());
        LlmStub.returnsSignals(Category.PAYMENT);

        Long ticketId = tickets.createId(rest, customerToken, "Anything", "Body.");
        runtime.runOnce(worker);

        Map<String, Object> analysis = analysis(ticketId);
        assertThat(analysis.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(analysis.get("manualTriageRequired")).isEqualTo(true);
        assertThat(LlmStub.chatCallCount())
                .as("no text left the building")
                .isZero();
        // And the endpoint answered 200, because the analysis resource exists and this
        // is a successful read of its real state. A 503 would make a degraded optional
        // feature look like a broken API.
        assertThat(analysisResponse(ticketId).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> analysisResponse(Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/analysis", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analysis(Long ticketId) {
        return analysisResponse(ticketId).getBody();
    }

    private String priority(Long ticketId) {
        return jdbc.queryForObject("SELECT priority FROM ticket WHERE id = ?", String.class,
                ticketId);
    }

    private Map<String, Object> slaStates(Long ticketId) {
        return jdbc.query("""
                SELECT kind, state FROM sla_record
                 WHERE ticket_id = ? AND state <> 'CANCELLED'
                """,
                rs -> {
                    Map<String, Object> states = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        states.put(rs.getString("kind"), rs.getString("state"));
                    }
                    return states;
                }, ticketId);
    }

    private String analysisStatus(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT status FROM ai_analysis WHERE ticket_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, ticketId);
    }

    private String outboxStatus(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT status FROM outbox_event WHERE aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, ticketId);
    }
}
