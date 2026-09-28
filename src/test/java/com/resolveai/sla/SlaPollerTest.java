package com.resolveai.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.sla.service.SlaDeadlinePoller;
import com.resolveai.ticketing.TicketTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The escalation ladder under a poller that is deliberately at-least-once.
 *
 * <h2>Why exactly-once is a database constraint and not application code</h2>
 *
 * <p>The poller claims ids in one short transaction and processes them in others, so two
 * runs — or two application instances — can pick the same record. Nothing in the
 * application prevents that, and nothing needs to: {@code uq_escalation_rung} makes the
 * second insert fail, and the failure <i>is</i> the deduplication. An at-least-once
 * trigger becomes an exactly-once effect with no leader election, no distributed lock and
 * no coordination between instances.
 *
 * <p>These tests are what makes that claim checkable rather than a comment.
 */
class SlaPollerTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport sla;
    @Autowired SlaDeadlinePoller poller;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("slapoll");
        sla.makeCalendarAlwaysOpen(tenant.tenantId());
        sla.seedPolicies(tenant.tenantId(), "PRO");
        agentToken = auth.accessToken(rest, "slapoll", "agent");
        customerToken = auth.accessToken(rest, "slapoll", "customer");
    }

    /**
     * A clock well past its target climbs the whole ladder, and then stops climbing.
     *
     * <p>One rung per pass is deliberate — each pass re-reads the record under its lock
     * and re-checks the arithmetic, so a clock that was paused between passes stops where
     * it is. The ladder is walked by running the poller until it reports nothing fired,
     * and the assertion that matters is the one after that: <b>three further polls change
     * nothing.</b> Without the unique constraint they would produce twelve escalation rows
     * and twelve notifications, and the first anyone would know is a team lead with a
     * mailbox full of the same alert.
     */
    @Test
    @DisplayName("Every rung fires exactly once, however many times the poller runs")
    void rungsFireExactlyOnce() {
        Long ticketId = overdueTicket("Ladder", 500);
        Long resolution = sla.recordId(ticketId, "RESOLUTION");

        int passes = 0;
        while (poller.pollOnce() > 0 && passes < 10) {
            passes++;
        }

        assertThat(sla.firedRungs(resolution)).as(() -> sla.describe(resolution))
                .containsExactly(50, 75, 90, 100);
        assertThat(sla.state(resolution)).isEqualTo("BREACHED");
        // Out of idx_sla_poller for good. A breached record left with a deadline would be
        // claimed every ten seconds for the rest of its life, finding nothing to do.
        assertThat(jdbc.queryForObject(
                "SELECT next_deadline_at IS NULL FROM sla_record WHERE id = ?",
                Boolean.class, resolution)).isTrue();

        int notificationsAfterLadder = sla.notificationCount(tenant.tenantId());

        poller.pollOnce();
        poller.pollOnce();
        poller.pollOnce();

        assertThat(sla.firedRungs(resolution)).containsExactly(50, 75, 90, 100);
        assertThat(sla.notificationCount(tenant.tenantId())).isEqualTo(notificationsAfterLadder);
    }

    /**
     * Two pollers, one due record, released together — the two-instance case.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} usually means only one of them even sees the
     * record; when the first has already committed and released, the second claims it and
     * is stopped by the constraint instead. Both paths end in one row, which is why the
     * assertion is on the row and not on which mechanism did the work.
     */
    @RepeatedTest(10)
    @DisplayName("Two pollers racing the same due record fire one escalation between them")
    void concurrentPollersFireOnce() throws Exception {
        Long ticketId = overdueTicket("Two pollers", 40);
        Long firstResponse = sla.recordId(ticketId, "FIRST_RESPONSE");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return poller.pollOnce();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<Integer> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(sla.firedRungs(firstResponse)).containsExactly(50);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sla_escalation WHERE sla_record_id = ? AND rung = 50
                """, Integer.class, firstResponse)).isEqualTo(1);
    }

    /**
     * <b>The test the polling design exists for.</b>
     *
     * <p>A deadline two hours in the past is what an application that was down for two
     * hours leaves behind — a deploy, a crash, a node drained by the scheduler. One poll
     * after it comes back finds the record and fires the rung it owed.
     *
     * <p>The alternative design — a {@code ScheduledExecutorService} task per deadline —
     * is simpler, has no polling latency, and <b>silently loses every pending escalation
     * on restart</b>. Nothing logs it, because from the new process's point of view those
     * timers never existed. The first anyone hears is a customer escalating weeks later
     * about a breach that was never flagged, by which time the evidence is gone.
     *
     * <p>An absolute deadline in an indexed column has no such failure mode: the state is
     * in the database, and the process is stateless with respect to it.
     */
    @Test
    @DisplayName("A deadline missed while the application was down fires on the next poll")
    void recoversDeadlinesMissedWhileDown() {
        Long ticketId = overdueTicket("Missed while down", 120);
        Long firstResponse = sla.recordId(ticketId, "FIRST_RESPONSE");

        assertThat(sla.firedRungs(firstResponse)).isEmpty();

        int fired = poller.pollOnce();

        assertThat(fired).isGreaterThanOrEqualTo(1);
        // Not "some rung eventually": the correct rung, immediately, on the first pass
        // after recovery. 120 minutes against a 60-minute target is past every rung, so
        // the ladder starts where it left off rather than skipping to the breach — the
        // assignee still gets told before their lead does.
        assertThat(sla.firedRungs(firstResponse)).containsExactly(50);
        // And somebody was actually told. An escalation row with no notification behind it
        // is an audit trail of alerts nobody received.
        assertThat(jdbc.queryForObject("""
                SELECT notification_id IS NOT NULL FROM sla_escalation
                 WHERE sla_record_id = ? AND rung = 50
                """, Boolean.class, firstResponse)).isTrue();
    }

    /**
     * A paused clock is invisible to the poller, because {@code next_deadline_at} is null
     * and {@code idx_sla_poller} is partial on {@code state = 'RUNNING'}.
     */
    @Test
    @DisplayName("A paused clock is never claimed, however overdue it looks")
    void pausedClocksAreNotClaimed() {
        Long ticketId = overdueTicket("Paused and overdue", 500);
        Long resolution = sla.recordId(ticketId, "RESOLUTION");
        tickets.post(rest, agentToken, ticketId, "status",
                Map.of("status", "WAITING_ON_CUSTOMER", "reason", "Waiting"));

        poller.pollOnce();
        poller.pollOnce();

        assertThat(sla.state(resolution)).isEqualTo("PAUSED");
        assertThat(sla.firedRungs(resolution)).isEmpty();
    }

    /**
     * Batch latency, measured rather than asserted.
     *
     * <p>Fifty due clocks through one pass, timed. The number goes in the README's
     * benchmark row, and the only assertion is that every rung fired — a latency figure
     * from a pass that quietly did nothing would be worse than no figure at all.
     *
     * <p>Deliberately not a performance <i>assertion</i>. A threshold here would fail on
     * a loaded CI runner and teach everyone to ignore it, and the interesting property of
     * this design is not that a poll is fast but that its cost tracks the number of due
     * records rather than the number of open tickets — which is a claim about
     * {@code idx_sla_poller}, not about milliseconds.
     */
    @Test
    @DisplayName("Batch latency: 50 due clocks in one pass")
    void batchLatency() {
        int tickets = 25;
        // 130 minutes puts both clocks past their 50% rung: 216% of a 60-minute
        // first-response target and 54% of a 240-minute resolution one. At 40 minutes
        // only the first-response clocks would be due, and the pass would be measuring
        // half the batch.
        for (int i = 0; i < tickets; i++) {
            overdueTicket("Batch " + i, 130);
        }
        int due = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sla_record
                 WHERE state = 'RUNNING' AND next_deadline_at <= NOW()
                """, Integer.class);
        assertThat(due).isEqualTo(tickets * 2);

        long startedAt = System.nanoTime();
        int fired = poller.pollOnce();
        long millis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.printf("BENCHMARK sla-poller: %d claimed, %d fired, one pass, %d ms "
                + "(%.1f ms/record)%n", due, fired, millis, (double) millis / due);

        // Every claimed record fired. A latency figure from a pass that quietly did
        // nothing - because the arithmetic re-check under the lock found the rungs not
        // yet crossed - would be worse than no figure at all.
        assertThat(fired).isEqualTo(due);
    }

    /**
     * A ticket whose clocks are {@code minutesOverdue} minutes into their life, assigned,
     * and due right now.
     */
    private Long overdueTicket(String subject, long minutesOverdue) {
        Long ticketId = tickets.createId(rest, customerToken, subject, "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        // Assigned, so the escalation has somebody to notify — an unassigned ticket still
        // writes the escalation row but sends nothing, and that is a different test.
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        for (String kind : List.of("FIRST_RESPONSE", "RESOLUTION")) {
            Long recordId = sla.recordId(ticketId, kind);
            sla.rewind(recordId, minutesOverdue);
        }
        return ticketId;
    }
}
