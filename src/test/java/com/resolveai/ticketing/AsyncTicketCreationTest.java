package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.OutboxRepository;
import com.resolveai.platform.outbox.OutboxStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creation is asynchronous now: {@code 202}, an outbox event, and a status resource to
 * poll.
 *
 * <p>The assertion that carries the design is {@link #theTicketAndItsJobCommitTogether()}
 * — not because committing two rows in one transaction is clever, but because the
 * alternative every tutorial reaches for (save, then publish to a broker) has two silent
 * failure modes and this has none.
 */
class AsyncTicketCreationTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        auth.seedTenant("async");
        agentToken = auth.accessToken(rest, "async", "agent");
        customerToken = auth.accessToken(rest, "async", "customer");
    }

    @Test
    @DisplayName("POST /tickets returns 202 with a pointer to the analysis resource")
    @SuppressWarnings("unchecked")
    void creationIsAccepted() {
        ResponseEntity<Map> response = tickets.create(rest, customerToken,
                "Payment deducted but order pending", "UPI debited, order still unpaid.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("analysisStatus")).isEqualTo("PROCESSING");
        assertThat(body.get("priority")).isEqualTo("UNTRIAGED");
        // Null, and deliberately so: nothing has decided them yet, and the client is
        // being told to poll rather than to render them.
        assertThat(body.get("category")).isNull();
        assertThat(body.get("assignee")).isNull();

        Map<String, String> links = (Map<String, String>) body.get("links");
        assertThat(links.get("analysis"))
                .isEqualTo("/api/v1/tickets/" + body.get("id") + "/analysis");
    }

    @Test
    @DisplayName("The ticket and its triage job commit in one transaction")
    void theTicketAndItsJobCommitTogether() {
        Long ticketId = tickets.createId(rest, customerToken, "Queued", "Body");

        List<OutboxEvent> events = outbox.findByAggregate("TICKET", ticketId);
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.eventType()).isEqualTo(EventType.TICKET_CREATED);
        assertThat(event.status()).isEqualTo(OutboxStatus.PENDING);
        // The tenant travels on the row, because the worker that picks this up has no
        // request and therefore no tenant context of its own.
        assertThat(event.tenantId()).isNotNull();
        // Identifying, not descriptive: the worker re-reads the ticket rather than
        // trusting a copy of the subject that may be edited before it runs.
        assertThat(event.payload()).contains("\"ticketId\"").doesNotContain("Body");
    }

    @Test
    @DisplayName("A replayed creation does not queue a second triage")
    void idempotentReplayQueuesOneJob() {
        String key = java.util.UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("subject", "Double submit", "body", "Retried.");

        ResponseEntity<Map> first = rest.exchange("/api/v1/tickets",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body,
                        TicketTestSupport.authed(customerToken, key)), Map.class);
        ResponseEntity<Map> second = rest.exchange("/api/v1/tickets",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body,
                        TicketTestSupport.authed(customerToken, key)), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        // One ticket, one job. A replay that queued a second event would triage the same
        // ticket twice and bill the tenant for both.
        assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
    }

    // ── The analysis status resource ────────────────────────────────────────

    @Test
    @DisplayName("Analysis is PROCESSING while the job is queued")
    @SuppressWarnings("unchecked")
    void analysisIsProcessingWhileQueued() {
        Long ticketId = tickets.createId(rest, customerToken, "In flight", "Body");

        ResponseEntity<Map> response = analysis(ticketId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("PROCESSING");
        // The server says how often to poll. Left to guess, clients pick either "once a
        // minute" or "as fast as possible", and both are wrong.
        assertThat(response.getBody().get("retryAfterSeconds")).isEqualTo(3);
        assertThat(response.getBody().get("enqueuedAt")).isNotNull();
    }

    @Test
    @DisplayName("A dead triage job reads as UNAVAILABLE — with a 200, not a 503")
    void deadJobReadsAsUnavailable() {
        Long ticketId = tickets.createId(rest, customerToken, "Provider down", "Body");
        jdbc.update("""
                UPDATE outbox_event SET status = 'DEAD', attempts = 5, last_error = 'provider'
                 WHERE aggregate_type = 'TICKET' AND aggregate_id = ?
                """, ticketId);

        ResponseEntity<Map> response = analysis(ticketId);

        // 200, because the analysis resource exists and "we tried and failed" is a real
        // state of it. A 5xx would make a degraded optional feature look like a broken
        // API — and the ticket is entirely workable by hand throughout.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().get("reason")).isEqualTo("PROVIDER_ERROR");
        assertThat(response.getBody().get("attempts")).isEqualTo(5);
        // The field a UI acts on: surface this for manual triage instead of showing a
        // spinner that will never stop.
        assertThat(response.getBody().get("manualTriageRequired")).isEqualTo(true);
    }

    @Test
    @DisplayName("A completed analysis reads as READY with its cost and provenance")
    @SuppressWarnings("unchecked")
    void completedAnalysisReadsAsReady() {
        Long ticketId = tickets.createId(rest, customerToken, "Triaged", "Body");
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM ticket WHERE id = ?",
                Long.class, ticketId);
        Long promptId = seedPromptVersion();

        jdbc.update("""
                INSERT INTO ai_analysis (ticket_id, tenant_id, prompt_version_id, model_id,
                                         signals, confidence, tokens_in, tokens_out,
                                         cost_micros, latency_ms, status)
                VALUES (?, ?, ?, 'gpt-4.1-mini', ?::jsonb, 0.91, 1187, 168, 412, 2840, 'OK')
                """, ticketId, tenantId, promptId,
                "{\"category\":\"PAYMENT\",\"paymentAffected\":true}");
        jdbc.update("""
                UPDATE outbox_event SET status = 'DONE' WHERE aggregate_id = ?
                """, ticketId);

        Map<String, Object> body = analysis(ticketId).getBody();

        assertThat(body.get("status")).as("analysis body was %s", body).isEqualTo("READY");
        assertThat((Map<String, Object>) body.get("signals"))
                .containsEntry("category", "PAYMENT")
                .containsEntry("paymentAffected", true);
        // Provenance, not decoration: which prompt and which model produced this is the
        // first question asked when a classification looks wrong, and the answer has to
        // survive the prompt being superseded.
        assertThat(body.get("promptVersion")).isEqualTo("triage@1");
        assertThat(body.get("modelId")).isEqualTo("gpt-4.1-mini");
        assertThat(body.get("costMicros")).isEqualTo(412);
        // No priority here. The model reports observations; a versioned rule set turns
        // them into a priority, and that lives behind /priority-rationale.
        assertThat(body).doesNotContainKey("priority");
    }

    @Test
    @DisplayName("A customer cannot read the analysis")
    void customersCannotReadAnalysis() {
        Long ticketId = tickets.createId(rest, customerToken, "Mine", "Body");

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId + "/analysis",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(
                        com.resolveai.AuthTestSupport.bearer(customerToken)), Map.class);

        // The signals are internal reasoning about the customer's own message —
        // "linguisticUrgency: HIGH" is a judgement they have every reason to object to.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> analysis(Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/analysis",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(
                        com.resolveai.AuthTestSupport.bearer(agentToken)), Map.class);
    }

    /**
     * Prompt versions are global rather than per tenant: one catalogue for the whole
     * system, so "which prompt produced this?" has the same answer everywhere and an
     * eval run means the same thing across tenants.
     */
    private Long seedPromptVersion() {
        return jdbc.queryForObject("""
                INSERT INTO prompt_version (name, version, template, model_id,
                                            output_schema, is_active)
                VALUES ('triage', 1, 'Classify the ticket.', 'gpt-4.1-mini',
                        '{"type":"object"}'::jsonb, TRUE)
                RETURNING id
                """, Long.class);
    }
}
