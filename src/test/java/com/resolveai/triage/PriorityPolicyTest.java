package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.testing.Property;
import com.resolveai.ticketing.domain.Category;
import com.resolveai.ticketing.domain.Priority;
import com.resolveai.triage.policy.PolicyInputs;
import com.resolveai.triage.policy.PriorityDecision;
import com.resolveai.triage.policy.PriorityPolicy;
import com.resolveai.triage.policy.RuleTrace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * <b>The answer to "so the LLM decides the priority?"</b>
 *
 * <p>No — and here are thirty-eight cases and a thousand random ones proving the decision
 * is a pure function that runs without a model, without Spring and without a database, in
 * a handful of milliseconds. Every row below is a claim about behaviour that would
 * otherwise be a claim about a prompt, checkable only by spending money and hoping.
 *
 * <p>There is no {@code @SpringBootTest} here and there must never be one. The moment
 * this test needs a context it stops being the cheap, exhaustive check that makes
 * changing the policy safe, and the rules start being tuned by feel again.
 */
class PriorityPolicyTest {

    private final PriorityPolicy policy = new PriorityPolicy();

    /**
     * The table.
     *
     * <p>Columns: impact, serviceDown, dataLoss, paymentAffected, plan, linked incident
     * (NONE for none), reopenCount, expected priority.
     */
    @ParameterizedTest(name = "[{index}] {0} down={1} loss={2} pay={3} {4} inc={5} reopen={6} -> {7}")
    @CsvSource({
            // ── each impact level alone, on PRO, nothing else set ──────────
            "ORG_WIDE,    false, false, false, PRO,        NONE, 0, P1",
            "TEAM,        false, false, false, PRO,        NONE, 0, P2",
            "SINGLE_USER, false, false, false, PRO,        NONE, 0, P3",

            // ── each bump in isolation, from the SINGLE_USER/PRO baseline ──
            "SINGLE_USER, true,  false, false, PRO,        NONE, 0, P2",
            "SINGLE_USER, false, true,  false, PRO,        NONE, 0, P1",
            "SINGLE_USER, false, false, true,  PRO,        NONE, 0, P2",
            "SINGLE_USER, false, false, false, ENTERPRISE, NONE, 0, P2",
            "SINGLE_USER, false, false, false, FREE,       NONE, 0, P4",
            "SINGLE_USER, false, false, false, PRO,        NONE, 2, P2",

            // ── bumps in combination ───────────────────────────────────────
            "SINGLE_USER, true,  false, true,  PRO,        NONE, 0, P1",
            "SINGLE_USER, false, false, true,  ENTERPRISE, NONE, 0, P1",
            "SINGLE_USER, true,  false, false, ENTERPRISE, NONE, 0, P1",
            "TEAM,        false, false, true,  PRO,        NONE, 0, P1",
            "TEAM,        true,  false, false, FREE,       NONE, 0, P2",
            "TEAM,        false, false, false, FREE,       NONE, 0, P3",
            "TEAM,        false, false, true,  FREE,       NONE, 0, P2",
            // The headline case from the plan: an ENTERPRISE payment outage.
            "ORG_WIDE,    true,  false, true,  ENTERPRISE, NONE, 0, P1",

            // ── data loss is absolute, not a bump ──────────────────────────
            // It fires after the impact base and before everything else, so a FREE
            // single-user data-loss ticket lands at P2, not P1: the plan still lowers it.
            "SINGLE_USER, false, true,  false, FREE,       NONE, 0, P2",
            "SINGLE_USER, false, true,  false, ENTERPRISE, NONE, 0, P1",
            "SINGLE_USER, false, true,  true,  PRO,        NONE, 0, P1",
            // Data loss overrides a *lower* base rather than stacking with it.
            "SINGLE_USER, true,  true,  false, PRO,        NONE, 0, P1",

            // ── clamping at both ends ──────────────────────────────────────
            "ORG_WIDE,    true,  false, true,  ENTERPRISE, NONE, 3, P1",
            "SINGLE_USER, false, false, false, FREE,       NONE, 0, P4",
            // FREE cannot push a ticket below P4 however weak the signals.
            "SINGLE_USER, false, false, false, FREE,       NONE, 1, P4",

            // ── FREE tier downgrade, and what still beats it ───────────────
            "ORG_WIDE,    false, false, false, FREE,       NONE, 0, P2",
            // Saturation matters here: ORG_WIDE + serviceDown is already P1 before the
            // plan is consulted, so FREE lowers it to P2 rather than being absorbed.
            "ORG_WIDE,    true,  false, false, FREE,       NONE, 0, P2",
            "TEAM,        true,  false, true,  FREE,       NONE, 0, P2",

            // ── reopenCount: 1 does nothing, 2 bumps, 5 bumps once ─────────
            "SINGLE_USER, false, false, false, PRO,        NONE, 1, P3",
            "SINGLE_USER, false, false, false, PRO,        NONE, 2, P2",
            "SINGLE_USER, false, false, false, PRO,        NONE, 5, P2",
            "TEAM,        false, false, false, PRO,        NONE, 2, P1",

            // ── incident inheritance overrides everything else ─────────────
            // Signals that would otherwise give P1; the incident says P4, so P4 it is.
            "ORG_WIDE,    true,  true,  true,  ENTERPRISE, P4,   9, P4",
            // Signals that would otherwise give P4; the incident says P1.
            "SINGLE_USER, false, false, false, FREE,       P1,   0, P1",
            "SINGLE_USER, false, false, false, PRO,        P2,   0, P2",
            "TEAM,        true,  false, true,  PRO,        P3,   4, P3",

            // ── a few realistic whole tickets ──────────────────────────────
            "ORG_WIDE,    true,  false, false, PRO,        NONE, 0, P1",
            "TEAM,        false, false, false, ENTERPRISE, NONE, 0, P1",
            "SINGLE_USER, true,  false, true,  FREE,       NONE, 0, P2",
    })
    void table(String impact, boolean serviceDown, boolean dataLoss, boolean payment,
               PlanTier plan, String incident, int reopenCount, Priority expected) {
        PriorityDecision decision = policy.evaluate(new PolicyInputs(
                signals(impact, serviceDown, dataLoss, payment, "MEDIUM"),
                plan,
                "NONE".equals(incident) ? null : Priority.valueOf(incident),
                reopenCount));

        assertThat(decision.priority()).isEqualTo(expected);
    }

