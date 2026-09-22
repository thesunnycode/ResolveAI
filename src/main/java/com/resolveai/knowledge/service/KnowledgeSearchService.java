package com.resolveai.knowledge.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.knowledge.repository.HybridSearchRepository;
import com.resolveai.knowledge.repository.HybridSearchRepository.HybridResult;
import com.resolveai.knowledge.web.dto.SearchResponse;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Hybrid search, with the full score breakdown — doc 05 §3.6, doc 15 Tasks 9–11.
 *
 * <h2>This endpoint is a debugging tool wearing a search box</h2>
 *
 * <p>{@code explain=true} is not an afterthought flag. It is why this class exists as a
 * service distinct from something that could have been three lines in a controller: the
 * per-source ranks and scores it returns are the actual evidence for "why hybrid search"
 * — a response where one chunk ranked 1st lexically and 3rd by vector, and another was
 * the reverse, is the argument made as data rather than as an assertion.
 *
 * <h2>The cache key includes {@code kbVersion}</h2>
 *
 * <p>{@code knowledge_document.kb_version} is bumped on every index, edit and delete.
 * Folding the tenant's current maximum {@code kb_version} into the cache key means a KB
 * edit makes every previously cached result for that tenant unreachable automatically —
 * no explicit eviction, no invalidation bug where an edit is made and the cache is
 * forgotten.
 */
@Service
public class KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HybridSearchRepository hybridSearch;
    private final EmbeddingService embeddings;
    private final AiPolicyService policies;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public KnowledgeSearchService(HybridSearchRepository hybridSearch,
                                  EmbeddingService embeddings, AiPolicyService policies,
                                  StringRedisTemplate redis, JdbcTemplate jdbc,
                                  ObjectMapper objectMapper) {
        this.hybridSearch = hybridSearch;
        this.embeddings = embeddings;
        this.policies = policies;
        this.redis = redis;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(String query, int k, String sourceFilter, boolean explain) {
        Long tenantId = TenantContext.getRequired();
        long kbVersion = currentKbVersion(tenantId);
        String cacheKey = cacheKey(tenantId, query, k, sourceFilter, kbVersion);

        SearchResponse cached = explain ? null : readCache(cacheKey);
        if (cached != null) {
            return cached.withCacheHit(true);
        }

        long embedStart = System.nanoTime();
        AiPolicyService.Decision decision = policies.check(tenantId);
        if (!decision.externalModelAllowed()) {
            throw new ApiException(ErrorCode.AI_DISABLED_BY_POLICY,
                    "This tenant does not permit external models; search embeddings "
                    + "cannot be computed.");
        }
        float[] queryVector = embeddings.embed(tenantId, query);
        long embeddingMs = (System.nanoTime() - embedStart) / 1_000_000L;

        long queryStart = System.nanoTime();
        List<HybridResult> results = hybridSearch.search(query, queryVector, k, sourceFilter);
        long queryMs = (System.nanoTime() - queryStart) / 1_000_000L;

        List<SearchResponse.ResultView> views = results.stream()
                .map(r -> toView(r, explain))
                .toList();

        SearchResponse response = new SearchResponse(query, kbVersion, "HYBRID_RRF",
                new SearchResponse.Timings(embeddingMs, queryMs, embeddingMs + queryMs),
                views, false);

        if (!explain) {
            writeCache(cacheKey, response);
        }
        return response;
    }

    private SearchResponse.ResultView toView(HybridResult r, boolean explain) {
        String snippet = r.text().length() > 300 ? r.text().substring(0, 300) + "…" : r.text();
        SearchResponse.Scores scores = explain
                ? new SearchResponse.Scores(r.lexicalRank(), r.lexicalScore(), r.vectorRank(),
                        r.vectorScore(), r.rrfScore(), r.tierBoost(), r.finalScore())
                : null;
        return new SearchResponse.ResultView(r.chunkId(), r.documentId(), r.documentTitle(),
                r.source(), tierOf(r.source()), snippet, r.charStart(), r.charEnd(), scores);
    }

    private static String tierOf(String source) {
        return "RESOLVED_TICKET".equals(source) ? "PRECEDENT" : "AUTHORITATIVE";
    }

    private long currentKbVersion(Long tenantId) {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(kb_version), 0) FROM knowledge_document WHERE tenant_id = ?",
                Long.class, tenantId);
        return max == null ? 0 : max;
    }

    private static String cacheKey(Long tenantId, String query, int k, String sourceFilter,
                                   long kbVersion) {
        String raw = tenantId + "|" + query + "|" + k + "|" + sourceFilter;
        return "ret:" + sha256(raw) + ":" + kbVersion;
    }

    private SearchResponse readCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, SearchResponse.class);
        } catch (RuntimeException e) {
            log.debug("Retrieval cache read failed for {}", key, e);
            return null;
        }
    }

    private void writeCache(String key, SearchResponse response) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Retrieval cache write failed for {}", key, e);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
