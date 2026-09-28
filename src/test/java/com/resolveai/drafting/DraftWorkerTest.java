package com.resolveai.drafting;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.knowledge.KnowledgeTestSupport;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.ticketing.TicketTestSupport;
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
 * The drafting pipeline end to end: request a draft over HTTP, run {@code DraftWorker}
 * once, read it back with per-claim verdicts.
 */
class DraftWorkerTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired KnowledgeTestSupport kb;
    @Autowired DraftTestSupport draftWorker;
    @Autowired AiPolicyService policies;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;
    private Long chunkId;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("draft");
        agentToken = auth.accessToken(rest, "draft", "agent");
        customerToken = auth.accessToken(rest, "draft", "customer");
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.reset();
        LlmStub.returnsEmbedding();

        Long docId = kb.createIndexed(tenant.tenantId(), DocumentSource.RUNBOOK,
                "UPI payment failures",
                "## Reversal timeline\n\nA failed UPI payment that was debited is "
                + "reversed within 5-7 business days by payment-service.");
        chunkId = jdbc.queryForObject(
                "SELECT id FROM knowledge_chunk WHERE document_id = ? ORDER BY ordinal LIMIT 1",
                Long.class, docId);
    }

    @Test
    @DisplayName("a fully-supported draft is SHOWN with coverage 1.0 and an assembled reply")
    void fullySupportedDraftIsShown() {
        LlmStub.returnsDraftClaims(
                "[{\"text\":\"A failed UPI payment that was debited is reversed within "
                + "5-7 business days.\",\"citationIds\":[\"chunk:" + chunkId + "\"]}]",
                "REASSURING", List.of());
        LlmStub.returnsEntailmentVerdict("SUPPORTED");

        Long ticketId = tickets.createId(rest, customerToken, "Money gone, no confirmation",
                "I paid via UPI and the order still shows unpaid.");
        Long draftId = requestDraft(ticketId);

        draftWorker.runUntilSettled();

        Map<String, Object> draft = getDraft(draftId);
        assertThat(draft.get("status")).isEqualTo("SHOWN");
        assertThat(((Number) draft.get("coverage")).doubleValue()).isEqualTo(1.0);
        assertThat(draft.get("assembledText")).asString().contains("5-7 business days");

        List<Map<String, Object>> claims = (List<Map<String, Object>>) draft.get("claims");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).get("verdict")).isEqualTo("SUPPORTED");
        assertThat(claims.get(0).get("kept")).isEqualTo(true);
    }

    @Test
    @DisplayName("a fabricated numeric claim is dropped for zero LLM verification cost")
    void fabricatedNumericClaimIsDropped() {
        LlmStub.returnsDraftClaims(
                "[{\"text\":\"A failed UPI payment is reversed within 5-7 business days.\","
                + "\"citationIds\":[\"chunk:" + chunkId + "\"]},"
                + "{\"text\":\"Your refund of \\u20b92499 will be credited by 24 September.\","
                + "\"citationIds\":[\"chunk:" + chunkId + "\"]}]",
                "APOLOGETIC", List.of());
        LlmStub.returnsEntailmentVerdict("SUPPORTED");

        Long ticketId = tickets.createId(rest, customerToken, "Refund amount",
                "When will my refund arrive and how much?");
        Long draftId = requestDraft(ticketId);

        draftWorker.runUntilSettled();

        Map<String, Object> draft = getDraft(draftId);
        List<Map<String, Object>> claims = (List<Map<String, Object>>) draft.get("claims");
        assertThat(claims).hasSize(2);

        Map<String, Object> fabricated = claims.stream()
                .filter(c -> ((String) c.get("text")).contains("2499"))
                .findFirst().orElseThrow();
        assertThat(fabricated.get("verdict")).isEqualTo("FAILED_NUMERIC_CHECK");
        assertThat(fabricated.get("kept")).isEqualTo(false);
        assertThat((String) fabricated.get("rejectionReason")).contains("2499");

        // Zero entailment calls for the numeric-failed claim: the free filter caught it
        // before the model was ever asked to verify it.
        assertThat(LlmStub.chatCallCount()).isEqualTo(2); // 1 generation + 1 entailment
    }

    @Test
    @DisplayName("an unsupported claim is dropped and the draft is suppressed below threshold")
    void unsupportedClaimSuppressesTheDraft() {
        LlmStub.returnsDraftClaims(
                "[{\"text\":\"Unrelated claim about something the passage never discusses.\","
                + "\"citationIds\":[\"chunk:" + chunkId + "\"]}]",
                "NEUTRAL", List.of("No coverage for the customer's actual question."));
        LlmStub.returnsEntailmentVerdict("NOT_SUPPORTED");

        Long ticketId = tickets.createId(rest, customerToken, "Odd question", "Something else.");
        Long draftId = requestDraft(ticketId);

        draftWorker.runUntilSettled();

        Map<String, Object> draft = getDraft(draftId);
        assertThat(draft.get("status")).isEqualTo("SUPPRESSED_LOW_COVERAGE");
        assertThat(draft.get("assembledText")).isNull();
        assertThat(draft.get("recommendation")).isEqualTo("ESCALATE_TO_HUMAN");
        assertThat((String) draft.get("suppressionReason")).contains("0 of the 1");
        assertThat((List<String>) draft.get("unresolvedAspects")).isNotEmpty();
    }

    @Test
    @DisplayName("a partial verdict counts at half weight toward coverage")
    void partialVerdictCountsAtHalfWeight() {
        LlmStub.returnsDraftClaims(
                "[{\"text\":\"Claim one, fully supported.\","
                + "\"citationIds\":[\"chunk:" + chunkId + "\"]},"
                + "{\"text\":\"Claim two, partially supported.\","
                + "\"citationIds\":[\"chunk:" + chunkId + "\"]}]",
                "NEUTRAL", List.of());
        LlmStub.entailmentVerdictWhenClaimContains("fully supported", "SUPPORTED");
        LlmStub.entailmentVerdictWhenClaimContains("partially supported", "PARTIAL");

        Long ticketId = tickets.createId(rest, customerToken, "Mixed", "Body");
        Long draftId = requestDraft(ticketId);

        draftWorker.runUntilSettled();

        Map<String, Object> draft = getDraft(draftId);
        // (1.0 + 0.5) / 2 = 0.75, below the default 0.8 threshold.
        assertThat(((Number) draft.get("coverage")).doubleValue()).isEqualTo(0.75);
        assertThat(draft.get("status")).isEqualTo("SUPPRESSED_LOW_COVERAGE");
    }

    @Test
    @DisplayName("a second draft request while one is pending is 409 DRAFT_IN_PROGRESS")
    void concurrentDraftRequestIsRejected() {
        Long ticketId = tickets.createId(rest, customerToken, "Ticket", "Body");
        requestDraft(ticketId);

        ResponseEntity<Map> second = rest.exchange("/api/v1/tickets/" + ticketId + "/drafts",
                HttpMethod.POST, new HttpEntity<>(Map.of(), TicketTestSupport.authed(agentToken)),
                Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("errorCode")).isEqualTo("DRAFT_IN_PROGRESS");
    }

    @Test
    @DisplayName("a tenant with no indexed knowledge base gets 422 NO_KNOWLEDGE_BASE")
    void noKnowledgeBaseIsRejected() {
        var emptyTenant = auth.seedTenant("empty-kb");
        policies.ensureExists(emptyTenant.tenantId());
        policies.evict(emptyTenant.tenantId());
        String emptyAgentToken = auth.accessToken(rest, "empty-kb", "agent");
        String emptyCustomerToken = auth.accessToken(rest, "empty-kb", "customer");

        Long ticketId = tickets.createId(rest, emptyCustomerToken, "Ticket", "Body");

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId + "/drafts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), TicketTestSupport.authed(emptyAgentToken)), Map.class);

        // Compared by value: Spring 7 renamed the constant to UNPROCESSABLE_CONTENT.
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("errorCode")).isEqualTo("NO_KNOWLEDGE_BASE");
    }

    @Test
    @DisplayName("a customer may not request a draft")
    void customerCannotRequestADraft() {
        Long ticketId = tickets.createId(rest, customerToken, "Ticket", "Body");

        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId + "/drafts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), TicketTestSupport.authed(customerToken)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @SuppressWarnings("unchecked")
    private Long requestDraft(Long ticketId) {
        ResponseEntity<Map> response = rest.exchange("/api/v1/tickets/" + ticketId + "/drafts",
                HttpMethod.POST, new HttpEntity<>(Map.of(), TicketTestSupport.authed(agentToken)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return ((Number) response.getBody().get("draftId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDraft(Long draftId) {
        ResponseEntity<Map> response = rest.exchange("/api/v1/drafts/" + draftId,
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