    /**
     * The property that makes the table safe to extend: whatever goes in, a valid
     * priority comes out.
     *
     * <p>A table can only assert about the rows somebody thought of. This asserts about
     * the 1,000 combinations nobody did — and it is the check that would catch a future
     * rule that does its own arithmetic and forgets to saturate, which is precisely the
     * mistake {@code CLAMP} exists for and precisely the one that produces a {@code P0}
     * or {@code P5} and an {@code IllegalArgumentException} three layers away.
     */
    @Test
    @DisplayName("Any combination of inputs yields a priority within P1-P4")
    void alwaysInRange() {
        List<TriageSignals.Severity> impacts = List.of(TriageSignals.Severity.values());
        List<TriageSignals.Urgency> urgencies = List.of(TriageSignals.Urgency.values());
        List<PlanTier> plans = List.of(PlanTier.values());
        List<Priority> incidents = List.of(Priority.P1, Priority.P2, Priority.P3, Priority.P4);

        Property.forAll("priority is always P1-P4", 42L,
                random -> new PolicyInputs(
                        new TriageSignals(
                                Category.values()[random.nextInt(Category.values().length)],
                                impacts.get(random.nextInt(impacts.size())),
                                random.nextBoolean(),
                                random.nextBoolean(),
                                random.nextBoolean(),
                                urgencies.get(random.nextInt(urgencies.size())),
                                Map.of(),
                                random.nextDouble()),
                        plans.get(random.nextInt(plans.size())),
                        random.nextInt(5) == 0
                                ? incidents.get(random.nextInt(incidents.size())) : null,
                        random.nextInt(8)),
                // Shrinking towards zero reopens is enough to make a counterexample
                // readable; the enums have no natural ordering to shrink along.
                sample -> sample.reopenCount() == 0 ? List.of()
                        : List.of(new PolicyInputs(sample.signals(), sample.planTier(),
                                sample.linkedIncidentPriority(), 0)),
                sample -> {
                    PriorityDecision decision = policy.evaluate(sample);
                    assertThat(decision.priority()).isIn(
                            Priority.P1, Priority.P2, Priority.P3, Priority.P4);
                    assertThat(decision.policyVersion()).isEqualTo("v1");
                    // Constant-length trace: every rule reports, matched or not.
                    assertThat(decision.rules()).hasSize(8);
                });
    }

