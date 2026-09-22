package com.resolveai.drafting;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.IntegrationTestBase;
import com.resolveai.drafting.eval.GroundingEvalRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises the GROUNDING suite's harness against the hand-labelled dataset — see
 * {@code eval/grounding-cases.json} and {@code GroundingEvalRunner} for what this
 * dataset is and is not.
 */
class GroundingEvalTest extends IntegrationTestBase {

    @Autowired GroundingEvalRunner runner;

    @Test
    @DisplayName("the suite runs, persists, and reports claim-level below response-level")
    void suiteRunsAndReportsTheGap() {
        GroundingEvalRunner.RunResult result = runner.run();

        assertThat(result.runId()).isNotNull();
        assertThat(result.scores().totalClaims()).isEqualTo(34);
        assertThat(result.scores().totalResponses()).isEqualTo(14);

        // The finding Task 22 exists to demonstrate: claim-level groundedness is a
        // stricter (lower) number than response-level, because a response with three
        // supported claims and one fabricated one still fails the RESPONSE-level bar
        // ("every claim supported") while contributing three correct data points to the
        // CLAIM-level one.
        assertThat(result.scores().claimLevelGroundedness())
                .isGreaterThan(result.scores().responseLevelGroundedness());
    }
}
