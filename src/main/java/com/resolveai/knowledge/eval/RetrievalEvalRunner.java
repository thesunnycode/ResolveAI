package com.resolveai.knowledge.eval;

import com.resolveai.knowledge.repository.HybridSearchRepository;
import com.resolveai.knowledge.service.KnowledgeSearchService;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the 70-case retrieval suite against whatever is currently indexed for a tenant,
 * and writes down what happened.
 *
 * <h2>Runs through {@link HybridSearchRepository} directly, not through the endpoint</h2>
 *
 * <p>Deliberately bypassing {@link KnowledgeSearchService}'s cache: an eval run has to
 * measure the query engine, not whatever happened to be cached from an earlier request
 * with the same text. It still goes through the real embedding path — the same budget
 * check, the same cache-by-content-hash that production search uses — because an eval
 * that bypasses the production embedding path measures something the product does not
 * do, the same argument {@code EvalRunner}'s class comment makes for classification.
 */
@Service
public class RetrievalEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalRunner.class);
    private static final String SUITE = "RETRIEVAL";

    private final RetrievalEvalCaseRepository cases;
    private final HybridSearchRepository hybridSearch;
    private final EmbeddingService embeddings;
    private final AiPolicyService policies;
    private final RetrievalEvalBaseline baseline;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RetrievalEvalRunner(RetrievalEvalCaseRepository cases,
                               HybridSearchRepository hybridSearch, EmbeddingService embeddings,
                               AiPolicyService policies, RetrievalEvalBaseline baseline,
                               JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.cases = cases;
        this.hybridSearch = hybridSearch;
        this.embeddings = embeddings;
        this.policies = policies;
        this.baseline = baseline;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record RunResult(Long runId, Map<String, RetrievalMetrics.GroupScores> scores,
                            boolean passed) {
    }

    public RunResult run(Long tenantId, int k) {
        AiPolicyService.Decision decision = policies.check(tenantId);
        if (!decision.externalModelAllowed()) {
            throw new IllegalStateException(
                    "Tenant " + tenantId + " does not permit external models; the retrieval "
                    + "suite needs to embed 70 queries and cannot run.");
        }

        List<RetrievalMetrics.CaseResult> results = TenantContext.callAs(tenantId, () -> {
            List<RetrievalMetrics.CaseResult> out = new java.util.ArrayList<>();
            for (RetrievalEvalCaseRepository.RawCase raw : cases.readAll()) {
                Set<Long> relevantChunks = chunkIdsFor(tenantId, raw.relevantDocumentTitles());
                if (relevantChunks.isEmpty()) {
                    log.warn("Retrieval eval case {} names document(s) {} with no indexed "
                             + "chunks; skipping", raw.name(), raw.relevantDocumentTitles());
                    continue;
                }
                float[] queryVector = embeddings.embed(tenantId, raw.query());
                List<Long> ranked = hybridSearch.search(raw.query(), queryVector, k, null)
                        .stream().map(HybridSearchRepository.HybridResult::chunkId).toList();
                out.add(new RetrievalMetrics.CaseResult(raw.name(), raw.group(),
                        relevantChunks, ranked));
            }
            return out;
        });

        Map<String, RetrievalMetrics.GroupScores> scores = RetrievalMetrics.score(results);
        RetrievalMetrics.GroupScores overall = scores.get("OVERALL");
        boolean passed = overall != null && baseline.passes(overall);

        Long runId = persist(tenantId, scores, passed);
        log.info("Retrieval eval run {}: {} — overall Recall@5={} MRR={} (floor {}/{})",
                runId, passed ? "PASS" : "FAIL",
                overall == null ? "n/a" : overall.recallAt5(),
                overall == null ? "n/a" : overall.mrr(),
                baseline.minRecallAt5(), baseline.minMrr());
        return new RunResult(runId, scores, passed);
    }

    private Set<Long> chunkIdsFor(Long tenantId, List<String> documentTitles) {
        if (documentTitles == null || documentTitles.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = jdbc.queryForList("""
                SELECT c.id FROM knowledge_chunk c
                  JOIN knowledge_document d ON d.id = c.document_id
                 WHERE c.tenant_id = ? AND d.title = ANY (?)
                """, Long.class, tenantId, documentTitles.toArray(new String[0]));
        return Set.copyOf(ids);
    }

    @Transactional
    Long persist(Long tenantId, Map<String, RetrievalMetrics.GroupScores> scores,
                boolean passed) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        scores.forEach((group, s) -> metrics.put(group, Map.of(
                "caseCount", s.caseCount(), "recallAt5", s.recallAt5(),
                "precisionAt5", s.precisionAt5(), "mrr", s.mrr(), "ndcgAt10", s.ndcgAt10())));

        return jdbc.queryForObject("""
                INSERT INTO eval_run (suite, model_id, metrics, passed, finished_at)
                VALUES (?, 'text-embedding-3-small', CAST(? AS jsonb), ?, NOW())
                RETURNING id
                """, Long.class, SUITE, objectMapper.writeValueAsString(metrics), passed);
    }
}
