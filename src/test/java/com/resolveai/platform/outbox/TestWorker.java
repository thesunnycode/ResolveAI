package com.resolveai.platform.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A worker whose behaviour a test can dictate: succeed, fail N times then succeed, refuse
 * outright, or block until released.
 *
 * <p><b>Not a mock.</b> The runtime's contract is about transactions, visibility
 * timeouts and virtual threads — things a stubbed {@code process()} cannot exercise
 * because a mock never actually blocks, never actually holds a connection, and never
 * actually outlives its claim. This is a real bean doing real work slowly on demand.
 */
public class TestWorker implements Worker {

    private final Set<EventType> handles;
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> processed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();

    private volatile CountDownLatch blockUntil;
    private volatile boolean nonRetryable;
    private volatile Duration workDuration = Duration.ZERO;
    private volatile Duration visibility = Duration.ofMinutes(2);
    private volatile int batchSize = 20;
    private volatile Runnable duringWork;

    public TestWorker(EventType... types) {
        this.handles = Set.of(types);
    }

    @Override
    public Set<EventType> handles() {
        return handles;
    }

    @Override
    public Duration visibilityTimeout() {
        return visibility;
    }

    @Override
    public int batchSize() {
        return batchSize;
    }

    @Override
    public void process(OutboxEvent event) {
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        try {
            if (duringWork != null) {
                duringWork.run();
            }
            CountDownLatch gate = blockUntil;
            if (gate != null) {
                // A latch, never a sleep: the test decides when work finishes, so there
                // is no timing assumption to become flaky on a loaded machine.
                gate.await(30, TimeUnit.SECONDS);
            }
            if (!workDuration.isZero()) {
                Thread.sleep(workDuration.toMillis());
            }
            if (nonRetryable) {
                throw new NonRetryableException("Payload is unusable: " + event.payload());
            }
            if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("Simulated transient failure for " + event.id());
            }
            processed.add(event.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    // ── Controls ────────────────────────────────────────────────────────────

    public TestWorker failNextTimes(int n) {
        failuresRemaining.set(n);
        return this;
    }

    public TestWorker refuseEverything() {
        nonRetryable = true;
        return this;
    }

    public TestWorker blockOn(CountDownLatch latch) {
        blockUntil = latch;
        return this;
    }

    public TestWorker takingAtLeast(Duration duration) {
        workDuration = duration;
        return this;
    }

    public TestWorker withVisibility(Duration duration) {
        visibility = duration;
        return this;
    }

    public TestWorker withBatchSize(int size) {
        batchSize = size;
        return this;
    }

    /** Runs on the worker thread while an event is in flight — for sampling the pool. */
    public TestWorker sampling(Runnable probe) {
        duringWork = probe;
        return this;
    }

    public TestWorker reset() {
        failuresRemaining.set(0);
        nonRetryable = false;
        blockUntil = null;
        workDuration = Duration.ZERO;
        duringWork = null;
        processed.clear();
        peakInFlight.set(0);
        return this;
    }

    // ── Observations ────────────────────────────────────────────────────────

    public List<Long> processedIds() {
        return List.copyOf(processed);
    }

    public int processedCount() {
        return processed.size();
    }

    public int peakInFlight() {
        return peakInFlight.get();
    }
}
