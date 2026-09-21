package com.resolveai.platform.outbox;

import com.resolveai.platform.tenant.TenantContext;
import com.resolveai.platform.time.DatabaseClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Claims outbox events and hands them to the {@link Worker} that declared their type.
 *
 * <h2>⚠ A worker must never hold a database connection across an LLM call</h2>
 *
 * <p>This is the single most important structural rule in the async design, and it is
 * worth stating in full because the code that breaks it looks completely reasonable:
 *
 * <pre>{@code
 * @Transactional                      // ← opens a connection, holds it for the method
 * public void process(OutboxEvent e) {
 *     var ticket = tickets.findById(e.aggregateId());
 *     var signals = llm.classify(ticket);     // ← 2-8 seconds, connection still held
 *     analyses.save(new AiAnalysis(signals));
 * }
 * }</pre>
 *
 * <p>The pool is capped at ten. Ten concurrent triages hold all ten connections for eight
 * seconds each, and <b>the entire application stops serving HTTP</b> — every request
 * queues on {@code getConnection()} and times out. The logs show connection-pool
 * exhaustion, which sends you to read Hikari settings; the cause is a network call inside
 * a transaction, three layers away.
 *
 * <p><b>Virtual threads make this easier to hit, not harder.</b> A platform-thread pool
 * of eight was accidentally limiting concurrency to eight; virtual threads remove that
 * limit, so the first load test after switching them on is the one that discovers the
 * transaction was never safe.
 *
 * <p>So every worker is structured as: <b>short transaction to read → no transaction for
 * the network call → short transaction to write.</b> The runtime enforces its half by
 * calling {@link Worker#process} with no transaction open; the claim and the completion
 * are separate, single-statement transactions of their own.
 *
 * <h2>Virtual threads, and the one Java 21 caveat</h2>
 *
 * <p>Each event in a batch runs on its own virtual thread, and the loop waits for the
 * batch before polling again — so a slow event delays the next poll rather than letting
 * an unbounded backlog of in-flight work accumulate.
 *
 * <p>{@code synchronized} pins the carrier thread in Java 21: a virtual thread that
 * blocks inside a {@code synchronized} block cannot unmount, so enough of them exhaust
 * the carrier pool and the symptom is a mysterious stall rather than an error. Workers
 * that need mutual exclusion use {@link java.util.concurrent.locks.ReentrantLock}.
 *
 * <h2>The tenant comes off the row</h2>
 *
 * <p>There is no request here and therefore no tenant. Every event is processed inside
 * {@code TenantContext.callAs(event.tenantId())} — set <i>before</i> any transaction
 * opens, because Hibernate resolves the tenant when the session opens, not per statement.
 * Getting that backwards produces "entity not found" on rows that plainly exist, and it
 * has now cost this project an afternoon twice.
 */
@Component
public class WorkerRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final OutboxRepository outbox;
    private final RetryPolicy retryPolicy;
    private final DatabaseClock clock;
    private final MeterRegistry metrics;
    private final List<Worker> workers;

    public WorkerRuntime(OutboxRepository outbox, RetryPolicy retryPolicy, DatabaseClock clock,
                         MeterRegistry metrics, List<Worker> workers) {
        this.outbox = outbox;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.metrics = metrics;
        this.workers = workers;
        log.info("Worker runtime registered {} worker(s): {}", workers.size(),
                workers.stream().map(Worker::name).collect(Collectors.joining(", ")));
        registerGauges();
    }

    /**
     * One poll of every registered worker.
     *
     * <p>{@code fixedDelay}, so a slow batch makes the runtime poll <i>less often</i>
     * rather than queueing overlapping runs. One second is a latency floor on triage that
     * nobody will notice against an LLM call that takes several.
     */
    @Scheduled(fixedDelayString = "${resolveai.workers.poll-interval-ms:1000}")
    public void poll() {
        for (Worker worker : workers) {
            try {
                runOnce(worker);
            } catch (RuntimeException e) {
                // A scheduled method that throws is never run again by some schedulers.
                // One bad batch must not stop every worker in the process for ever.
                log.error("Worker {} poll failed; the next poll will retry", worker.name(), e);
            }
        }
    }

    /**
     * Claims and processes one batch for one worker. Returns how many events were
     * handled, so tests can drive the runtime deterministically instead of waiting for a
     * schedule — a test that sleeps for a poll interval is slow and flaky at once.
     */
    public int runOnce(Worker worker) {
        List<OutboxEvent> batch = outbox.claim(List.copyOf(worker.handles()),
                worker.batchSize(), worker.visibilityTimeout());
        if (batch.isEmpty()) {
            return 0;
        }
        count("outbox.claimed", batch);

        // One virtual thread per event, and the batch is awaited before returning. The
        // executor is per-batch rather than shared: virtual threads are cheap enough that
        // pooling them is pointless, and a per-batch executor cannot leak tasks between
        // polls.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>(batch.size());
            for (OutboxEvent event : batch) {
                running.add(pool.submit(() -> handle(worker, event)));
            }
            for (Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return batch.size();
                } catch (Exception e) {
                    // handle() already recorded the outcome on the row; this only guards
                    // the await itself.
                    log.error("Worker {} task failed outside its handler", worker.name(), e);
                }
            }
        }
        return batch.size();
    }

    /**
     * One event, start to finish.
     *
     * <p><b>No transaction wraps this.</b> The worker opens its own short ones; the
     * outcome write below is a single statement in its own.
     */
    private void handle(Worker worker, OutboxEvent event) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            if (event.tenantId() == null) {
                worker.process(event);
            } else {
                TenantContext.runAs(event.tenantId(), () -> worker.process(event));
            }
            outbox.markDone(event.id());
            metrics.counter("outbox.succeeded", "type", event.eventType().name()).increment();

        } catch (NonRetryableException e) {
            // Deliberately not retried: five identical failures tell nobody anything the
            // first one did not, and they hold a worker slot while doing it.
            log.warn("Outbox {} ({}) is non-retryable; dead-lettering on attempt {}",
                    event.id(), event.eventType(), event.attempts(), e);
            outbox.markDead(event.id(), ErrorScrubber.scrub(e));
            metrics.counter("outbox.dead", "type", event.eventType().name(),
                    "reason", "non_retryable").increment();

        } catch (Exception e) {
            failWithBackoff(event, e);

        } finally {
            sample.stop(metrics.timer("outbox.processing.duration",
                    "type", event.eventType().name()));
        }
    }

    private void failWithBackoff(OutboxEvent event, Exception e) {
        String scrubbed = ErrorScrubber.scrub(e);
        if (retryPolicy.shouldRetry(event.attempts())) {
            var nextAttempt = retryPolicy.nextAttemptAt(clock.now(), event.attempts());
            log.warn("Outbox {} ({}) failed on attempt {}/{}; retrying at {}",
                    event.id(), event.eventType(), event.attempts(),
                    retryPolicy.maxAttempts(), nextAttempt, e);
            outbox.markForRetry(event.id(), nextAttempt, scrubbed);
            metrics.counter("outbox.failed", "type", event.eventType().name()).increment();
        } else {
            // WARN rather than ERROR on the individual event: the alert that matters is
            // the DLQ depth, not each arrival, and paging on every dead event during a
            // provider outage trains people to mute the alert.
            log.warn("Outbox {} ({}) exhausted {} attempts; dead-lettering",
                    event.id(), event.eventType(), retryPolicy.maxAttempts(), e);
            outbox.markDead(event.id(), scrubbed);
            metrics.counter("outbox.dead", "type", event.eventType().name(),
                    "reason", "exhausted").increment();
        }
    }

    private void count(String metric, List<OutboxEvent> batch) {
        Map<EventType, Long> byType = batch.stream().collect(
                Collectors.groupingBy(OutboxEvent::eventType, Collectors.counting()));
        byType.forEach((type, n) ->
                metrics.counter(metric, "type", type.name()).increment(n));
    }

    /**
     * Depth and age.
     *
     * <p><b>Age is the one that matters.</b> A depth of five hundred draining in ten
     * seconds is a healthy system under load; a depth of three where the oldest is twenty
     * minutes old is something stuck in a retry loop, and the second is the incident.
     * Depth alone cannot tell them apart, which is why both are published and why the
     * health indicator reads the age.
     */
    private void registerGauges() {
        metrics.gauge("outbox.queue.depth", this,
                runtime -> runtime.outbox.countByStatus(OutboxStatus.PENDING));
        metrics.gauge("outbox.dead.depth", this,
                runtime -> runtime.outbox.countByStatus(OutboxStatus.DEAD));
        metrics.gauge("outbox.oldest.pending.seconds", this,
                runtime -> runtime.outbox.oldestPendingAgeSeconds());
    }

    /** Visible for tests that need to drive a specific worker. */
    public List<Worker> workers() {
        return List.copyOf(workers);
    }

    /** Every registered worker, one batch each. */
    public int runAllOnce() {
        int handled = 0;
        for (Worker worker : workers) {
            handled += runOnce(worker);
        }
        return handled;
    }

    /** The default visibility timeout, for tests asserting on reaper behaviour. */
    public static Duration defaultVisibility() {
        return Duration.ofMinutes(2);
    }
}
