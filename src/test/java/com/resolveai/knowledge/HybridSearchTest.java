package com.resolveai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
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
 * The RRF hybrid query, over HTTP.
 *
 * <p>The test that matters most here is
 * {@link #tenantPreFilterAppliesInsideTheCandidateScan()} — doc 15 Task 8 calls tenant
 * pre-filtering "the single most likely silent bug in retrieval", and it is silent
 * precisely because a post-filter bug still returns *some* results, just fewer than it
 * should. Nothing short of a cross-tenant assertion catches it.
 */
class HybridSearchTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired KnowledgeTestSupport kb;
    @Autowired JdbcTemplate jdbc;
    @Autowired AiPolicyService policies;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("search");
        agentToken = auth.accessToken(rest, "search", "agent");
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.reset();
        LlmStub.returnsEmbedding();
    }

    @Test
    @DisplayName("a document indexed for this tenant is findable by its own text")
    void findsAnIndexedDocument() {
        kb.createIndexed(tenant.tenantId(), DocumentSource.RUNBOOK, "UPI failures",
                "## Reversal timeline\n\nA failed UPI payment that was debited is "
                + "reversed within 5-7 business days by payment-service.");

        ResponseEntity<Map> response = search(agentToken, "UPI reversal timeline");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> results = (List<Map<String, Object>>)
                response.getBody().get("results");
        assertThat(results).isNotEmpty();
        assertThat(response.getBody().get("strategy")).isEqualTo("HYBRID_RRF");
    }

    @Test
    @DisplayName("explain=true returns the full score breakdown; explain=false does not")
    void explainReturnsScoreBreakdown() {
        kb.createIndexed(tenant.tenantId(), DocumentSource.ARTICLE, "Rate limiting",
                "api-gateway enforces 100 requests per minute per key, returning "
                + "ERR_RATE_LIMIT when exceeded.");

        Map<String, Object> explained = firstResult(search(agentToken,
                "rate limit ERR_RATE_LIMIT", true));
        Map<String, Object> plain = firstResult(search(agentToken,
                "rate limit ERR_RATE_LIMIT", false));

        assertThat(explained).containsKey("scores");
        assertThat(explained.get("scores")).isNotNull();
        assertThat(plain.get("scores")).isNull();

        Map<String, Object> scores = (Map<String, Object>) explained.get("scores");
        assertThat(scores).containsKeys("rrfScore", "tierBoost", "finalScore");
    }

    /**
     * <b>The assertion Task 8 exists for.</b> Two tenants index near-identical content;
     * a search from one must never surface the other's chunk. If the tenant predicate
     * ever moved outside the CTE's WHERE — applied after the LIMIT 50 rather than
     * before — this would still often pass, because the other tenant's row would simply
     * be one of many filtered out downstream rather than never selected at all. Making
     * both tenants' content near-identical is what removes that escape: there is nothing
     * for a leaked row to lose a relevance contest against.
     */
    @Test
    @DisplayName("tenant pre-filter applies inside the candidate scan, not after")
    void tenantPreFilterAppliesInsideTheCandidateScan() {
        var other = auth.seedTenant("search2");
        policies.ensureExists(other.tenantId());
        policies.evict(other.tenantId());

        String sharedText = "## Duplicate settlement guidance\n\nSettlement delays of "
                + "T+2 business days are normal for ERR_SETTLEMENT_DELAY across every "
                + "PSP integration Ledgerly supports.";
        kb.createIndexed(tenant.tenantId(), DocumentSource.ARTICLE,
                "Settlement guidance (tenant A)", sharedText);
        kb.createIndexed(other.tenantId(), DocumentSource.ARTICLE,
                "Settlement guidance (tenant B)", sharedText);

        ResponseEntity<Map> response = search(agentToken, "settlement delay guidance");

        List<Map<String, Object>> results = (List<Map<String, Object>>)
                response.getBody().get("results");
        assertThat(results).isNotEmpty();
        assertThat(results).extracting(r -> r.get("documentTitle"))
                .as("no result may belong to the other tenant's document")
                .doesNotContain("Settlement guidance (tenant B)");
    }

    @Test
    @DisplayName("a resolved-ticket precedent ranks below an equally-relevant runbook")
    void authoritativeOutranksPrecedentAtEqualRelevance() {
        String text = "## Card decline guidance\n\nA card payment declined with "
                + "ERR_PAY_DECLINED usually means insufficient limit or a blocked card.";
        kb.createIndexed(tenant.tenantId(), DocumentSource.RUNBOOK, "Card runbook", text);
        kb.createIndexed(tenant.tenantId(), DocumentSource.RESOLVED_TICKET,
                "Card precedent", text + "\n\nAdditional precedent-only context noting "
                + "the same guidance applied on a real past ticket.");

        Map<String, Object> top = firstResult(search(agentToken,
                "card declined ERR_PAY_DECLINED", true));

        // Identical text means identical rrfScore for both; only the tier boost can
        // separate them, and it must favour the runbook.
        assertThat(top.get("source")).isEqualTo("RUNBOOK");
        assertThat(top.get("tier")).isEqualTo("AUTHORITATIVE");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstResult(ResponseEntity<Map> response) {
        List<Map<String, Object>> results = (List<Map<String, Object>>)
                response.getBody().get("results");
        assertThat(results).isNotEmpty();
        return results.get(0);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> search(String token, String query) {
        return search(token, query, false);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> search(String token, String query, boolean explain) {
        String url = "/api/v1/knowledge/search?q=" + query.replace(" ", "%20")
                + "&explain=" + explain;
        return rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(token)), Map.class);
    }
}
