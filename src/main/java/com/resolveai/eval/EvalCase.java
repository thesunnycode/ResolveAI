package com.resolveai.eval;

/**
 * One labelled example: a ticket, and what triage should have said about it.
 *
 * @param name             stable and unique within the suite, because
 *                         {@code uq_eval_case (suite, name)} makes seeding idempotent
 *                         and because a result row is only interesting if you can say
 *                         which case it belongs to.
 * @param body             carries a trailing {@code Case reference EVALCASE###}
 *                         sentence. That is a <b>test-harness affordance, stated
 *                         plainly rather than hidden</b>: it is what lets the WireMock
 *                         fixture answer differently per case, so the suite can be run
 *                         deterministically and for free in CI. Against a real provider
 *                         it is an inert eleven characters.
 * @param expectedCategory the hand-verified label from the Phase 1 corpus — see
 *                         {@code seed/VERIFICATION.md}, where thirty of them were
 *                         checked by hand.
 */
public record EvalCase(
        Long id,
        String name,
        String subject,
        String body,
        String expectedCategory,
        String expectedTeam) {

    /** Subject and body, as the worker would assemble them. */
    public String text() {
        return subject + "\n\n" + body;
    }
}
