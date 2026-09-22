package com.resolveai.incidents.service;

import com.resolveai.platform.ai.model.IncidentTitleSignals;
import com.resolveai.platform.ai.model.LlmExceptions.BudgetExhaustedException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import com.resolveai.platform.outbox.NonRetryableException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes an incident's title and summary — the model's <i>entire</i> contribution to
 * incident correlation.
 *
 * <h2>This is the clearest expression of the project's architecture</h2>
 *
 * <p>{@code CorrelationGate} already decided an incident exists, deterministically, before
 * this class is ever called. When the model is unavailable, incidents are still detected,
 * still gated, still proposed, still confirmable, still fan-out-able — the product degrades
 * by exactly one cosmetic field. <b>The fallback below is not a nicety; it is the
 * demonstration that the model has no vote.</b>
 */
@Component
public class IncidentTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(IncidentTitleGenerator.class);
    private static final String PROMPT_NAME = "incident_title";

    private final PromptVersionRepository prompts;
    private final ModelRouter models;

    public IncidentTitleGenerator(PromptVersionRepository prompts, ModelRouter models) {
        this.prompts = prompts;
        this.models = models;
    }

    /**
     * @param title            under 80 characters
     * @param summary          two sentences, or null for a templated result
     * @param generatedByModel null means templated — see {@code Incident}'s class comment
     * @param promptVersionId  null when templated, since no prompt was actually run
     */
    public record TitleResult(String title, String summary, String generatedByModel,
                              Long promptVersionId) {
        static TitleResult templated(String title) {
            return new TitleResult(title, null, null, null);
        }
    }

    public TitleResult generate(Long tenantId, List<String> representativeSubjects,
                                Set<String> sharedEntityLabels, int clusterSize) {
        try {
            PromptVersion prompt = prompts.findByNameAndActiveTrue(PROMPT_NAME)
                    .orElseThrow(() -> new NonRetryableException(
                            "No active prompt named " + PROMPT_NAME
                            + "; V14 should have seeded one"));
            String userText = buildUserText(representativeSubjects, sharedEntityLabels);
            LlmResult<IncidentTitleSignals> result =
                    models.call(tenantId, prompt, userText, IncidentTitleSignals.class);
            IncidentTitleSignals signals = result.value();
            return new TitleResult(truncate(signals.title(), 200), signals.summary(),
                    result.modelId(), prompt.getId());
        } catch (LlmUnavailableException | BudgetExhaustedException | LlmParseException e) {
            log.info("Tenant {} could not generate an incident title ({}); using the "
                     + "template fallback", tenantId, e.getClass().getSimpleName());
            return TitleResult.templated(templatedTitle(clusterSize, sharedEntityLabels));
        }
    }

    private static String buildUserText(List<String> subjects, Set<String> sharedEntityLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append("TICKET SUBJECTS (up to 5, representative of the whole cluster):\n");
        subjects.stream().limit(5).forEach(s -> sb.append("- ").append(s).append('\n'));
        sb.append("\nSHARED ENTITIES:\n");
        if (sharedEntityLabels.isEmpty()) {
            sb.append("(none extracted)\n");
        } else {
            sharedEntityLabels.forEach(e -> sb.append("- ").append(e).append('\n'));
        }
        return sb.toString();
    }

    /**
     * {@code "38 related tickets — payment-service"}. Deliberately plain: it names the
     * size and the single most informative shared fact, and nothing it did not measure.
     */
    private static String templatedTitle(int clusterSize, Set<String> sharedEntityLabels) {
        String topEntity = sharedEntityLabels.stream().findFirst()
                .map(e -> e.contains(":") ? e.substring(e.indexOf(':') + 1) : e)
                .orElse("multiple tickets");
        return "%d related tickets — %s".formatted(clusterSize, topEntity);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
