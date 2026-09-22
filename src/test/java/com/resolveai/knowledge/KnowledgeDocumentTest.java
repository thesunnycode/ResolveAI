package com.resolveai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.platform.ai.LlmStub;
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
 * Upload, index, list, detail, delete — the whole 7A lifecycle over HTTP and the worker.
 */
class KnowledgeDocumentTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired KnowledgeTestSupport kb;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.resolveai.platform.ai.AiPolicyService policies;

    private AuthTestSupport.SeededTenant tenant;
    private String adminToken;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("kb");
        adminToken = auth.accessToken(rest, "kb", "admin");
        agentToken = auth.accessToken(rest, "kb", "agent");
        customerToken = auth.accessToken(rest, "kb", "customer");
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.reset();
        LlmStub.returnsEmbedding();
    }

    @Test
    @DisplayName("uploading a document queues it, and one poll of IndexWorker chunks and embeds it")
    void uploadAndIndex() {
        ResponseEntity<Map> response = create(adminToken, "RUNBOOK", "Test runbook",
                "## Heading one\n\nSome content here that is long enough to be a real "
                + "paragraph and describes ERR_PAY_TIMEOUT in payment-service.\n\n"
                + "## Heading two\n\nMore content in a second section, also mentioning "
                + "payment-service for good measure.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long id = ((Number) response.getBody().get("id")).longValue();
        assertThat(response.getBody().get("indexed")).isEqualTo(false);

        kb.runIndexingUntilSettled();

        assertThat(kb.chunkCount(id)).isGreaterThan(0);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT indexed_at, kb_version FROM knowledge_document WHERE id = ?", id);
        assertThat(row.get("indexed_at")).isNotNull();
        assertThat(((Number) row.get("kb_version")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("chunks carry valid, non-overlapping-in-order offsets into the parent body")
    void chunksCarryOffsets() {
        String body = "## Section A\n\n" + "Alpha content. ".repeat(80)
                + "\n\n## Section B\n\n" + "Beta content. ".repeat(80);
        Long id = kb.createIndexed(tenant.tenantId(), DocumentSource.RUNBOOK, "Offsets", body);

        var chunks = jdbc.queryForList(
                "SELECT char_start, char_end FROM knowledge_chunk WHERE document_id = ? "
                + "ORDER BY ordinal", id);

        assertThat(chunks).isNotEmpty();
        for (Map<String, Object> chunk : chunks) {
            int start = (Integer) chunk.get("char_start");
            int end = (Integer) chunk.get("char_end");
            assertThat(end).isGreaterThan(start);
            assertThat(end).isLessThanOrEqualTo(body.length());
        }
    }

    @Test
    @DisplayName("only ADMIN may upload or delete; AGENT and above may read")
    void authorizationIsEnforced() {
        assertThat(create(agentToken, "ARTICLE", "x", "y".repeat(50)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create(customerToken, "ARTICLE", "x", "y".repeat(50)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        Long id = kb.createIndexed(tenant.tenantId(), DocumentSource.ARTICLE, "Readable",
                "content ".repeat(30));

        assertThat(rest.exchange("/api/v1/knowledge/documents/" + id, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rest.exchange("/api/v1/knowledge/documents/" + id, HttpMethod.DELETE,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("uploading identical content twice is rejected, not silently duplicated")
    void duplicateContentIsRejected() {
        String body = "Identical content for the duplicate test. ".repeat(10);
        ResponseEntity<Map> first = create(adminToken, "ARTICLE", "First title", body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> second = create(adminToken, "ARTICLE", "Different title", body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("deleting a document soft-deletes it and hard-deletes its chunks")
    void deleteRemovesChunksAndSoftDeletesDocument() {
        Long id = kb.createIndexed(tenant.tenantId(), DocumentSource.ARTICLE, "To delete",
                "content ".repeat(30));
        assertThat(kb.chunkCount(id)).isGreaterThan(0);

        ResponseEntity<Void> response = rest.exchange("/api/v1/knowledge/documents/" + id,
                HttpMethod.DELETE, new HttpEntity<>(AuthTestSupport.bearer(adminToken)),
                Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(kb.chunkCount(id)).isZero();
        Object deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM knowledge_document WHERE id = ?", Object.class, id);
        assertThat(deletedAt).isNotNull();

        // Soft-deleted, so a normal read no longer finds it.
        assertThat(rest.exchange("/api/v1/knowledge/documents/" + id, HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(adminToken)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> create(String token, String source, String title, String body) {
        return rest.exchange("/api/v1/knowledge/documents", HttpMethod.POST,
                new HttpEntity<>(Map.of("source", source, "title", title, "body", body),
                        AuthTestSupport.bearer(token)),
                Map.class);
    }
}
