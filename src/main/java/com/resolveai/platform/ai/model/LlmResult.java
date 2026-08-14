package com.resolveai.platform.ai.model;

/**
 * What a model call produced, and what it cost.
 *
 * <p>The value is half of this record; the other half is the provenance. Every one of
 * these fields ends up on the {@code ai_analysis} row, because the questions asked about
 * a classification months later are "which model", "which prompt", "how confident" and
 * "how much did we spend" — and none of them can be reconstructed afterwards from the
 * value alone.
 *
 * @param costMicros <b>an integer, always.</b> Money in a {@code double} accumulates
 *                   rounding error across a month of per-call increments, and the
 *                   direction of the drift is not predictable. Micros — millionths of a
 *                   unit — give six decimal places of headroom in a type that adds
 *                   exactly.
 * @param attempt    which try this was. A repaired parse is attempt 2, and knowing that
 *                   a tenant's classifications routinely need repairing is a signal
 *                   about the prompt, not about the tickets.
 */
public record LlmResult<T>(
        T value,
        String modelId,
        String promptLabel,
        int tokensIn,
        int tokensOut,
        long costMicros,
        long latencyMs,
        int attempt) {
}
