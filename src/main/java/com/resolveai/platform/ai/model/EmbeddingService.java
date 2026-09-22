package com.resolveai.platform.ai.model;

import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.AiSpendRecorder;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns text into a vector, and does not pay for the same vector twice.
 *
 * <h2>Why embeddings exist in Phase 6 at all</h2>
 *
 * <p>Retrieval is Phase 7 and incident correlation is Phase 8, so an embedding here looks
 * premature. It is not: correlation clusters tickets on these vectors, and the vector is
 * a <b>by-product of triage</b> — the redacted text is already in hand, already paid to
 * be prepared, and embedding it costs a fraction of the classification that just ran.
 * Computing it later would mean re-reading, re-redacting and re-paying for every ticket
 * in the window.
 *
 * <h2>The cache key is a hash of the redacted text, and only the redacted text</h2>
 *
 * <p>Two reasons, and both matter:
 *
 * <ul>
 *   <li><b>Privacy.</b> Hashing the raw text would put PII-derived keys in Redis. A hash
 *       is not encryption: with a known customer name, an attacker with the key space
 *       confirms a guess by computing it. The redacted text contains no personal data, so
 *       neither does its hash.
 *   <li><b>Hit rate.</b> Two tickets that differ only in a customer's name redact to the
 *       <i>same</i> string, and should produce the same vector — which is exactly the
 *       case incident correlation cares about. Hashing raw text would miss every one of
 *       them, and the miss would look like normal cache behaviour.
 * </ul>
 *
 * <p>This is also why {@code PiiRedactor} has to be deterministic: unstable placeholders
 * mean a new key every time, a cache that never hits, and an embedding bill that is
 * quietly double what it should be with nothing anywhere reporting a problem.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private static final String KEY_PREFIX = "emb:";
    /** Thirty days. Long, because the input is immutable: the same text embeds the same
     * way for as long as the model is pinned, and a shorter TTL only buys re-spending. */
    private static final Duration TTL = Duration.ofDays(30);

    private static final String EMBEDDING_MODEL_ID = "text-embedding-3-small";

    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final AiPolicyService policies;
    private final AiSpendRecorder spendRecorder;
    private final ModelRates rates;
    private final Counter hits;
    private final Counter misses;

    public EmbeddingService(EmbeddingModel embeddingModel, StringRedisTemplate redis,
                            JdbcTemplate jdbc, AiPolicyService policies,
                            AiSpendRecorder spendRecorder, ModelRates rates,
                            MeterRegistry metrics) {
        this.embeddingModel = embeddingModel;
        this.redis = redis;
        this.jdbc = jdbc;
        this.policies = policies;
        this.spendRecorder = spendRecorder;
        this.rates = rates;
        this.hits = metrics.counter("ai.embedding.cache", "result", "hit");
        this.misses = metrics.counter("ai.embedding.cache", "result", "miss");
    }

    /**
     * The vector for this text.
     *
     * @param redactedText <b>must already be redacted.</b> The parameter is named to say
     *                     so, because this method cannot tell — and an unredacted string
     *                     arriving here is sent to a third party and cached under a
     *                     PII-derived key in one step.
     */
    public float[] embed(Long tenantId, String redactedText) {
        String key = KEY_PREFIX + sha256(redactedText);

        float[] cached = readCache(key);
        if (cached != null) {
            hits.increment();
            return cached;
        }
        misses.increment();

        AiPolicyService.Decision decision = policies.check(tenantId);
        if (!decision.externalModelAllowed()) {
            throw new LlmUnavailableException(
                    "Tenant " + tenantId + " does not permit external models; no embedding "
                    + "was requested");
        }

        float[] vector;
        try {
            vector = embeddingModel.embed(redactedText);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Embedding call failed", e);
        }

        // Charged like any other model call. An embedding is cheap and therefore easy to
        // forget to account for, which is how a budget that looks generous runs out.
        long cost = rates.costMicros(EMBEDDING_MODEL_ID, estimateTokens(redactedText), 0);
        spendRecorder.record(tenantId, cost);

        writeCache(key, vector);
        return vector;
    }

    /**
     * Stores a ticket's vector.
     *
     * <p>A native UPDATE of one column rather than a mapped field. pgvector needs a
     * custom Hibernate type to map {@code vector(768)}, and writing one to set a single
     * column that nothing ever reads through the entity is more moving parts than the
     * problem deserves. The reads that matter are similarity searches, which are native
     * SQL anyway.
     */
    public void storeTicketEmbedding(Long ticketId, float[] vector) {
        jdbc.update("UPDATE ticket SET embedding = ?::vector WHERE id = ?",
                toVectorLiteral(vector), ticketId);
    }

    /**
     * {@code [0.1,0.2,…]} — pgvector's own input format.
     *
     * <p>Public because Phase 7's knowledge chunks need the identical literal for the
     * same reason tickets do: one formatting routine for every vector this system ever
     * writes, so a ticket's embedding and a chunk's embedding cannot quietly disagree on
     * precision or separators.
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }

    private static float[] fromVectorLiteral(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    private float[] readCache(String key) {
        try {
            String stored = redis.opsForValue().get(key);
            return stored == null ? null : fromVectorLiteral(stored);
        } catch (RuntimeException e) {
            // A cache that is down costs money, not correctness. Logged at debug because
            // the Redis health indicator is already saying so more usefully.
            log.debug("Embedding cache read failed", e);
            return null;
        }
    }

    private void writeCache(String key, float[] vector) {
        try {
            redis.opsForValue().set(key, toVectorLiteral(vector), TTL);
        } catch (RuntimeException e) {
            log.debug("Embedding cache write failed", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
