package com.resolveai.platform.outbox;

/**
 * Where an event is in its life.
 *
 * <p>Four states, and the interesting one is {@link #IN_FLIGHT}: it means "claimed by a
 * worker that has not finished yet", which is <b>not</b> the same as "being worked on".
 * A worker that was killed mid-process leaves its event here for ever, which is why
 * {@code OutboxReaper} exists and why the lag health indicator counts IN_FLIGHT as lag.
 */
public enum OutboxStatus {

    /** Waiting to be claimed, at or after {@code next_attempt_at}. */
    PENDING,

    /**
     * Claimed, with a visibility timeout. Returns to {@link #PENDING} on its own if the
     * timeout passes without the worker finishing — the recovery path for a crash.
     */
    IN_FLIGHT,

    /** Processed successfully. Kept for audit until the pruner takes it. */
    DONE,

    /**
     * Out of attempts, or rejected as non-retryable. <b>Nothing retries a DEAD event
     * automatically</b> — a human looks at it, because the alternative is a poison
     * message consuming a worker for ever.
     */
    DEAD
}
