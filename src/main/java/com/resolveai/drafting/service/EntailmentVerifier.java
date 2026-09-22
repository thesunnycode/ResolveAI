package com.resolveai.drafting.service;

import com.resolveai.drafting.domain.EntailmentVerdict;
import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Checks a claim against <b>only</b> its cited span. Doc 15 Task 15.
 *
 * <h2>Why the verifier sees nothing else — not the ticket, not the other chunks</h2>
 *
 * <p>This is the mechanism's independence guarantee, and it is stated in {@code
 * entailment@1}'s own prompt as well as enforced here by simply never constructing a
 * larger context to send. A verifier with the full context available will rationalise
 * support from material the claim did not cite — a plausible-sounding claim can then pass
 * by leaning on context its citation never named, which is exactly the fabrication this
 * whole pipeline exists to catch. The narrow context is not a cost optimisation; it is
 * what makes a pass mean "this specific span supports this specific claim" rather than
 * "the ticket seems to be about something like this."
 *
 * <h2>Cached, and run concurrently on virtual threads outside any transaction</h2>
 *
 * <p>A five-claim draft is five verification calls; done sequentially at ~800ms each that
 * is four extra seconds of latency for nothing, since the calls do not depend on each
 * other. Virtual threads make "just launch them all" the right amount of complexity for
 * this.
 *
 * <p>The cache key is {@code sha256(claim + span + promptVersion)}. The same claim–span
 * pair recurs constantly — two agents independently drafting from the same knowledge
 * article ask the identical question — and each cache hit is a call, a wait and a cost
 * avoided entirely. The prompt version is part of the key so that a prompt change (a
 * different {@code entailment@N}) cannot be served a verdict computed under the old
 * wording.
 */
@Service
public class EntailmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(EntailmentVerifier.class);
    private static final String PROMPT_NAME = "entailment";
    private static final String KEY_PREFIX = "entail:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    private final ModelRouter models;
    private final PromptVersionRepository prompts;
    private final StringRedisTemplate redis;

    public EntailmentVerifier(ModelRouter models, PromptVersionRepository prompts,
                              StringRedisTemplate redis) {
        this.models = models;
        this.prompts = prompts;
        this.redis = redis;
    }

    /** One claim, verified against one span, with the model call's cost if it was made. */
    public record Result(EntailmentVerdict.Result verdict, long costMicros, boolean cacheHit) {
    }

    /**
     * @param claimSpanPairs claim text paired with its single cited span's text. A claim
     *                       citing several chunks is verified against each pairing
     *                       independently by the caller and the verdicts combined —
     *                       this method verifies one pair.
     */
    public List<Result> verifyAll(Long tenantId, List<String[]> claimSpanPairs) {
        PromptVersion prompt = prompts.findByNameAndActiveTrue(PROMPT_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "No active prompt named " + PROMPT_NAME));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> futures = claimSpanPairs.stream()
                    .map(pair -> pool.submit(() -> verifyOne(tenantId, prompt, pair[0], pair[1])))
                    .toList();
            return futures.stream().map(this::await).toList();
        }
    }

    /**
     * Thrown when a verification call could not be completed at all — the provider was
     * unreachable, the budget ran out mid-batch, the policy changed underneath the run.
     *
     * <p><b>Never caught and downgraded to a verdict.</b> A draft where three of five
     * claims were genuinely checked and two could not be reached must not be scored as
     * though the two unreachable ones failed entailment — that reports a coverage number
     * computed from partial information as though it were complete. The whole draft has
     * to end {@code FAILED} instead; see {@code DraftWorker}.
     */
    public static class EntailmentUnavailableException extends RuntimeException {
        public EntailmentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Result verifyOne(Long tenantId, PromptVersion prompt, String claim, String span) {
        String key = KEY_PREFIX + sha256(claim + "|" + span + "|" + prompt.label());

        EntailmentVerdict.Result cached = readCache(key);
        if (cached != null) {
            return new Result(cached, 0, true);
        }

        String userText = "CLAIM:\n" + claim + "\n\nSPAN:\n" + span;
        LlmResult<EntailmentVerdict> result = models.call(tenantId, prompt, userText,
                EntailmentVerdict.class);

        writeCache(key, result.value().verdict());
        return new Result(result.value().verdict(), result.costMicros(), false);
    }

    /**
     * <b>Propagates, deliberately, rather than downgrading to a verdict.</b> See
     * {@link EntailmentUnavailableException}: a claim this call could not verify is not
     * the same fact as a claim the model read and rejected, and scoring them the same
     * way would let a provider outage silently masquerade as a considered "not
     * supported" — a worse failure than the draft simply not completing.
     */
    private Result await(Future<Result> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Entailment verification interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new EntailmentUnavailableException(
                    "Could not verify a claim: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private EntailmentVerdict.Result readCache(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : EntailmentVerdict.Result.valueOf(value);
        } catch (RuntimeException e) {
            log.debug("Entailment cache read failed for {}", key, e);
            return null;
        }
    }

    private void writeCache(String key, EntailmentVerdict.Result verdict) {
        try {
            redis.opsForValue().set(key, verdict.name(), CACHE_TTL);
        } catch (RuntimeException e) {
            log.debug("Entailment cache write failed for {}", key, e);
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
