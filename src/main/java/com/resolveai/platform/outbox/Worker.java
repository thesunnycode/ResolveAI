package com.resolveai.platform.outbox;

import java.time.Duration;
import java.util.Set;

/**
 * Something that processes one kind of outbox event.
 *
 * <p>A worker declares what it handles and how long it needs; {@link WorkerRuntime} does
 * the claiming, the retrying, the backoff and the dead-lettering. That split is the
 * point: <b>every worker in the system gets crash recovery and a dead-letter queue for
 * free</b>, and none of them can implement it slightly differently.
 *
 * <h2>The rule that matters</h2>
 *
 * <p>{@link #process} runs <b>outside any transaction</b>, on a virtual thread. A worker
 * that needs the database opens its own short transactions around the reads and writes
 * and holds none of them across a network call. {@link WorkerRuntime}'s class comment
 * explains what happens when that rule is broken, and it is not subtle.
 */
public interface Worker {

    /** Which event types this worker claims. One worker may handle several. */
    Set<EventType> handles();

    /**
     * How long a claim holds before the reaper takes it back.
     *
     * <p>Must exceed the realistic worst case for {@link #process}, including its own
     * retries. Set it too low and a slow-but-working event is redelivered while the first
     * attempt is still running, so the work happens twice; too high and a genuinely
     * crashed worker's event sits unavailable for that long.
     */
    default Duration visibilityTimeout() {
        return Duration.ofMinutes(2);
    }

    /** How many events to claim per poll. */
    default int batchSize() {
        return 20;
    }

    /** A name for logs and metrics. */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Does the work.
     *
     * <p>Throwing schedules a retry with backoff. Throwing
     * {@link NonRetryableException} goes straight to {@code DEAD} — for a payload that
     * cannot be parsed or an aggregate that no longer exists, where five attempts would
     * only waste four more.
     */
    void process(OutboxEvent event);
}
