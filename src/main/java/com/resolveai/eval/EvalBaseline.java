package com.resolveai.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The committed floor the classification suite must clear.
 *
 * <h2>Why it is a file in the repository and not a constant or a database row</h2>
 *
 * <p>Because the diff is the point. When a prompt legitimately improves, the baseline is
 * raised <b>in the same commit</b>, so the pull request shows the gain as two changed
 * numbers next to the prompt that produced them. A constant buried in a test class gets
 * edited quietly; a database row has no history a reviewer will ever see.
 *
 * <p>It also makes the only dishonest move — lowering the bar to make the build green —
 * a visible, reviewable act rather than an invisible one. That does not prevent it. It
 * does mean somebody has to do it on purpose, in front of a reviewer.
 *
 * <p><b>A gate you have never seen fail is a gate you do not know works</b>, which is
 * why {@code ClassificationEvalTest} deliberately degrades the classifier and asserts
 * the run comes back failed.
 */
@Component
public class EvalBaseline {

    private static final String RESOURCE = "eval/classification-baseline.json";

    private final double minAccuracy;
    private final double minMacroF1;

    public EvalBaseline(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> values = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            this.minAccuracy = ((Number) values.get("minAccuracy")).doubleValue();
            this.minMacroF1 = ((Number) values.get("minMacroF1")).doubleValue();
        } catch (IOException e) {
            // Fail at startup rather than defaulting to zero. A baseline that silently
            // becomes "anything passes" is worse than no gate, because the build stays
            // green and everyone believes it means something.
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    public double minAccuracy() {
        return minAccuracy;
    }

    public double minMacroF1() {
        return minMacroF1;
    }

    /** Both floors must be cleared: a high accuracy hiding one dead category is a fail. */
    public boolean passes(EvalMetrics.Scores scores) {
        return scores.accuracy() >= minAccuracy && scores.macroF1() >= minMacroF1;
    }

    public String describe(EvalMetrics.Scores scores) {
        return "accuracy %.3f (floor %.3f), macroF1 %.3f (floor %.3f)"
                .formatted(scores.accuracy(), minAccuracy, scores.macroF1(), minMacroF1);
    }
}
