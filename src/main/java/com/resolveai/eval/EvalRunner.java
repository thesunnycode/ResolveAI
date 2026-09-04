package com.resolveai.eval;

import com.resolveai.platform.ai.model.LlmResult;
import com.resolveai.platform.ai.model.ModelRouter;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.platform.ai.pii.PiiRedactor;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the classification suite and writes down what happened.
 *
 * <h2>The answer to "how do you know your AI works?"</h2>
 *
 * <p>Without this the AI half of the project has no evidence behind it — only the claim
 * that the prompt is good, which is indistinguishable from the claim that the prompt was
 * tuned until the three tickets someone tried by hand came out right. Sixty labelled
 * cases, scored the same way every time, persisted with the prompt version that produced
 * them, is what turns "it seems to work" into a number that can go up or down.
 *
 * <p><b>It is also the only way a prompt change can be shown to be an improvement.</b>
 * A prompt edit always looks better on the example that motivated it; the question is
 * what it did to the other fifty-nine, and there is no way to answer that by reading.
 *
 * <h2>Why it goes through {@code ModelRouter} rather than calling the model directly</h2>
 *
 * <p>Because an eval that bypasses the production path measures something the product
 * does not do. The policy gate, the redaction, the budget accounting and the cost
 * recording all apply here exactly as they do in triage — which also means <b>a run
 * costs real money and is charged to the tenant's budget</b>, deliberately, because an
 * eval suite whose cost is invisible is an eval suite that gets run in a loop by
 * accident.
 *
 * <p>The one thing it does not do is write anything to a ticket. There is no ticket:
 * {@code redactWithoutMapping} is used because an eval case has nothing to rehydrate
 * for and storing a PII mapping against no ticket would be personal data kept for no
 * reason.
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final String SUITE = "CLASSIFICATION";
    private static final String PROMPT_NAME = "triage";

    private final EvalCaseRepository cases;
    private final PromptVersionRepository prompts;
    private final PiiRedactor redactor;
    private final ModelRouter models;
    private final EvalBaseline baseline;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EvalRunner(EvalCaseRepository cases, PromptVersionRepository prompts,
                      PiiRedactor redactor, ModelRouter models, EvalBaseline baseline,
                      JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.cases = cases;
        this.prompts = prompts;
        this.redactor = redactor;
        this.models = models;
        this.baseline = baseline;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** What one run produced, in memory, for a caller that wants to assert on it. */
    public record RunResult(Long runId, EvalMetrics.Scores scores, boolean passed,
                            long totalCostMicros,
                            List<EvalMetrics.Prediction> predictions) {
    }

    /**
     * Runs every active case against the active prompt.
     *
     * <p><b>Not transactional across the calls</b>, and for the usual reason: sixty
     * model calls is minutes of network, and a transaction open across it would hold a
     * connection for the duration. The run row is written at the end, in one short
     * transaction, together with its results.
     */
    public RunResult run(Long tenantId) {
        PromptVersion prompt = prompts.findByNameAndActiveTrue(PROMPT_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "No active prompt named " + PROMPT_NAME));

        List<EvalCase> suite = cases.active(SUITE);
        if (suite.isEmpty()) {
            throw new IllegalStateException(
                    "No active " + SUITE + " cases; has EvalCaseRepository.seed() run?");
        }

        List<EvalMetrics.Prediction> predictions = new ArrayList<>(suite.size());
        List<CaseOutcome> outcomes = new ArrayList<>(suite.size());
        long costMicros = 0;

        for (EvalCase evalCase : suite) {
            String redacted = redactor.redactWithoutMapping(tenantId, evalCase.text())
                    .redactedText();
            String actual = null;
            String failure = null;
            try {
                LlmResult<TriageSignals> result =
                        models.call(tenantId, prompt, redacted, TriageSignals.class);
                actual = result.value().category().name();
                costMicros += result.costMicros();
            } catch (RuntimeException e) {
                // Counted as wrong, not skipped. A prompt that makes the model fall over
                // on the hard cases must not score better than one that answers them
                // badly, and dropping them from the denominator is exactly how it would.
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("Eval case {} failed: {}", evalCase.name(), failure);
            }
            predictions.add(new EvalMetrics.Prediction(
                    evalCase.name(), evalCase.expectedCategory(), actual));
            outcomes.add(new CaseOutcome(evalCase, actual, failure));
        }

        EvalMetrics.Scores scores = EvalMetrics.score(predictions);
        boolean passed = baseline.passes(scores);
        Long runId = persist(prompt, scores, passed, costMicros, outcomes);

        log.info("Eval run {} on {}: {} — {}", runId, prompt.label(),
                passed ? "PASS" : "FAIL", baseline.describe(scores));
        return new RunResult(runId, scores, passed, costMicros, predictions);
    }

    /**
     * The run and every case result, in one transaction.
     *
     * <p>A run row without its results is a number nobody can investigate, and a set of
     * results with no run is orphaned. They commit together.
     */
    @Transactional
    Long persist(PromptVersion prompt, EvalMetrics.Scores scores, boolean passed,
                 long costMicros, List<CaseOutcome> outcomes) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total", scores.total());
        metrics.put("correct", scores.correct());
        metrics.put("accuracy", scores.accuracy());
        metrics.put("macroF1", scores.macroF1());
        metrics.put("costMicros", costMicros);
        metrics.put("minAccuracy", baseline.minAccuracy());
        metrics.put("minMacroF1", baseline.minMacroF1());
        metrics.put("perCategory", scores.perCategory());

        Long runId = jdbc.queryForObject("""
                INSERT INTO eval_run (suite, prompt_version_id, model_id, metrics, passed,
                                      finished_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, NOW())
                RETURNING id
                """, Long.class, SUITE, prompt.getId(), prompt.getModelId(),
                objectMapper.writeValueAsString(metrics), passed);

        for (CaseOutcome outcome : outcomes) {
            jdbc.update("""
                    INSERT INTO eval_result (eval_run_id, eval_case_id, metric, expected,
                                             actual, passed)
                    VALUES (?, ?, 'category', CAST(? AS jsonb), CAST(? AS jsonb), ?)
                    """, runId, outcome.evalCase().id(),
                    objectMapper.writeValueAsString(
                            Map.of("category", outcome.evalCase().expectedCategory())),
                    objectMapper.writeValueAsString(actualPayload(outcome)),
                    outcome.evalCase().expectedCategory().equals(outcome.actual()));
        }
        return runId;
    }

    private static Map<String, Object> actualPayload(CaseOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", outcome.actual());
        if (outcome.failure() != null) {
            // Kept, because "the model said BILLING" and "the model could not be reached"
            // are the same zero in the score and completely different problems.
            payload.put("failure", outcome.failure());
        }
        return payload;
    }

    /** @param failure non-null when the call threw rather than answering wrongly. */
    record CaseOutcome(EvalCase evalCase, String actual, String failure) {
    }
}
