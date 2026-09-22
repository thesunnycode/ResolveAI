package com.resolveai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The classification gate: sixty labelled cases, scored, persisted, and enforced.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <p><b>Proves:</b> the suite runs end to end through the production path — policy
 * gate, redaction, model router, cost accounting — scores itself with accuracy and
 * macro-F1, writes a run and sixty results, and <b>fails when the classifier gets
 * worse</b>. That last one is demonstrated rather than asserted in the abstract: one
 * test deliberately degrades the classifier and checks the run comes back failed,
 * because a gate nobody has ever watched fail is a gate nobody knows works.
 *
 * <p><b>Does not prove:</b> that the prompt is good. The provider is a stub, so the
 * accuracy of a "correct" run is 1.0 by construction. The number that means something
 * comes from a run against a live model; this is the machinery that makes that number
 * comparable between runs and enforceable in CI. Saying otherwise would be the exact
 * kind of self-congratulatory metric the eval suite exists to replace.
 */
class ClassificationEvalTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired EvalCaseRepository cases;
    @Autowired EvalRunner runner;
    @Autowired EvalBaseline baseline;
    @Autowired AiPolicyService policies;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private List<Map<String, Object>> fixtures;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenantId = auth.seedTenant("eval").tenantId();
        policies.ensureExists(tenantId);
        policies.update(tenantId, true, List.of("openai"), true, 1_000_000_000L, 30);
        policies.evict(tenantId);

        jdbc.update("DELETE FROM eval_result");
        jdbc.update("DELETE FROM eval_run");
        jdbc.update("DELETE FROM eval_case");
        cases.seed();
        fixtures = cases.readResource();

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        // Sixty consecutive 503s in the provider-failure test open the breaker, and it
        // stays open into the next test - which then scores 0.0 for a reason that has
        // nothing to do with what it is testing. That is the breaker working; resetting
        // per test keeps each one about its own subject.
        breakers.circuitBreaker("openai").reset();
    }

    @Test
    @DisplayName("The suite is sixty hand-labelled cases spread across all eight categories")
    void theSuiteIsBalanced() {
        List<EvalCase> suite = cases.active("CLASSIFICATION");

        assertThat(suite).hasSize(60);
        // Balanced on purpose. Mirroring the corpus would make the suite 25% PAYMENT,
        // and a classifier that is excellent at payments and hopeless at onboarding
        // would score well while routing every onboarding ticket to the wrong team.
        Map<String, Long> byCategory = new LinkedHashMap<>();
        suite.forEach(c -> byCategory.merge(c.expectedCategory(), 1L, Long::sum));
        assertThat(byCategory).hasSize(8);
        assertThat(byCategory.values()).allSatisfy(count ->
                assertThat(count).isBetween(7L, 8L));
    }

    @Test
    @DisplayName("Seeding twice changes nothing")
    void seedingIsIdempotent() {
        cases.seed();
        cases.seed();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eval_case", Integer.class))
                .isEqualTo(60);
    }

    @Test
    @DisplayName("A correct classifier clears the gate, and the run is recorded in full")
    void aGoodRunPassesAndIsPersisted() {
        LlmStub.classifiesPerCase(answers(0));

        EvalRunner.RunResult result = runner.run(tenantId);

        assertThat(result.passed())
                .as(baseline.describe(result.scores()))
                .isTrue();
        assertThat(result.scores().total()).isEqualTo(60);
        assertThat(result.scores().accuracy()).isEqualTo(1.0);
        assertThat(result.scores().macroF1()).isEqualTo(1.0);
        assertThat(result.scores().perCategory()).hasSize(8);

        // Persisted, with one result row per case, so a run is investigable later
        // rather than being a number in a log line.
        assertThat(jdbc.queryForObject(
                "SELECT passed FROM eval_run WHERE id = ?", Boolean.class, result.runId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM eval_result WHERE eval_run_id = ?", Integer.class,
                result.runId())).isEqualTo(60);

        // The run went through the production path, so it was charged for.
        assertThat(result.totalCostMicros())
                .as("an eval run costs real money and is accounted for")
                .isPositive();
    }

    /**
     * <b>The gate, watched failing.</b>
     *
     * <p>Twenty of the sixty cases are deliberately mislabelled by the stub, which puts
     * accuracy at ~0.67 against a floor of 0.9. If this test ever goes green, the gate
     * has stopped gating and every passing run above it means nothing.
     */
    @Test
    @DisplayName("A degraded classifier fails the gate")
    void aBadRunFailsTheGate() {
        LlmStub.classifiesPerCase(answers(20));

        EvalRunner.RunResult result = runner.run(tenantId);

        assertThat(result.scores().accuracy()).isLessThan(baseline.minAccuracy());
        assertThat(result.passed())
                .as("the build must go red on %s", baseline.describe(result.scores()))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT passed FROM eval_run WHERE id = ?", Boolean.class, result.runId()))
                .as("and the failure is recorded, not just thrown")
                .isFalse();
    }

    @Test
    @DisplayName("Macro-F1 catches one dead category that accuracy would hide")
    void macroF1SeesWhatAccuracyMisses() {
        // Every ONBOARDING case answered as PAYMENT: seven of sixty wrong, so accuracy
        // stays a comfortable 0.88 - while an entire category is being routed to the
        // wrong team, every single time.
        Map<String, String> answers = new LinkedHashMap<>();
        for (Map<String, Object> fixture : fixtures) {
            String expected = (String) fixture.get("expectedCategory");
            answers.put(marker(fixture), "ONBOARDING".equals(expected) ? "PAYMENT" : expected);
        }
        LlmStub.classifiesPerCase(answers);

        EvalRunner.RunResult result = runner.run(tenantId);

        assertThat(result.scores().accuracy()).isGreaterThan(0.85);
        assertThat(result.scores().perCategory().get("ONBOARDING").recall())
                .as("the category is completely dead")
                .isZero();
        // Macro-F1 weights every category equally, so one dead class drags it down
        // whatever its size. That is the whole reason it is the second gate.
        assertThat(result.scores().macroF1()).isLessThan(result.scores().accuracy());
    }

    @Test
    @DisplayName("A provider failure counts as wrong, not as absent")
    void failuresAreNotQuietlyDroppedFromTheDenominator() {
        // No per-case stubs at all: every call 503s.
        LlmStub.fails(503);

        EvalRunner.RunResult result = runner.run(tenantId);

        // Sixty cases attempted, sixty scored, none correct. Skipping the failures
        // would let a prompt that makes the model fall over on hard inputs score better
        // than one that answers them badly.
        assertThat(result.scores().total()).isEqualTo(60);
        assertThat(result.scores().correct()).isZero();
        assertThat(result.passed()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM eval_result
                 WHERE eval_run_id = ? AND actual->>'failure' IS NOT NULL
                """, Integer.class, result.runId()))
                .as("and each one records why, because 'wrong' and 'unreachable' are "
                    + "different problems with the same score")
                .isEqualTo(60);
    }

    @Test
    @DisplayName("GET /admin/eval/runs lists accuracy by prompt version, admin only")
    @SuppressWarnings("unchecked")
    void runsAreVisibleToAdmins() {
        LlmStub.classifiesPerCase(answers(0));
        runner.run(tenantId);

        String adminToken = auth.accessToken(rest, "eval", "admin");
        List<Map<String, Object>> runs = rest.exchange("/api/v1/admin/eval/runs",
                HttpMethod.GET, new HttpEntity<>(AuthTestSupport.bearer(adminToken)),
                List.class).getBody();

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("promptVersion")).isEqualTo("triage@1");
        assertThat(runs.get(0).get("passed")).isEqualTo(true);
        Map<String, Object> metrics = (Map<String, Object>) runs.get(0).get("metrics");
        assertThat(metrics).containsKeys("accuracy", "macroF1", "perCategory", "costMicros");

        // An agent has no business reading the eval history: it is a statement about
        // the platform, not about their tickets.
        String agentToken = auth.accessToken(rest, "eval", "agent");
        assertThat(rest.exchange("/api/v1/admin/eval/runs", HttpMethod.GET,
                new HttpEntity<>(AuthTestSupport.bearer(agentToken)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Correct answers, with the first {@code wrong} cases deliberately mislabelled. */
    private Map<String, String> answers(int wrong) {
        Map<String, String> answers = new LinkedHashMap<>();
        int mislabelled = 0;
        for (Map<String, Object> fixture : fixtures) {
            String expected = (String) fixture.get("expectedCategory");
            String answer = expected;
            if (mislabelled < wrong) {
                answer = "PAYMENT".equals(expected) ? "BILLING" : "PAYMENT";
                mislabelled++;
            }
            answers.put(marker(fixture), answer);
        }
        return answers;
    }

    /** The {@code EVALCASE###} token the stub matches on. */
    private static String marker(Map<String, Object> fixture) {
        return "EVALCASE" + ((String) fixture.get("name")).substring("CLS-".length());
    }
}
