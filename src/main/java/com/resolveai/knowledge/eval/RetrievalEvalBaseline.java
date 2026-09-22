package com.resolveai.knowledge.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The committed floor for the RETRIEVAL suite's overall Recall@5 and MRR.
 *
 * <p>Same pattern as {@code EvalBaseline}: a file in the repository so a legitimate
 * improvement raises the floor in the same commit that earned it, and a lowered floor is
 * a visible, reviewable act rather than an invisible one.
 */
@Component
public class RetrievalEvalBaseline {

    private static final String RESOURCE = "eval/retrieval-baseline.json";

    private final double minRecallAt5;
    private final double minMrr;

    public RetrievalEvalBaseline(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> values = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            this.minRecallAt5 = ((Number) values.get("minRecallAt5")).doubleValue();
            this.minMrr = ((Number) values.get("minMrr")).doubleValue();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    public double minRecallAt5() {
        return minRecallAt5;
    }

    public double minMrr() {
        return minMrr;
    }

    public boolean passes(RetrievalMetrics.GroupScores overall) {
        return overall.recallAt5() >= minRecallAt5 && overall.mrr() >= minMrr;
    }
}
