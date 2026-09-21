package com.resolveai.platform.ai.model;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * What each model costs, per million tokens, in micros.
 *
 * <h2>Integers, and a table, and neither is incidental</h2>
 *
 * <p><b>Integers</b> because this number is added to a running total once per call and
 * compared against a customer's budget. A {@code double} accumulates error across
 * thousands of additions, in a direction nobody can predict, on a figure somebody is
 * billed against. Micros per million tokens keeps every intermediate value whole.
 *
 * <p><b>A table</b> because the alternative is a rate inlined at the call site, which is
 * wrong the week a provider changes its prices and silently wrong for every historical
 * row already computed with it. Rates here, cost snapshotted onto {@code ai_analysis} at
 * the time of the call, so a repricing never rewrites history.
 *
 * <p>An unknown model costs {@link #UNKNOWN_MODEL_RATE} rather than zero. Zero would make
 * an unrecognised model free, which is the one way a budget can be exhausted without the
 * budget noticing.
 */
@Component
public class ModelRates {

    /** Input and output micros per million tokens. */
    public record Rate(long inputPerMillion, long outputPerMillion) {
    }

    /**
     * A deliberately pessimistic guess for a model nobody registered: ten rupees-ish per
     * million tokens either way. Being wrong high means a budget stops early and somebody
     * asks why; being wrong low means it never stops at all.
     */
    private static final Rate UNKNOWN_MODEL_RATE = new Rate(1_000_000L, 3_000_000L);

    /**
     * Published list prices, in micros per million tokens, as of the build date.
     *
     * <p>Not read from the provider at runtime: a cost calculation that depends on a
     * network call fails exactly when the provider is having trouble, which is when
     * accurate accounting matters most.
     */
    private static final Map<String, Rate> RATES = Map.of(
            "gpt-4.1-mini", new Rate(400_000L, 1_600_000L),
            "gpt-4.1", new Rate(2_000_000L, 8_000_000L),
            "gpt-4o-mini", new Rate(150_000L, 600_000L),
            // Local, so the marginal cost is electricity. Recorded as zero rather than
            // omitted, so that "this tenant's AI cost nothing" is a fact in the data
            // rather than an absence in it.
            "llama3.2:3b", new Rate(0L, 0L),
            "text-embedding-3-small", new Rate(20_000L, 0L));

    public Rate rateFor(String modelId) {
        return RATES.getOrDefault(modelId, UNKNOWN_MODEL_RATE);
    }

    /**
     * What a call cost, in micros, rounded up.
     *
     * <p>Rounded <b>up</b>: a per-call rounding error that always favours the customer
     * accumulates into a real difference between what was spent and what was recorded,
     * and the budget is the thing protecting the bill.
     */
    public long costMicros(String modelId, int tokensIn, int tokensOut) {
        Rate rate = rateFor(modelId);
        long input = ceilDiv((long) tokensIn * rate.inputPerMillion(), 1_000_000L);
        long output = ceilDiv((long) tokensOut * rate.outputPerMillion(), 1_000_000L);
        return input + output;
    }

    /** What a call is likely to cost, for the budget check that happens before it. */
    public long estimateMicros(String modelId, int estimatedTokensIn) {
        // Output is assumed to be a quarter of input, which is about right for a
        // classification returning a small JSON object. An estimate that is too low
        // would let a call through that the budget cannot afford.
        return costMicros(modelId, estimatedTokensIn, Math.max(64, estimatedTokensIn / 4));
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }
}
