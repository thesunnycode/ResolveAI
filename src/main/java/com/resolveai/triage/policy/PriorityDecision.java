package com.resolveai.triage.policy;

import com.resolveai.ticketing.domain.Priority;
import java.util.List;

/**
 * A priority, and the complete argument for it.
 *
 * <p>The decision and its reasoning are one value, not two. Returning a bare
 * {@link Priority} and logging the reasoning would make the rationale best-effort —
 * present when somebody remembered to log, absent on the path that mattered — and the
 * rationale is the product feature, not a debugging aid.
 *
 * @param policyVersion the version of the rule set that produced this, recorded so a
 *                      decision made under {@code v1} still explains itself after
 *                      {@code v2} ships. Without it, re-rendering an old rationale under
 *                      new rules would show an argument that was never made.
 * @param rules         every rule, in evaluation order, matched or not.
 */
public record PriorityDecision(
        Priority priority,
        String policyVersion,
        List<RuleTrace> rules,
        String humanReadable) {

    public PriorityDecision {
        rules = List.copyOf(rules);
    }

    /** The rules that actually fired, for a caller that wants only the short version. */
    public List<RuleTrace> matchedRules() {
        return rules.stream().filter(RuleTrace::matched).toList();
    }
}
