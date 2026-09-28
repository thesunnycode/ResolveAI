package com.resolveai.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Twenty agents click "Assign to me" at the same instant.
 *
 * <h2>The mechanism under test</h2>
 *
 * <p>Assignment is a conditional update with an affected-row check:
 * {@code UPDATE ticket SET assignee_id = ? WHERE id = ? AND assignee_id IS NULL}. The
 * predicate is evaluated under the row lock the {@code UPDATE} itself takes, so there is no
 * window. The obvious alternative — {@code SELECT} to check it is free, then {@code UPDATE}
 * to take it — has a gap between the two statements in which another request can win, and
 * <b>both callers then get a 200 and believe they own the ticket.</b>
 *
 * <p>Nothing about that failure is visible without a test like this one. It never happens
 * under manual testing, it never happens under load unless two people happen to click
 * within the same few milliseconds, and when it does happen the symptom is two agents
 * replying to the same customer — which gets reported as a UI bug.
 *
 * <h2>Released by a latch, never a sleep</h2>
 *
 * <p>A concurrency test built on {@code Thread.sleep} is flaky, gets {@code @Disabled}
 * within a week, and then protects nothing. A {@link CountDownLatch} makes the threads
 * genuinely simultaneous and makes the test deterministic.
 */
class ConcurrentAssignmentTest extends IntegrationTestBase {

    private static final int CONTENDERS = 20;

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired JdbcTemplate jdbc;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("race");
        agentToken = auth.accessToken(rest, "race", "agent");
        customerToken = auth.accessToken(rest, "race", "customer");
    }

    /**
     * Run ten times in a row. <b>A concurrency test that passes once has not passed.</b>
     * The window this closes is a few hundred microseconds wide, and a single green run is
     * as likely to mean the threads did not actually overlap as it is to mean the code is
     * correct.
     */
    @RepeatedTest(10)
    @DisplayName("20 concurrent assignments produce exactly one 200 and nineteen 409s")
    void exactlyOneWinner() throws Exception {
        Long ticketId = tickets.createId(rest, customerToken, "Contended", "Body");
        String etag = tickets.etag(rest, agentToken, ticketId);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(CONTENDERS);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    ResponseEntity<Map> response = tickets.postWithEtag(rest, agentToken,
                            ticketId, "assign", Map.of(), etag);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        ok.incrementAndGet();
                    } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(ok.get()).as("exactly one assignment must succeed").isEqualTo(1);
        // 409 either from ALREADY_ASSIGNED (the conditional update matched nothing) or
        // from VERSION_CONFLICT (the ETag pre-check saw the winner's bump first). Both are
        // correct answers to "somebody else got there": the caller must reload either way.
        assertThat(conflict.get()).isEqualTo(CONTENDERS - 1);
        assertThat(other.get()).as("no 500s and no silent successes").isZero();

        // The counter moved exactly once. A read-modify-write on open_count would have
        // moved it somewhere between one and twenty and reported success every time.
        assertThat(openCount(tenant.agentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM ticket WHERE id = ?",
                Long.class, ticketId)).isEqualTo(tenant.agentId());
    }

    /**
     * Load distribution: 50 tickets across 5 available agents, claimed concurrently.
     *
     * <p><b>This is the test with a story attached.</b> An earlier project of mine assigns
     * to the least-loaded agent too, and that implementation has exactly this race — it
     * reads every agent's load, picks the minimum, and writes, so a concurrent burst reads
     * the same minimum repeatedly and piles the whole burst onto one person. Here the
     * capacity read takes a row lock and the counter is incremented by the database, so the
     * skew stays at one.
     */
    @Test
    @DisplayName("50 tickets across 5 agents leave a load skew of at most 1")
    void loadIsDistributedUnderConcurrency() throws Exception {
        List<Long> agentIds = seedAgents(5);
        List<Long> ticketIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ticketIds.add(tickets.createId(rest, customerToken, "Queued " + i, "Body"));
        }

        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < ticketIds.size(); i++) {
                Long ticketId = ticketIds.get(i);
                // Round-robin stands in for Phase 6's routing policy, which does not exist
                // yet. What is under test here is not the choice of agent - it is that
                // fifty concurrent claims against five capacity counters land where they
                // were sent, without a lost update.
                Long target = agentIds.get(i % agentIds.size());
                futures.add(pool.submit(() -> {
                    release.await(10, TimeUnit.SECONDS);
                    return tickets.postWithEtag(rest, leadToken(), ticketId, "assign",
                            Map.of("assigneeId", String.valueOf(target)),
                            tickets.etag(rest, leadToken(), ticketId));
                }));
            }
            release.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        List<Integer> counts = agentIds.stream().map(this::openCount).toList();
        int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);

        assertThat(counts.stream().mapToInt(Integer::intValue).sum())
                .as("every claim that returned 200 incremented exactly one counter")
                .isEqualTo(50);
        assertThat(max - min).as("load skew across %s", counts).isLessThanOrEqualTo(1);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private String cachedLeadToken;

    private String leadToken() {
        if (cachedLeadToken == null) {
            jdbc.update("UPDATE app_user SET role = 'TEAM_LEAD' WHERE id = ?", tenant.adminId());
            cachedLeadToken = auth.accessToken(rest, "race", "admin");
        }
        return cachedLeadToken;
    }

    private List<Long> seedAgents(int count) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",
                String.class, tenant.agentId());
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long id = jdbc.queryForObject("""
                    INSERT INTO app_user (tenant_id, email, password_hash, full_name, role, team_id)
                    VALUES (?, ?, ?, ?, 'AGENT', ?) RETURNING id
                    """, Long.class, tenant.tenantId(), "load" + i + "@race.test", hash,
                    "Load Agent " + i, tenant.teamId());
            jdbc.update("""
                    INSERT INTO agent_profile (user_id, tenant_id, max_concurrent)
                    VALUES (?, ?, 50)
                    """, id, tenant.tenantId());
            ids.add(id);
        }
        return ids;
    }

    private int openCount(Long userId) {
        Integer value = jdbc.queryForObject(
                "SELECT open_count FROM agent_profile WHERE user_id = ?", Integer.class, userId);
        return value == null ? 0 : value;
    }
}
