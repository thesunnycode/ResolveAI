package com.resolveai.drafting.eval;

import com.resolveai.drafting.eval.GroundingMetrics.LabelledClaim;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Computes and persists the GROUNDING suite's claim-level and response-level scores over
 * the hand-labelled set in {@code eval/grounding-cases.json}. Doc 15 Task 22.
 *
 * <p>See {@code grounding-cases.json}'s own header comment for what this dataset is and,
 * as importantly, what it is not: 34 claims labelled by hand against spans written by
 * hand, not 150 audited from a live model's output. The metric and the gate are the real
 * deliverable; the numbers describe this fixed dataset, not draft@1's live behaviour.
 */
@Service
public class GroundingEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(GroundingEvalRunner.class);
    private static final String SUITE = "GROUNDING";
    private static final String RESOURCE = "eval/grounding-cases.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GroundingEvalRunner(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record RunResult(Long runId, GroundingMetrics.Scores scores, boolean passed) {
    }

    public RunResult run() {
        List<LabelledClaim> claims = readClaims();
        GroundingMetrics.Scores scores = GroundingMetrics.score(claims);

        // No committed floor for this suite (doc 15 asks for the gap to be reported and
        // discussed, not gated) — REFUSAL is where Task 24 puts an absolute gate. This
        // run is recorded so GET /admin/eval/runs shows it alongside the others.
        boolean passed = true;
        Long runId = persist(scores);

        log.info("Grounding eval run {}: claim-level={} response-level={} "
                 + "(gap={}) citationPrecision={}",
                runId, scores.claimLevelGroundedness(), scores.responseLevelGroundedness(),
                round(scores.claimLevelGroundedness() - scores.responseLevelGroundedness()),
                scores.citationPrecision());
        return new RunResult(runId, scores, passed);
    }

    @SuppressWarnings("unchecked")
    private List<LabelledClaim> readClaims() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("cases");
            return raw.stream()
                    .map(c -> new LabelledClaim((String) c.get("caseId"),
                            (String) c.get("claimText"),
                            Boolean.TRUE.equals(c.get("citationSupportsClaim")),
                            (String) c.get("label")))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    @Transactional
    Long persist(GroundingMetrics.Scores scores) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalClaims", scores.totalClaims());
        metrics.put("claimLevelGroundedness", scores.claimLevelGroundedness());
        metrics.put("totalResponses", scores.totalResponses());
        metrics.put("responseLevelGroundedness", scores.responseLevelGroundedness());
        metrics.put("gap", round(scores.claimLevelGroundedness()
                - scores.responseLevelGroundedness()));
        metrics.put("citationPrecision", scores.citationPrecision());

        return jdbc.queryForObject("""
                INSERT INTO eval_run (suite, model_id, metrics, passed, finished_at)
                VALUES (?, 'hand-labelled', CAST(? AS jsonb), TRUE, NOW())
                RETURNING id
                """, Long.class, SUITE, objectMapper.writeValueAsString(metrics));
    }

    private static double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
