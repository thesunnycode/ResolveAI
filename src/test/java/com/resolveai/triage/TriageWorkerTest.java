package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.platform.outbox.OutboxRepository;
import com.resolveai.platform.outbox.OutboxStatus;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.sla.SlaTestSupport;
import com.resolveai.ticketing.TicketTestSupport;
import com.resolveai.ticketing.domain.Category;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole triage pipeline, end to end, against a stubbed provider.
 *
 * <p>A ticket goes in over HTTP; one poll of the worker later it has an analysis, a
 * priority with a recorded argument, a team, an assignee and two running SLA clocks —
 * and none of it cost a penny or touched the network.
 *
 * <p>The tests that matter most here are the ones about what happens when the model
 * does <i>not</i> cooperate. A triage pipeline that works when the provider is healthy
 * is the easy half; the half that decides whether this is shippable is whether a ticket
 * raised during an outage is still workable, still tracked, and still honest about what
 * it knows.
 */
class TriageWorkerTest extends IntegrationTestBase {

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
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("triage");
        slaSupport.seedPolicies(tenant.tenantId(), "PRO");
        slaSupport.makeCalendarAlwaysOpen(tenant.tenantId());

        // The seeded agent works 09:00-18:00, so on a suite that runs at 02:00 the
        // routing test would silently assert "nobody was assigned" and pass for the
        // wrong reason. A round-the-clock shift removes the clock from the equation;
        // the shift filter itself is tested explicitly below.
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL "
                    + "WHERE tenant_id = ?", tenant.tenantId());

        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());

        agentToken = auth.accessToken(rest, "triage", "agent");
        customerToken = auth.accessToken(rest, "triage", "customer");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("A created ticket is classified, prioritised, routed, assigned and clocked")
    void triagesEndToEnd() {
        LlmStub.returnsSignals(Category.PAYMENT);
        Long ticketId = createTicket();

        assertThat(runtime.runOnce(worker)).isEqualTo(1);

        Map<String, Object> ticket = ticketRow(ticketId);
        // PAYMENT + SINGLE_USER + paymentAffected on a PRO plan: base P3, one bump.
        assertThat(ticket.get("priority")).isEqualTo("P2");
        assertThat(ticket.get("category")).isEqualTo("PAYMENT");
        assertThat(ticket.get("team_id")).isEqualTo(tenant.teamId());
        assertThat(ticket.get("assignee_id")).isEqualTo(tenant.agentId());

        // The capacity the assignment consumed was booked in the same transaction.
        assertThat(openCount(tenant.agentId())).isEqualTo(1);

        // Both clocks, started against the priority that was just decided rather than
        // against a default guessed at creation time.
        assertThat(slaKinds(ticketId)).containsExactlyInAnyOrder("FIRST_RESPONSE", "RESOLUTION");
        assertThat(jdbc.queryForObject(
                "SELECT target_minutes FROM sla_record WHERE ticket_id = ? AND kind = 'RESOLUTION'",
                Integer.class, ticketId)).isEqualTo(240);

        assertThat(outbox.findByAggregate("TICKET", ticketId).get(0).status())
                .isEqualTo(OutboxStatus.DONE);
    }

    @Test
    @DisplayName("GET /analysis reports READY with the signals and what the call cost")
    @SuppressWarnings("unchecked")
    void analysisBecomesReady() {
        LlmStub.returnsSignals(Category.API, "TEAM", "HIGH", true, false, false, 0.88);
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        Map<String, Object> analysis = get("/api/v1/tickets/" + ticketId + "/analysis");
        assertThat(analysis.get("status")).isEqualTo("READY");

        Map<String, Object> signals = (Map<String, Object>) analysis.get("signals");
        assertThat(signals.get("category")).isEqualTo("API");
        assertThat(signals.get("reportedImpact")).isEqualTo("TEAM");
        assertThat(signals.get("serviceDownClaimed")).isEqualTo(true);

        // Non-zero because the stub reports real token counts. A fixture that returned
        // zero tokens would make every test agree that everything is free.
        assertThat((Integer) analysis.get("tokensIn")).isPositive();
        assertThat(((Number) analysis.get("costMicros")).longValue()).isPositive();
        assertThat(analysis.get("promptVersion")).isEqualTo("triage@1");
    }

    @Test
    @DisplayName("The rationale splits model inputs from system inputs and reconstructs "
                 + "the priority")
    @SuppressWarnings("unchecked")
    void rationaleExplainsTheDecision() {
        LlmStub.returnsSignals(Category.PAYMENT, "ORG_WIDE", "LOW", true, false, true, 0.93);
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        Map<String, Object> rationale =
                get("/api/v1/tickets/" + ticketId + "/priority-rationale");

        assertThat(rationale.get("computedPriority")).isEqualTo("P1");
        assertThat(rationale.get("policyVersion")).isEqualTo("v1");
        assertThat(rationale.get("overridable")).isEqualTo(true);

        // The split is the whole reason this endpoint exists: it is what lets somebody
        // tell later whether the model misread the ticket or the policy is wrong.
        Map<String, Object> inputs = (Map<String, Object>) rationale.get("inputs");
        Map<String, Object> fromModel = (Map<String, Object>) inputs.get("fromModel");
        Map<String, Object> fromSystem = (Map<String, Object>) inputs.get("fromSystem");
        assertThat(fromModel).containsKeys("category", "reportedImpact", "confidence");
        assertThat(fromSystem).containsEntry("planTier", "PRO").containsEntry("reopenCount", 0);
        // And the model's half must NOT contain a priority. If this ever starts failing,
        // the architecture has been quietly inverted.
        assertThat(fromModel).doesNotContainKey("priority");

        List<Map<String, Object>> rules = (List<Map<String, Object>>) rationale.get("rules");
        assertThat(rules).hasSize(8);
        assertThat(rules).anySatisfy(rule -> {
            assertThat(rule.get("rule")).isEqualTo("PLAN_TIER_BUMP");
            assertThat(rule.get("matched")).isEqualTo(false);
            // The near-miss: the useful part of an unmatched rule.
            assertThat((String) rule.get("note")).contains("ENTERPRISE would");
        });
        assertThat((String) rationale.get("humanReadable")).startsWith("P1 because");
    }

    @Test
    @DisplayName("The redacted text is what leaves, and the embedding is stored")
    void redactsBeforeCallingAndStoresTheVector() {
        LlmStub.returnsSignals(Category.PAYMENT);
        Long ticketId = tickets.createId(rest, customerToken, "Refund not received",
                "Customer triage has not had a refund; call on 9876543210.");

        runtime.runOnce(worker);

        String sent = LlmStub.start().findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching(
                                ".*/chat/completions"))).get(0).getBodyAsString();
        assertThat(sent)
                .as("no personal data may appear in the request body")
                .doesNotContain("9876543210")
                .doesNotContain("Customer triage");
        assertThat(sent).contains("«PHONE_").contains("«PERSON_");

        assertThat(jdbc.queryForObject(
                "SELECT embedding IS NOT NULL FROM ticket WHERE id = ?", Boolean.class,
                ticketId)).isTrue();
    }

    // ── Failure, which is where the design is actually tested ───────────────

    @Test
    @DisplayName("A provider outage leaves the ticket workable and the job retrying")
    void providerFailureIsRetriedNotRecorded() {
        LlmStub.fails(503);
        Long ticketId = createTicket();

        runtime.runOnce(worker);

        // No analysis row: writing FAILED here would occupy attempt 1 and permanently
        // block the retry that is about to happen from recording its success.
        assertThat(analysisCount(ticketId)).isZero();
        assertThat(outbox.findByAggregate("TICKET", ticketId).get(0).status())
                .isEqualTo(OutboxStatus.PENDING);

        // And the ticket is entirely usable in the meantime.
        Map<String, Object> ticket = ticketRow(ticketId);
        assertThat(ticket.get("priority")).isEqualTo("UNTRIAGED");
        assertThat(get("/api/v1/tickets/" + ticketId + "/analysis").get("status"))
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("A tenant with AI switched off gets a FAILED analysis, not a retry loop")
    void policyRefusalIsFinal() {
        policies.update(tenant.tenantId(), false, List.of(), true, 1_000_000L, 30);
        policies.evict(tenant.tenantId());
        LlmStub.returnsSignals(Category.PAYMENT);
        Long ticketId = createTicket();

        runtime.runOnce(worker);

        // Nothing left the building.
        assertThat(LlmStub.chatCallCount()).isZero();
        assertThat(analysisStatus(ticketId)).isEqualTo("FAILED");
        assertThat(outbox.findByAggregate("TICKET", ticketId).get(0).status())
                .isEqualTo(OutboxStatus.DONE);

        Map<String, Object> analysis = get("/api/v1/tickets/" + ticketId + "/analysis");
        assertThat(analysis.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(analysis.get("manualTriageRequired")).isEqualTo(true);
    }

    @Test
    @DisplayName("An exhausted budget is recorded as BUDGET_HELD without a model call")
    void budgetExhaustionIsRecorded() {
        policies.update(tenant.tenantId(), true, List.of("openai"), true, 0L, 30);
        policies.evict(tenant.tenantId());
        LlmStub.returnsSignals(Category.PAYMENT);
        Long ticketId = createTicket();

        runtime.runOnce(worker);

        assertThat(analysisStatus(ticketId)).isEqualTo("BUDGET_HELD");
        assertThat(LlmStub.chatCallCount())
                .as("the point of a budget is that the call does not happen")
                .isZero();
    }

    @Test
    @DisplayName("Output that cannot be parsed twice is recorded as PARSE_FAILED")
    void unparseableOutputIsTerminal() {
        LlmStub.returnsMalformed();
        Long ticketId = createTicket();

        runtime.runOnce(worker);

        assertThat(analysisStatus(ticketId)).isEqualTo("PARSE_FAILED");
        // Two calls: the original and the one repair attempt. Not three.
        assertThat(LlmStub.chatCallCount()).isEqualTo(2);
        assertThat(ticketRow(ticketId).get("priority")).isEqualTo("UNTRIAGED");
    }

    @Test
    @DisplayName("A category the model invented fails rather than becoming a null")
    void unknownCategoryIsRejected() {
        LlmStub.returnsUnknownEnum();
        Long ticketId = createTicket();

        runtime.runOnce(worker);

        // The interesting negative: the JSON is syntactically perfect, so a lenient
        // parser would hand the policy a TriageSignals with a null category and it
        // would confidently compute a priority from nothing.
        assertThat(analysisStatus(ticketId)).isEqualTo("PARSE_FAILED");
        assertThat(ticketRow(ticketId).get("category")).isNull();
    }

    // ── Idempotency and redelivery ──────────────────────────────────────────

    @Test
    @DisplayName("Reprocessing the same event does not produce a second analysis")
    void redeliveryIsIdempotent() {
        LlmStub.returnsSignals(Category.PAYMENT);
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        // Put the event back as if the worker had crashed after committing its writes
        // but before marking the row done - the exact window the reaper exists for.
        jdbc.update("""
                UPDATE outbox_event SET status = 'PENDING', locked_until = NULL,
                       processed_at = NULL
                 WHERE aggregate_id = ?
                """, ticketId);

        runtime.runOnce(worker);

        assertThat(analysisCount(ticketId))
                .as("uq_analysis_ticket_prompt is what makes the redelivery harmless")
                .isEqualTo(1);
        assertThat(openCount(tenant.agentId()))
                .as("and the agent was not charged for a second assignment")
                .isEqualTo(1);
    }

    // ── The SLA gap that moving the call between transactions opened ────────

    @Test
    @DisplayName("A ticket whose triage never succeeded still gets default clocks")
    void fallbackSweeperRescuesUntrackedTickets() {
        LlmStub.fails(503);
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        assertThat(slaKinds(ticketId))
                .as("no triage, so no clocks - this is the hole")
                .isEmpty();

        // Age the ticket past the ten-minute grace period.
        jdbc.update("UPDATE ticket SET created_at = NOW() - INTERVAL '20 minutes' WHERE id = ?",
                ticketId);

        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(slaKinds(ticketId)).containsExactlyInAnyOrder(
                "FIRST_RESPONSE", "RESOLUTION");
        assertThat(ticketRow(ticketId).get("priority")).isEqualTo("P3");
        // And the timeline says the system defaulted it rather than implying a decision.
        assertThat(jdbc.queryForObject("""
                SELECT payload->>'source' FROM ticket_event
                 WHERE ticket_id = ? AND event_type = 'PRIORITY_CHANGED'
                 ORDER BY id DESC LIMIT 1
                """, String.class, ticketId)).isEqualTo("SLA_FALLBACK");
    }

    @Test
    @DisplayName("The sweeper leaves a freshly created ticket alone")
    void fallbackRespectsTheGracePeriod() {
        LlmStub.fails(503);
        createTicket();
        runtime.runOnce(worker);

        // Not aged: triage may still be retrying, and sweeping now would race the worker
        // to start the same clocks.
        assertThat(sweeper.sweepOnce()).isZero();
    }

    // ── Connection discipline ───────────────────────────────────────────────

    @Test
    @DisplayName("No database connection is held across the model call")
    void holdsNoConnectionDuringTheCall() throws Exception {
        LlmStub.returnsSignalsAfter(Category.PAYMENT, java.time.Duration.ofMillis(600));
        for (int i = 0; i < 5; i++) {
            createTicket();
        }

        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger peak = new AtomicInteger();
        AtomicLong samples = new AtomicLong();
        AtomicLong activeSum = new AtomicLong();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int active = hikari.getHikariPoolMXBean().getActiveConnections();
                peak.accumulateAndGet(active, Math::max);
                activeSum.addAndGet(active);
                samples.incrementAndGet();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            assertThat(runtime.runOnce(worker)).isEqualTo(5);
        } finally {
            sampler.interrupt();
            sampler.join(1000);
        }

        double mean = activeSum.get() / (double) Math.max(1, samples.get());

        // THE ASSERTION IS THE MEAN, NOT THE PEAK, and that is worth explaining because
        // the obvious test asserts the peak and is wrong.
        //
        // Five triages start together, so their five short read transactions genuinely
        // overlap and the peak is legitimately five - the same number a broken
        // implementation would show. The peak cannot tell the two apart; it is bounded
        // by the batch size either way.
        //
        // What separates them is DURATION. Each event spends ~600ms in a stubbed
        // network call and single-digit milliseconds in its transactions, so if no
        // connection is held across the call the mean active count over the run is a
        // small fraction of one. If a @Transactional wrapped the whole of process(),
        // all five connections would be held for the entire 600ms and the mean would
        // sit just under five. There is no overlap between those two outcomes.
        assertThat(mean)
                .as("mean active connections over the run (a held transaction would be ~5)")
                .isLessThan(2.0);
        assertThat(peak.get())
                .as("bounded by the batch size, not by the pool")
                .isLessThanOrEqualTo(5);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long createTicket() {
        return tickets.createId(rest, customerToken, "Payment stuck",
                "UPI debited but the order is still unpaid.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(AuthTestSupport.bearer(agentToken)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> ticketRow(Long ticketId) {
        return jdbc.queryForMap(
                "SELECT priority, category, team_id, assignee_id FROM ticket WHERE id = ?",
                ticketId);
    }

    private int openCount(Long userId) {
        return jdbc.queryForObject(
                "SELECT open_count FROM agent_profile WHERE user_id = ?", Integer.class, userId);
    }

    private List<String> slaKinds(Long ticketId) {
        return jdbc.queryForList(
                "SELECT kind FROM sla_record WHERE ticket_id = ? AND state <> 'CANCELLED'",
                String.class, ticketId);
    }

    private int analysisCount(Long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private String analysisStatus(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT status FROM ai_analysis WHERE ticket_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """, String.class, ticketId);
    }
}
