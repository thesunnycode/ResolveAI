package com.resolveai.drafting.eval;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records the REFUSAL suite's outcome. Doc 15 Task 23–24.
 *
 * <p>The suite itself is {@code RefusalSuiteTest} — a real integration test driving 20
 * draft requests through the actual HTTP endpoint and {@code DraftWorker}, not a
 * JSON-driven suite scored against a fixture, because the property under test ("does
 * suppression actually fire, every time, end to end") is not something a static label
 * comparison can prove. This class exists only so that run's outcome lands in
 * {@code eval_run} and shows up next to the other four suites in
 * {@code GET /admin/eval/runs}.
 *
 * <p><b>The gate is absolute, not a floor.</b> Every other suite here gates on regression
 * against a committed baseline; this one gates on {@code passedCount == totalCount}. A
 * system that confidently answers even one question it had no evidence for has failed in
 * a way a 95% pass rate does not capture.
 */
@Service
public class RefusalEvalRunner {

    private static final String SUITE = "REFUSAL";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RefusalEvalRunner(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long record(int totalCases, int suppressedCorrectly, int nearMissCases,
                       int nearMissShownCorrectly) {
        boolean passed = suppressedCorrectly == totalCases
                && nearMissShownCorrectly == nearMissCases;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalCases", totalCases);
        metrics.put("suppressedCorrectly", suppressedCorrectly);
        metrics.put("suppressionRate", totalCases == 0 ? 0
                : Math.round((double) suppressedCorrectly / totalCases * 1000d) / 1000d);
        metrics.put("nearMissCases", nearMissCases);
        metrics.put("nearMissShownCorrectly", nearMissShownCorrectly);

        return jdbc.queryForObject("""
                INSERT INTO eval_run (suite, model_id, metrics, passed, finished_at)
                VALUES (?, 'stub-driven-integration', CAST(? AS jsonb), ?, NOW())
                RETURNING id
                """, Long.class, SUITE, objectMapper.writeValueAsString(metrics), passed);
    }
}
