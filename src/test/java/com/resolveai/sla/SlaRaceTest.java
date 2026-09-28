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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three races in the SLA engine, each run repeatedly.
 *
 * <h2>Test A — a reply and a breach at the same instant</h2>
 *
 * <p>An agent replies at the moment the poller decides the first-response clock has
 * breached. The two must not both happen: a clock that is {@code MET} and has a breach
 * escalation against it is a clock nobody can explain, and it is the kind of thing that
 * surfaces months later in a contractual argument.
 *
 * <p><b>The invariant is consistency, not a winner.</b> Either outcome is correct — the
 * reply genuinely did or did not arrive first — and asserting that a particular side wins
 * would be asserting on thread scheduling, which is how a test becomes flaky. What is
 * asserted is that exactly one of the two outcomes is visible afterwards.
 *
 * <p>The mechanism is the row lock: the poller re-reads the record {@code FOR UPDATE}
 * inside its per-record transaction, and the reply's transaction touches the same row.
 * One of them goes second and sees the other's committed work. <b>Removing the
 * {@code FOR UPDATE} from {@code SlaRecordRepository.findByIdForUpdate} is how to check
 * this test is real</b> — it should start failing within a handful of iterations.
 */
class SlaRaceTest extends IntegrationTestBase {

    private static final int ITERATIONS = 50;

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
        tenant = auth.seedTenant("slarace");
        sla.makeCalendarAlwaysOpen(tenant.tenantId());
        sla.seedPolicies(tenant.tenantId(), "PRO");
        agentToken = auth.accessToken(rest, "slarace", "agent");
        customerToken = auth.accessToken(rest, "slarace", "customer");
    }

    /**
     * Fifty rounds of reply-versus-poller on a clock that is exactly at its breach rung.
     *
     * <p>Fifty because the window is a few milliseconds wide and a single round proves
     * only that the two threads did not happen to overlap.
     */
    @RepeatedTest(ITERATIONS)
    @DisplayName("A reply racing the breach poller leaves exactly one consistent outcome")
    void replyVersusBreachIsConsistent() throws Exception {
        Long ticketId = tickets.createId(rest, customerToken, "Race me", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        tickets.post(rest, agentToken, ticketId, "assign", Map.of());

        Long firstResponse = sla.recordId(ticketId, "FIRST_RESPONSE");
        // Past the 60-minute first-response target and standing on the breach rung, so
        // the poller's next pass will breach it rather than merely escalate.
        sla.rewind(firstResponse, 90);
        jdbc.update("UPDATE sla_record SET next_rung = 100 WHERE id = ?", firstResponse);
        sla.makeDue(firstResponse, 1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> reply = pool.submit(() -> {
                ready.countDown();
                release.await(10, TimeUnit.SECONDS);
                return tickets.addMessage(rest, agentToken, ticketId, "Sorry — here now.",
                        "PUBLIC").getStatusCode();
            });
            Future<?> poll = pool.submit(() -> {
                ready.countDown();
                release.await(10, TimeUnit.SECONDS);
                return poller.pollOnce();
            });

            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            reply.get(60, TimeUnit.SECONDS);
            poll.get(60, TimeUnit.SECONDS);
        }

        String state = sla.state(firstResponse);
        boolean breachFired = sla.firedRungs(firstResponse).contains(100);

        // Never neither: the clock is terminal whichever way it went. It cannot still be
        // RUNNING, because both contenders end it.
        assertThat(state).as("the clock must be terminal after the race")
                .isIn("MET", "BREACHED");

        if ("MET".equals(state)) {
            // Never both. A MET clock with a breach row against it is the bug this test
            // exists for: it means the poller acted on a record the reply had already
            // finished with, which is precisely what the row lock prevents.
            assertThat(breachFired).as("a MET clock must not also have fired its breach rung")
                    .isFalse();
        } else {
            // The poller won. The reply still landed — the customer did get an answer —
            // but the clock is honestly recorded as late.
            assertThat(breachFired).as("a BREACHED clock must have a breach row")
                    .isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT breached_at IS NOT NULL FROM sla_record WHERE id = ?",
                    Boolean.class, firstResponse)).isTrue();
        }
    }

    /**
     * Two simultaneous pauses.
     *
     * <p>Both callers wanted the clock paused and it is paused, so both get a {@code 200}.
     * The database guarantee is {@code uq_segment_open}, and the application's answer to
     * losing that race is the affected-row count from the conditional close: the loser
     * sees {@code 0}, concludes the work is already done, and returns the current state
     * instead of inserting a second open segment on top of the first.
     */
    @RepeatedTest(10)
    @DisplayName("Two concurrent pauses leave exactly one open segment")
    void concurrentPauseIsIdempotent() throws Exception {
        Long ticketId = tickets.createId(rest, customerToken, "Pause race", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long resolution = sla.recordId(ticketId, "RESOLUTION");

        List<HttpStatusCode> statuses = fireTogether(
                () -> pause(ticketId).getStatusCode(),
                () -> pause(ticketId).getStatusCode());

        assertThat(statuses).allSatisfy(s -> assertThat(s).isEqualTo(HttpStatus.OK));
        assertThat(sla.state(resolution)).isEqualTo("PAUSED");
        assertThat(sla.openSegmentCount(resolution)).isEqualTo(1);
        // One running segment, closed, and one paused segment. A third row would mean the
        // loser inserted anyway and the unique index simply happened to allow it because
        // the winner's segment had already been closed.
        assertThat(sla.segmentCount(resolution)).isEqualTo(2);
    }

    /**
     * A pause and a resume fired at the same instant.
     *
     * <p>Either order is a legitimate outcome — that is what "simultaneous" means to two
     * agents clicking in different browsers — so the assertion is on the invariant rather
     * than on the result: whatever happens, the clock has exactly one open segment and a
     * state consistent with it.
     */
    @RepeatedTest(10)
    @DisplayName("A pause racing a resume still leaves exactly one open segment")
    void pauseRacingResume() throws Exception {
        Long ticketId = tickets.createId(rest, customerToken, "Pause vs resume", "Body");
        sla.triage(rest, tickets, agentToken, ticketId, "P2");
        Long resolution = sla.recordId(ticketId, "RESOLUTION");
        pause(ticketId);

        fireTogether(
                () -> resume(ticketId).getStatusCode(),
                () -> pause(ticketId).getStatusCode());

        assertThat(sla.openSegmentCount(resolution)).isEqualTo(1);
        String state = sla.state(resolution);
        assertThat(state).isIn("RUNNING", "PAUSED");
        // The open segment agrees with the record. A PAUSED record whose open segment is
        // RUNNING would accrue elapsed time while claiming to be stopped, which is the
        // silent version of this bug and the reason the check is on both.
        assertThat(jdbc.queryForObject("""
                SELECT state FROM sla_clock_segment
                 WHERE sla_record_id = ? AND ended_at IS NULL
                """, String.class, resolution)).isEqualTo(state);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> pause(Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/sla/pause", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "WAITING_ON_CUSTOMER"),
                        TicketTestSupport.authed(agentToken)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> resume(Long ticketId) {
        return rest.exchange("/api/v1/tickets/" + ticketId + "/sla/resume", HttpMethod.POST,
                new HttpEntity<>(Map.of(), TicketTestSupport.authed(agentToken)), Map.class);
    }

    /** Runs two calls genuinely simultaneously — a latch, never a sleep. */
    @SafeVarargs
    private static List<HttpStatusCode> fireTogether(
            java.util.concurrent.Callable<HttpStatusCode>... calls)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<HttpStatusCode>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(calls.length)) {
            for (java.util.concurrent.Callable<HttpStatusCode> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }
}
