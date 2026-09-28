package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.platform.ai.model.TriageSignals.Severity;
import com.resolveai.platform.ai.model.TriageSignals.Urgency;
import com.resolveai.ticketing.domain.Category;
import com.resolveai.ticketing.domain.Priority;
import com.resolveai.triage.policy.PolicyInputs;
import com.resolveai.triage.policy.PriorityPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The sentence an agent reads in the "why this priority" popover. */
class PriorityRationaleWordingTest {

    private final PriorityPolicy policy = new PriorityPolicy();

    private String explain(Severity impact, boolean down, boolean payment, PlanTier plan, int reopens) {
        TriageSignals s = new TriageSignals(Category.PAYMENT, impact, down, false, payment,
                Urgency.MEDIUM, Map.of(), 0.9);
        return policy.evaluate(new PolicyInputs(s, plan, null, reopens)).humanReadable();
    }

    @Test
    void showsHowTheLevelMovedStepByStep() {
        assertThat(explain(Severity.SINGLE_USER, true, false, PlanTier.ENTERPRISE, 0))
                .isEqualTo("P1: starts at P3 because it affects one person; "
                        + "raised to P2 because they say something is completely unusable; "
                        + "raised to P1 because the account is on the Enterprise plan.");
    }

    @Test
    void aDowngradeReadsAsADowngrade() {
        assertThat(explain(Severity.TEAM, false, false, PlanTier.FREE, 0))
                .isEqualTo("P3: starts at P2 because the customer says a team is affected; "
                        + "lowered to P3 because the account is on the Free plan.");
    }

    @Test
    void aBumpPastTheTopIsNotClaimedAsARaise() {
        String s = explain(Severity.ORG_WIDE, true, true, PlanTier.PRO, 0);
        assertThat(s).isEqualTo("P1: starts at P1 because the customer says their whole organisation is affected."
                + " Already at P1, so these made no difference: they say something is completely unusable"
                + " and money is involved.");
        assertThat(policy.evaluate(new PolicyInputs(new TriageSignals(Category.PAYMENT, Severity.ORG_WIDE,
                true, true, true, Urgency.HIGH, Map.of(), 0.9), PlanTier.PRO, null, 0)).priority())
                .isEqualTo(Priority.P1);
    }
}
