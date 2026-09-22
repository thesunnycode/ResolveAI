package com.resolveai.drafting.domain;

/**
 * The verdict stamped on a {@code draft_claim} row. Matches {@code ck_claim_verdict}.
 *
 * <p>{@code FAILED_NUMERIC_CHECK} is its own value rather than folded into
 * {@code NOT_SUPPORTED}, because the two mean different things to whoever reads a
 * dropped claim later: one means "the deterministic pre-filter caught a fabricated
 * number, at zero LLM cost, before the model's judgement was ever consulted"; the other
 * means "an LLM read the claim against its citation and disagreed". Distinguishing them
 * is also how the grounding eval reports the pre-filter's standalone contribution.
 */
public enum ClaimVerdict {
    PENDING,
    SUPPORTED,
    PARTIAL,
    NOT_SUPPORTED,
    FAILED_NUMERIC_CHECK;

    /** Whether a claim with this verdict is kept in the assembled text. */
    public boolean isKept() {
        return this == SUPPORTED || this == PARTIAL;
    }
}
