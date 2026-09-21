package com.resolveai.platform.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * When to try again, and when to stop.
 *
 * <h2>Exponential, capped, and jittered — the third one is not decoration</h2>
 *
 * <p>Exponential backoff alone is <b>synchronised</b> backoff. A provider outage fails
 * two hundred events within the same second; all two hundred then wait exactly two
 * seconds and retry in the same second, then four, then eight. The retries arrive as
 * spikes, each one hammering a service that is already struggling, and the pattern
 * repeats five times before anything dead-letters. That is a self-inflicted thundering
 * herd, and it is a well-documented way to turn a provider's brief wobble into your own
 * sustained outage.
 *
 * <p>Up to thirty seconds of jitter spreads the same two hundred retries across a window
 * instead of a moment. It costs nothing and it is the difference between recovering from
 * an outage and extending it.
 *
 * <p>The cap matters too: without it, attempt five would wait 32 seconds but a system
 * with more attempts would be waiting hours, and an event nobody is watching sits in
 * {@code PENDING} looking exactly like one that is progressing.
 */
@Component
public class RetryPolicy {

    private final int maxAttempts;
    private final long maxBackoffSeconds;
    private final long maxJitterSeconds;

    public RetryPolicy(
            @Value("${resolveai.workers.max-attempts:5}") int maxAttempts,
            @Value("${resolveai.workers.max-backoff-seconds:300}") long maxBackoffSeconds,
            @Value("${resolveai.workers.max-jitter-seconds:30}") long maxJitterSeconds) {
        this.maxAttempts = maxAttempts;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.maxJitterSeconds = maxJitterSeconds;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Whether an event that has just failed its {@code attempts}-th try gets another. */
    public boolean shouldRetry(int attempts) {
        return attempts < maxAttempts;
    }

    /**
     * {@code now + min(2^attempts, cap) + jitter}.
     *
     * <p>{@code attempts} is the count <i>including</i> the one that just failed, because
     * the claim statement increments it — so the first failure waits two seconds, not
     * one, and the progression a test can assert is 2, 4, 8, 16, 32.
     */
    public OffsetDateTime nextAttemptAt(OffsetDateTime now, int attempts) {
        long backoff = Math.min((long) Math.pow(2, Math.max(1, attempts)), maxBackoffSeconds);
        long jitter = maxJitterSeconds <= 0 ? 0
                : ThreadLocalRandom.current().nextLong(maxJitterSeconds + 1);
        return now.plus(Duration.ofSeconds(backoff + jitter));
    }

    /** The un-jittered part, so a test can state the expected window rather than guess. */
    public long baseBackoffSeconds(int attempts) {
        return Math.min((long) Math.pow(2, Math.max(1, attempts)), maxBackoffSeconds);
    }

    public long maxJitterSeconds() {
        return maxJitterSeconds;
    }
}
