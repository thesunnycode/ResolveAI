package com.resolveai.drafting.service;

import com.resolveai.drafting.domain.DraftGeneration;
import com.resolveai.drafting.domain.RawClaim;
import com.resolveai.knowledge.repository.HybridSearchRepository.HybridResult;
import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.prompt.PromptVersion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns retrieved chunks and a ticket into claims. Doc 15 Task 13.
 *
 * <h2>Claims, not prose</h2>
 *
 * <p>{@code draft@1}'s whole reason for existing as a schema rather than a free-text
 * response: coverage, suppression and per-claim rejection all need something to operate
 * on <i>per assertion</i>. A single "response" string has nothing smaller than itself to
 * verify, so verification would collapse to "did the model cite anything anywhere" —
 * which checks that a citation exists, not that each sentence a customer reads is
 * supported.
 *
 * <h2>Context budget and chunk labelling</h2>
 *
 * <p>Each retrieved chunk is labelled {@code chunk:{id}} in the prompt, using the real
 * database id — the same id the model is asked to cite back. Chunks are added to the
 * context in ranked order until the token budget (using {@code DocumentChunker}'s
 * estimator — approximate is fine for a budget check, exact for what gets billed) would
 * be exceeded, and anything dropped for budget reasons is logged. <b>Silent truncation
 * makes a retrieval bug look like a generation bug</b> — a claim's citation missing
 * because its chunk never reached the prompt looks, from the outside, identical to the
 * model failing to cite something that was right in front of it.
 *
 * <h2>A fabricated citation kills the whole generation</h2>
 *
 * <p>Not just the offending claim. A model that invents {@code chunk:99999} has
 * demonstrated it will assert a citation exists when it does not, and a generation that
 * did that once cannot be trusted to have gotten the citations it <i>didn't</i> get
 * caught on right either. Rejecting outright, rather than dropping only the bad claim,
 * treats a fabricated citation as the same class of failure as unparseable JSON: total,
 * not partial.
 */
@org.springframework.stereotype.Service
public class ClaimGenerator {

    private static final Logger log = LoggerFactory.getLogger(ClaimGenerator.class);

    private static final String PROMPT_NAME = "draft";

    /**
     * Context token budget, conservative against the model's real window. Approximate
     * (four characters per token — see {@code DocumentChunker.estimateTokens}), which is
     * fine for a pre-call budget decision; the figures billed come from the provider's
     * own count.
     */
    private static final int CONTEXT_TOKEN_BUDGET = 6000;

    private final ModelRouter models;

    public ClaimGenerator(ModelRouter models) {
        this.models = models;
    }

    /** Thrown when the model cites a chunk id that was never offered. */
    public static class FabricatedCitationException extends RuntimeException {
        public FabricatedCitationException(String chunkId) {
            super("Generation cited " + chunkId + ", which was not in the retrieved context");
        }
    }

    public record Generation(List<RawClaim> claims, DraftGeneration.Tone tone,
                             List<String> unresolvedAspects, String modelId,
                             int tokensIn, int tokensOut, long costMicros, long latencyMs) {
    }

    public Generation generate(Long tenantId, PromptVersion prompt, String redactedTicketText,
                               List<HybridResult> retrieved) {
        Budgeted budgeted = buildContext(retrieved);
        if (!budgeted.dropped().isEmpty()) {
            log.info("Dropped {} chunk(s) from draft context for tenant {} over the {}-token "
                     + "budget: {}", budgeted.dropped().size(), tenantId, CONTEXT_TOKEN_BUDGET,
                    budgeted.dropped());
        }

        String userText = "TICKET:\n" + redactedTicketText + "\n\nRETRIEVED PASSAGES:\n"
                + budgeted.contextText();

        LlmResult<DraftGeneration> result = models.call(tenantId, prompt, userText,
                DraftGeneration.class);
        DraftGeneration generation = result.value();

        for (RawClaim claim : generation.claims()) {
            for (String citationId : claim.citationIds()) {
                if (!budgeted.availableChunkIds().contains(citationId)) {
                    throw new FabricatedCitationException(citationId);
                }
            }
        }

        return new Generation(generation.claims(), generation.suggestedTone(),
                generation.unresolvedAspects(), result.modelId(), result.tokensIn(),
                result.tokensOut(), result.costMicros(), result.latencyMs());
    }

    private record Budgeted(String contextText, Set<String> availableChunkIds,
                            List<String> dropped) {
    }

    private Budgeted buildContext(List<HybridResult> retrieved) {
        StringBuilder context = new StringBuilder();
        Set<String> included = new java.util.LinkedHashSet<>();
        List<String> dropped = new java.util.ArrayList<>();
        int budgetUsed = 0;

        for (HybridResult chunk : retrieved) {
            String label = "chunk:" + chunk.chunkId();
            String entry = "[" + label + "] (" + chunk.documentTitle() + ", " + chunk.source()
                    + ")\n" + chunk.text() + "\n\n";
            int entryTokens = estimateTokens(entry);
            if (budgetUsed + entryTokens > CONTEXT_TOKEN_BUDGET) {
                dropped.add(label);
                continue;
            }
            context.append(entry);
            included.add(label);
            budgetUsed += entryTokens;
        }
        return new Budgeted(context.toString(), included, dropped);
    }

    private static int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }
}
