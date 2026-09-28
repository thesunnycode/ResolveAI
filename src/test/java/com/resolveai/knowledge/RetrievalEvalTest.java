package com.resolveai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.knowledge.eval.RetrievalEvalRunner;
import com.resolveai.knowledge.eval.RetrievalMetrics;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the real 70-case retrieval suite against the real 75-document corpus, seeded and
 * indexed once for the whole class — the corpus does not change between test methods, and
 * seeding it is the most expensive part of this suite by far.
 *
 * <h2>The gate itself</h2>
 *
 * <p>{@link #suitePassesTheCommittedBaseline()} is the CI gate doc 15 Task 24 asks for:
 * failing below {@code retrieval-baseline.json}'s floor. It runs against
 * {@code LlmStub.returnsEmbedding()}, which returns one fixed vector for every query — see
 * that baseline file for why the floor is set where it is, and why the IDENTIFIER group
 * still scores well under a stub that cannot embed anything meaningfully: exact-token
 * matching is the lexical half's job, and the lexical half is real Postgres, not a stub.
 */
class RetrievalEvalTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired KnowledgeTestSupport kb;
    @Autowired RetrievalEvalRunner runner;
    @Autowired AiPolicyService policies;
    @Autowired ObjectMapper objectMapper;

    private static AuthTestSupport.SeededTenant tenant;
    private static boolean seeded;

    /**
     * Seeds and indexes the full corpus once, lazily, on whichever test method runs
     * first. Not {@code @BeforeAll}: that runs before Spring has injected this instance's
     * fields, and indexing 75 documents through the outbox and embedding path is
     * expensive enough that a per-method {@code @BeforeEach} would triple this class's
     * runtime for no reason — nothing about the corpus changes between the two methods
     * below.
     */
    private void ensureSeeded() {
        if (seeded) {
            return;
        }
        auth.wipe();
        tenant = auth.seedTenant("retrieval-eval");
        policies.ensureExists(tenant.tenantId());
        policies.evict(tenant.tenantId());
        LlmStub.reset();
        LlmStub.returnsEmbedding();

        for (Map<String, Object> doc : readCorpus()) {
            kb.createUnindexed(tenant.tenantId(), DocumentSource.valueOf((String) doc.get("source")),
                    (String) doc.get("title"), (String) doc.get("body"));
        }
        kb.runIndexingUntilSettled();
        seeded = true;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readCorpus() {
        try (InputStream in = new ClassPathResource("knowledge/corpus.json").getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            return (List<Map<String, Object>>) root.get("documents");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the retrieval suite runs to completion over the real corpus and reports every group")
    void suiteRunsAndReportsPerGroup() {
        ensureSeeded();

        RetrievalEvalRunner.RunResult result = runner.run(tenant.tenantId(), 10);

        assertThat(result.scores()).containsKeys("ARTICLE", "PARAPHRASE", "IDENTIFIER", "OVERALL");
        // The finding Task 10 exists to produce: reported per group, not only aggregate.
        RetrievalMetrics.GroupScores identifier = result.scores().get("IDENTIFIER");
        RetrievalMetrics.GroupScores article = result.scores().get("ARTICLE");
        assertThat(identifier.caseCount()).isEqualTo(10);
        assertThat(article.caseCount()).isEqualTo(40);
    }

    @Test
    @DisplayName("the suite passes the committed baseline — the CI gate")
    void suitePassesTheCommittedBaseline() {
        ensureSeeded();

        RetrievalEvalRunner.RunResult result = runner.run(tenant.tenantId(), 10);

        assertThat(result.passed())
                .as("scores: %s", result.scores())
                .isTrue();
    }
}