    @Test
    @DisplayName("An ENTERPRISE payment outage is P1 with the rules that produced it")
    void tracesTheHeadlineCase() {
        PriorityDecision decision = policy.evaluate(new PolicyInputs(
                signals("SINGLE_USER", true, false, true, "HIGH"),
                PlanTier.ENTERPRISE, null, 0));

        assertThat(decision.priority()).isEqualTo(Priority.P1);
        assertThat(decision.matchedRules()).extracting(RuleTrace::rule).containsExactly(
                PriorityPolicy.BASE_FROM_IMPACT,
                PriorityPolicy.SERVICE_DOWN_BUMP,
                PriorityPolicy.PAYMENT_BUMP,
                PriorityPolicy.PLAN_TIER_BUMP);
        assertThat(decision.humanReadable())
                .startsWith("P1: starts at P3 because")
                .contains("completely unusable")
                .contains("Enterprise plan");
    }

    /**
     * The rule that would be most tempting to weight, and must not be.
     *
     * <p>A furious message about a cosmetic bug and a calm one about the same bug are the
     * same bug. Weighting {@code linguisticUrgency} would make the queue reward shouting
     * — and tone correlates with things a support queue must not sort on.
     */
    @Test
    @DisplayName("linguisticUrgency changes nothing about the priority")
    void toneDoesNotDecide() {
        for (String urgency : List.of("LOW", "MEDIUM", "HIGH")) {
            PriorityDecision decision = policy.evaluate(new PolicyInputs(
                    signals("SINGLE_USER", false, false, false, urgency),
                    PlanTier.PRO, null, 0));
            assertThat(decision.priority())
                    .as("urgency %s must not move the priority", urgency)
                    .isEqualTo(Priority.P3);
        }
    }

    @Test
    @DisplayName("Unmatched rules are recorded with the reason they did not fire")
    void recordsNearMisses() {
        PriorityDecision decision = policy.evaluate(new PolicyInputs(
                signals("SINGLE_USER", false, false, false, "LOW"), PlanTier.PRO, null, 1));

        RuleTrace planTier = ruleNamed(decision, PriorityPolicy.PLAN_TIER_BUMP);
        assertThat(planTier.matched()).isFalse();
        assertThat(planTier.note()).contains("Enterprise would");

        RuleTrace reopen = ruleNamed(decision, PriorityPolicy.REOPEN_BUMP);
        assertThat(reopen.matched()).isFalse();
        // The near-miss is the useful part: one reopen, threshold two.
        assertThat(reopen.note()).contains("reopenCount >= 2").contains("has 1");
    }

    @Test
    @DisplayName("An inherited priority marks the later rules as not evaluated")
    void inheritanceSuppressesRatherThanSilentlySkips() {
        PriorityDecision decision = policy.evaluate(new PolicyInputs(
                signals("SINGLE_USER", false, false, false, "LOW"), PlanTier.PRO,
                Priority.P1, 4));

        assertThat(decision.priority()).isEqualTo(Priority.P1);
        RuleTrace reopen = ruleNamed(decision, PriorityPolicy.REOPEN_BUMP);
        assertThat(reopen.matched()).isFalse();
        // Not "this ticket was not reopened" — it was, four times. Saying so would be a
        // lie in the rationale an agent reads.
        assertThat(reopen.note()).contains("not evaluated");
    }

    @Test
    @DisplayName("An untriaged incident cannot lend a priority")
    void untriagedIncidentIsARejectedInput() {
        assertThatThrownBy(() -> policy.evaluate(new PolicyInputs(
                signals("TEAM", false, false, false, "LOW"), PlanTier.PRO,
                Priority.UNTRIAGED, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNTRIAGED");
    }

    @Test
    @DisplayName("Evaluation is pure: the same inputs give the same answer every time")
    void isDeterministic() {
        PolicyInputs inputs = new PolicyInputs(
                signals("TEAM", true, false, true, "HIGH"), PlanTier.ENTERPRISE, null, 2);

        PriorityDecision first = policy.evaluate(inputs);
        for (int i = 0; i < 100; i++) {
            PriorityDecision again = policy.evaluate(inputs);
            assertThat(again.priority()).isEqualTo(first.priority());
            assertThat(again.rules()).isEqualTo(first.rules());
            assertThat(again.humanReadable()).isEqualTo(first.humanReadable());
        }
    }

    private static RuleTrace ruleNamed(PriorityDecision decision, String name) {
        return decision.rules().stream().filter(r -> name.equals(r.rule())).findFirst()
                .orElseThrow(() -> new AssertionError("No rule named " + name));
    }

    private static TriageSignals signals(String impact, boolean serviceDown, boolean dataLoss,
                                         boolean payment, String urgency) {
        return new TriageSignals(Category.PAYMENT,
                TriageSignals.Severity.valueOf(impact), serviceDown, dataLoss, payment,
                TriageSignals.Urgency.valueOf(urgency), Map.of(), 0.9);
    }
}
