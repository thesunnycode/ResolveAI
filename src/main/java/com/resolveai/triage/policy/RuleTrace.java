package com.resolveai.triage.policy;

/**
 * One rule's contribution to a priority decision, matched or not.
 *
 * <h2>Why the rules that did <i>not</i> match are recorded too</h2>
 *
 * <p>A trace of only the matched rules answers "why is this P1?" but not the question an
 * agent actually asks, which is <b>"why is this not P1?"</b> — and that question is
 * answered by {@code PLAN_TIER_BUMP, matched: false, note: "PRO does not bump;
 * ENTERPRISE would"}. The unmatched entries are where the near-misses live, and a
 * near-miss is the single most useful thing to see when deciding whether the policy needs
 * changing or this ticket needs overriding.
 *
 * @param rule   the rule's stable name. Stable because it is stored in JSONB and read
 *               back months later; renaming one makes every historical rationale
 *               referring to it unexplainable.
 * @param effect what it did, as the UI shows it: {@code "P3"}, {@code "+1"},
 *               {@code "none"}. A string rather than a number because the base rule sets
 *               an absolute level and the bumps are relative, and flattening both into an
 *               integer would lose which is which.
 * @param note   a sentence for a human. Written even for unmatched rules — that is the
 *               whole point above.
 */
public record RuleTrace(String rule, boolean matched, String effect, String note) {

    static RuleTrace matched(String rule, String effect, String note) {
        return new RuleTrace(rule, true, effect, note);
    }

    static RuleTrace skipped(String rule, String note) {
        return new RuleTrace(rule, false, "none", note);
    }
}
