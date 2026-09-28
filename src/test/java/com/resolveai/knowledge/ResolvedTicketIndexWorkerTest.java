package com.resolveai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.ticketing.TicketTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The three quality gates that decide whether a resolved ticket becomes a knowledge
 * document. Doc 15 Task 25.
 */
class ResolvedTicketIndexWorkerTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired WorkerRuntime runtime;
    @Autowired AiPolicyService policies;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    private static final String SUBSTANTIVE_RESOLUTION =
            "The customer's UPI payment failed with ERR_UPI_COLLECT_FAILED because the "
            + "receiving VPA had been temporarily suspended by the PSP for KYC "
            + "re-verification. Once the customer's bank confirmed re-verification, "
            + "the payment was retried successfully and the reversal was cancelled.";

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("resolved");
        agentToken = auth.accessToken(rest, "resolved", "agent");
        customerToken = auth.accessToken(rest, "resolved", "customer");
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.reset();
        LlmStub.returnsEmbedding();
    }

    @Test
    @DisplayName("a substantive, unlinked, non-reopened resolution is indexed as a precedent")
    void substantiveResolutionIsIndexed() {
        Long ticketId = tickets.createId(rest, customerToken, "UPI payment failed",
                "My UPI payment failed with an error code.");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", SUBSTANTIVE_RESOLUTION));

        runIndexWorkerUntilSettled();

        Map<String, Object> doc = jdbc.queryForMap(
                "SELECT source, title FROM knowledge_document WHERE source = 'RESOLVED_TICKET'");
        assertThat(doc.get("source")).isEqualTo("RESOLVED_TICKET");
        assertThat((String) doc.get("title")).contains("TKT-");
    }

    @Test
    @DisplayName("a thin one-line resolution is not indexed")
    void thinResolutionIsSkipped() {
        Long ticketId = tickets.createId(rest, customerToken, "Quick fix", "Body.");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "resolve", Map.of("resolution", "Fixed it."));

        runIndexWorkerUntilSettled();

        assertThat(countResolvedTicketDocs()).isZero();
    }

    @Test
    @DisplayName("a reopened ticket's resolution is not indexed")
    void reopenedResolutionIsSkipped() {
        Long ticketId = tickets.createId(rest, customerToken, "Reopened case", "Body.");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", SUBSTANTIVE_RESOLUTION));
        tickets.post(rest, customerToken, ticketId, "reopen",
                Map.of("reason", "Still broken, please look again."));
        // Re-resolve after reopening, with the same substantive text, so the only
        // variable under test is the reopen count.
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", SUBSTANTIVE_RESOLUTION));

        runIndexWorkerUntilSettled();

        assertThat(countResolvedTicketDocs()).isZero();
    }

    @Test
    @DisplayName("an incident-linked ticket's resolution is not indexed")
    void incidentLinkedResolutionIsSkipped() {
        Long ticketId = tickets.createId(rest, customerToken, "Outage-related", "Body.");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        Long incidentId = jdbc.queryForObject("""
                INSERT INTO incident (tenant_id, reference, title, status, first_ticket_at)
                VALUES (?, 'INC-1000', 'Test outage', 'CONFIRMED', NOW())
                RETURNING id
                """, Long.class, tenant.tenantId());
        jdbc.update("INSERT INTO incident_ticket (incident_id, ticket_id) VALUES (?, ?)",
                incidentId, ticketId);

        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", SUBSTANTIVE_RESOLUTION));

        runIndexWorkerUntilSettled();

        assertThat(countResolvedTicketDocs()).isZero();
    }

    @Test
    @DisplayName("the indexed document's text is PII-redacted")
    void indexedDocumentIsPiiRedacted() {
        Long ticketId = tickets.createId(rest, customerToken, "Card issue",
                "My card 4111 1111 1111 1111 was declined.");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());
        tickets.post(rest, agentToken, ticketId, "resolve",
                Map.of("resolution", SUBSTANTIVE_RESOLUTION));

        runIndexWorkerUntilSettled();

        String body = jdbc.queryForObject(
                "SELECT body FROM knowledge_document WHERE source = 'RESOLVED_TICKET'",
                String.class);
        assertThat(body).doesNotContain("4111 1111 1111 1111");
    }

    private long countResolvedTicketDocs() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_document WHERE source = 'RESOLVED_TICKET'",
                Long.class);
    }

    private void runIndexWorkerUntilSettled() {
        for (int i = 0; i < 20; i++) {
            int handled = runtime.workers().stream()
                    .filter(w -> w.name().equals("ResolvedTicketIndexWorker"))
                    .mapToInt(runtime::runOnce)
                    .sum();
            if (handled == 0) {
                return;
            }
        }
    }
}
