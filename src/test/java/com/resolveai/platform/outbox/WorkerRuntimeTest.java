package com.resolveai.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The worker runtime, under the six conditions that matter.
 *
 * <p>Five of them are about failure — a crash, a timeout, a poison message, a provider
 * outage, two instances racing — because <b>the happy path of a queue is the part that
 * cannot be got wrong</b>. Everything interesting about this design is what happens when
 * something stops working halfway through.
 *
 * <p>The sixth is {@link #connectionsAreNotHeldDuringWork()}, which is not about failure
 * at all: it is the test that catches the mistake described at the top of
 * {@link WorkerRuntime} before it reaches production, where its symptom is the whole
 * application refusing HTTP requests for reasons that point at the connection pool rather
 * than at the worker that caused it.
 */
@Import(WorkerRuntimeTest.Workers.class)
class WorkerRuntimeTest extends IntegrationTestBase {

    @TestConfiguration
    static class Workers {
        /**
         * Registered as a bean so the real {@link WorkerRuntime} picks it up through the
         * same {@code List<Worker>} injection production uses. Testing the runtime with a
         * worker it was handed some other way would be testing a different object graph.
         */
        @Bean
        TestWorker testWorker() {
            return new TestWorker(EventType.TICKET_CREATED);
        }
    }

    @Autowired AuthTestSupport auth;
    @Autowired WorkerRuntime runtime;
    @Autowired OutboxReaper reaper;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired RetryPolicy retryPolicy;
    @Autowired TestWorker worker;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private Long tenantId;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenantId = auth.seedTenant("outbox").tenantId();
        worker.reset().withVisibility(Duration.ofMinutes(2)).withBatchSize(20);
    }

    // ── 1. Happy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("An event is claimed, processed once, and marked DONE")
    void happyPath() {
        Long eventId = publish(1L);

        assertThat(runtime.runOnce(worker)).isEqualTo(1);

        assertThat(worker.processedIds()).containsExactly(eventId);
        OutboxEvent after = outbox.findById(eventId).orElseThrow();
        assertThat(after.status()).isEqualTo(OutboxStatus.DONE);
        assertThat(after.attempts()).isEqualTo((short) 1);
        assertThat(after.processedAt()).isNotNull();
        // The lock is released on completion. A DONE row holding a lock would be
        // invisible to the claim query anyway, but the reaper would keep finding it.
        assertThat(after.lockedUntil()).isNull();

        // Nothing is left to claim: a second poll is a no-op, not a redelivery.
        assertThat(runtime.runOnce(worker)).isZero();
    }

    @Test
    @DisplayName("Publishing outside a transaction fails loudly")
    void publishingOutsideATransactionThrows() {
        // The dual write this whole design exists to prevent. MANDATORY turns "somebody
        // forgot @Transactional" from a rare production inconsistency into an exception
        // the first time the code runs.
        assertThatThrownBy(() -> TenantContext.runAs(tenantId, () ->
                publisher.publish("TICKET", 1L, EventType.TICKET_CREATED, Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outbox.countByStatus(OutboxStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("A rolled-back transaction leaves no event behind")
    void rollbackLeavesNoEvent() {
        assertThatThrownBy(() -> TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            publisher.publish("TICKET", 99L, EventType.TICKET_CREATED, Map.of("ticketId", 99));
            throw new IllegalStateException("the business logic failed after publishing");
        }))).isInstanceOf(IllegalStateException.class);

        // The aggregate and its event share a fate. With a broker here instead, the
        // consumer would already be holding the id of a ticket that does not exist.
        assertThat(outbox.findByAggregate("TICKET", 99L)).isEmpty();
    }

    // ── 2. Concurrent claim ─────────────────────────────────────────────────

    @Test
    @DisplayName("Two runtimes racing 50 events process each exactly once")
    void concurrentClaimsDoNotOverlap() throws Exception {
        List<Long> published = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            published.add(publish(1000L + i));
        }
        worker.withBatchSize(25);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    ready.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    int handled = 0;
                    // Each "instance" drains until it finds nothing, exactly as two
                    // application nodes would.
                    for (int pass = 0; pass < 10; pass++) {
                        handled += runtime.runOnce(worker);
                    }
                    return handled;
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<Integer> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        }

        // FOR UPDATE SKIP LOCKED is the entire mechanism: the second claimer steps over
        // the rows the first has locked instead of waiting for them or taking them twice.
        assertThat(worker.processedIds())
                .as("each event processed exactly once")
                .hasSize(50)
                .containsExactlyInAnyOrderElementsOf(published)
                .doesNotHaveDuplicates();
        assertThat(outbox.countByStatus(OutboxStatus.DONE)).isEqualTo(50);
    }

    // ── 3. Redelivery after a worker dies ───────────────────────────────────

    @Test
    @DisplayName("A claim whose visibility expires is reaped and redelivered")
    void expiredClaimsAreReapedAndRedelivered() {
        Long eventId = publish(7L);

        // Claimed with a zero visibility: the same state a worker leaves behind when the
        // JVM is killed mid-process, reached deterministically rather than by waiting out
        // a real timeout.
        List<OutboxEvent> claimed = outbox.claim(List.of(EventType.TICKET_CREATED), 10,
                Duration.ZERO);
        assertThat(claimed).hasSize(1);
        assertThat(outbox.findById(eventId).orElseThrow().status())
                .isEqualTo(OutboxStatus.IN_FLIGHT);

        // Invisible to every other worker while it holds the claim — which is why a
        // process that dies here would strand the event for ever without the reaper.
        assertThat(runtime.runOnce(worker)).isZero();

        assertThat(reaper.reapOnce()).containsExactly(eventId);

        assertThat(runtime.runOnce(worker)).isEqualTo(1);
        assertThat(outbox.findById(eventId).orElseThrow().status()).isEqualTo(OutboxStatus.DONE);
        // Two claims, so two attempts. The redelivery is at-least-once by construction,
        // which is exactly why consumers have to be idempotent.
        assertThat(outbox.findById(eventId).orElseThrow().attempts()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("A live claim is not reaped")
    void liveClaimsSurviveTheReaper() {
        publish(8L);
        outbox.claim(List.of(EventType.TICKET_CREATED), 10, Duration.ofMinutes(5));

        assertThat(reaper.reapOnce()).isEmpty();
        assertThat(outbox.countByStatus(OutboxStatus.IN_FLIGHT)).isEqualTo(1);
    }

    // ── 4. Backoff ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Failures back off exponentially, with jitter, then dead-letter")
    void failuresBackOffAndThenDeadLetter() {
        Long eventId = publish(11L);
        worker.failNextTimes(10);

        List<Long> observedDelays = new ArrayList<>();
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            // Each pass has to make the event due again, because the backoff has pushed
            // next_attempt_at into the future. Rewinding it is the same trick the SLA
            // tests use: cheaper than waiting and exactly as truthful.
            makeDue(eventId);
            runtime.runOnce(worker);

            OutboxEvent after = outbox.findById(eventId).orElseThrow();
            assertThat(after.attempts()).isEqualTo((short) attempt);

            if (attempt < retryPolicy.maxAttempts()) {
                assertThat(after.status()).isEqualTo(OutboxStatus.PENDING);
                observedDelays.add(Duration.between(OffsetDateTime.now(), after.nextAttemptAt())
                        .toSeconds());
            }
        }

        // 2, 4, 8, 16 — each within its jitter window rather than exact, because jitter
        // is the point. Asserting an exact delay would mean asserting that jitter is
        // absent, and a fleet that retries in perfect unison is how a provider's bad
        // minute becomes your outage.
        for (int i = 0; i < observedDelays.size(); i++) {
            long base = retryPolicy.baseBackoffSeconds(i + 1);
            assertThat(observedDelays.get(i))
                    .as("attempt %d backs off ~%ds plus jitter", i + 1, base)
                    .isBetween(base - 2, base + retryPolicy.maxJitterSeconds() + 2);
        }

        OutboxEvent dead = outbox.findById(eventId).orElseThrow();
        assertThat(dead.status()).isEqualTo(OutboxStatus.DEAD);
        assertThat(dead.attempts()).isEqualTo((short) retryPolicy.maxAttempts());
        assertThat(dead.lastError()).contains("Simulated transient failure");
    }

    @Test
    @DisplayName("A non-retryable failure dead-letters on the first attempt")
    void nonRetryableGoesStraightToDead() {
        Long eventId = publish(12L);
        worker.refuseEverything();

        runtime.runOnce(worker);

        OutboxEvent after = outbox.findById(eventId).orElseThrow();
        assertThat(after.status()).isEqualTo(OutboxStatus.DEAD);
        // One attempt, not five. Four more identical failures would tell nobody anything
        // and would hold a worker slot for several minutes doing it.
        assertThat(after.attempts()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("A stored error carries no personal data")
    void storedErrorsAreScrubbed() {
        Long eventId = publish(13L);
        worker.reset().sampling(() -> {
            throw new IllegalStateException(
                    "Could not parse ticket from arjun.mehta@example.com, phone 9876543210");
        });

        runtime.runOnce(worker);

        String stored = outbox.findById(eventId).orElseThrow().lastError();
        // last_error is read in the DLQ screen and pasted into chats. An exception
        // message that quotes its input puts a customer's email address there, under none
        // of the retention rules the ticket itself has.
        assertThat(stored).doesNotContain("arjun.mehta@example.com", "9876543210");
        assertThat(stored).contains("[email]", "[number]");
    }

    // ── 6. Connection discipline ────────────────────────────────────────────

    /**
     * Twenty concurrent events, each spending a second in a simulated network call.
     *
     * <p>The assertion is on the <b>peak number of active pool connections while that is
     * happening</b>. If a worker held a transaction across its network call, this test
     * would show twenty — or rather it would show ten, the pool maximum, and the other
     * ten events would be waiting for a connection while the application served no HTTP
     * at all.
     *
     * <p>That is the failure mode virtual threads make easier to reach: the old
     * platform-thread pool capped concurrency at eight by accident, and removing the cap
     * removes the accidental protection. This test is the replacement for it.
     */
    @Test
    @DisplayName("No connection is held during a worker's network call")
    void connectionsAreNotHeldDuringWork() throws Exception {
        for (int i = 0; i < 20; i++) {
            publish(2000L + i);
        }

        AtomicInteger peakActive = new AtomicInteger();
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        worker.takingAtLeast(Duration.ofSeconds(1))
                .sampling(() -> peakActive.accumulateAndGet(
                        hikari.getHikariPoolMXBean().getActiveConnections(), Math::max));

        assertThat(runtime.runOnce(worker)).isEqualTo(20);

        assertThat(worker.peakInFlight())
                .as("the batch really did run concurrently")
                .isGreaterThan(1);
        assertThat(peakActive.get())
                .as("peak active connections while 20 events were mid-'network call'")
                .isLessThanOrEqualTo(2);
        assertThat(outbox.countByStatus(OutboxStatus.DONE)).isEqualTo(20);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long publish(Long aggregateId) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status ->
                publisher.publish("TICKET", aggregateId, EventType.TICKET_CREATED,
                        Map.of("ticketId", aggregateId, "v", 1))));
    }

    private void makeDue(Long eventId) {
        jdbc.update("UPDATE outbox_event SET next_attempt_at = NOW() WHERE id = ?", eventId);
    }
}
