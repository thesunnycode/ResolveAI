package com.resolveai.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.LlmStub;
import com.resolveai.platform.outbox.OutboxReaper;
import com.resolveai.platform.outbox.OutboxRepository;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.sla.SlaTestSupport;
import com.resolveai.ticketing.TicketTestSupport;
import com.resolveai.ticketing.domain.Category;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What happens when two workers, or fifty tickets, arrive at once.
 *
 * <p>Three claims are made executable here: an event is claimed by exactly one worker, a
 * crash between the model call and the write leaves nothing half-done, and a burst of
 * tickets spreads across the available agents instead of piling onto one.
 *
 * <p>The last of those carries a {@code @Disabled} negative control, which is the most
 * useful test in the file precisely because it never runs in CI: it demonstrates that
 * {@code SKIP LOCKED} is load-bearing rather than decorative.
 */
class TriageConcurrencyTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        LlmStub.start();
        registry.add("spring.ai.openai.base-url", LlmStub::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "stub-key");
    }

    @Autowired AuthTestSupport auth;
    @Autowired TicketTestSupport tickets;
    @Autowired SlaTestSupport slaSupport;
    @Autowired TriageWorker worker;
    @Autowired WorkerRuntime runtime;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxReaper reaper;
    @Autowired AiPolicyService policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private AuthTestSupport.SeededTenant tenant;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("burst");
        slaSupport.seedPolicies(tenant.tenantId(), "PRO");
        slaSupport.makeCalendarAlwaysOpen(tenant.tenantId());
        jdbc.update("UPDATE agent_profile SET shift_start = NULL, shift_end = NULL, "
                    + "max_concurrent = 50 WHERE tenant_id = ?", tenant.tenantId());

        policies.ensureExists(tenant.tenantId());
        // 50 tickets at a few hundred micros each; the default budget is generous, but
        // a budget refusal here would look like a routing bug.
        policies.update(tenant.tenantId(), true, List.of("openai"), true,
                1_000_000_000L, 30);
        policies.evict(tenant.tenantId());

        customerToken = auth.accessToken(rest, "burst", "customer");

        LlmStub.reset();
        LlmStub.returnsEmbedding();
        LlmStub.returnsSignals(Category.PAYMENT);

        // The embedding cache key is a hash of the redacted text and lives in Redis,
        // which auth.wipe() does not touch - so two tests using the same ticket wording
        // share a cache entry and the second one records zero provider calls. That is
        // the cache working exactly as designed, and it makes a per-test assertion on
        // the call count meaningless unless the cache starts empty.
        var keys = redis.keys("emb:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ── Task 30: one event, one claim, one call ─────────────────────────────

    @Test
    @DisplayName("Two workers racing for one event produce one analysis and one model call")
    void duplicateClaimIsImpossible() throws Exception {
        Long ticketId = createTicket();

        // Both threads are released together, so they contend for the same row rather
        // than politely following one another.
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> handled = inParallel(2, () -> {
            release.await(5, TimeUnit.SECONDS);
            return runtime.runOnce(worker);
        }, release);

        // Exactly one worker got the event; the other claimed nothing and returned 0.
        assertThat(handled).containsExactlyInAnyOrder(1, 0);
        assertThat(analysisCount(ticketId)).isEqualTo(1);

        // The row count alone is not enough. A second worker could have paid for a
        // model call and then lost the insert to the unique constraint - one row, two
        // bills, and nothing in the database to show for the second. The request count
        // is what catches that.
        assertThat(LlmStub.chatCallCount())
                .as("exactly one classification was paid for")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("uq_analysis_ticket_prompt rejects a genuine duplicate row")
    void theConstraintIsReal() {
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT prompt_version_id, attempt FROM ai_analysis WHERE ticket_id = ?",
                ticketId);

        // Not a hypothetical: the idempotency of the whole pipeline rests on this
        // constraint existing, so the test asserts the database enforces it rather than
        // trusting that the DDL says so.
        assertThat(insertDuplicateAnalysis(ticketId, existing))
                .as("a second row with the same (ticket, prompt, attempt) must be refused")
                .isFalse();
    }

    // ── Task 31: crash between the model call and the write ─────────────────

    @Test
    @DisplayName("A worker that dies mid-flight leaves nothing behind, and the reaper "
                 + "hands the event on")
    void crashBeforeTheWriteLeavesNoPartialState() {
        Long ticketId = createTicket();

        // The crash, simulated at the level that matters: the event is claimed with a
        // visibility timeout and then the process disappears. Nothing marks it done,
        // nothing marks it failed - which is exactly what a `kill -9` between the model
        // call and tx2 looks like from the database's point of view.
        var claimed = outbox.claim(List.copyOf(worker.handles()), 5,
                java.time.Duration.ofMillis(-1));
        assertThat(claimed).hasSize(1);

        // Nothing was written, because the "worker" never got to tx2.
        assertThat(analysisCount(ticketId)).isZero();
        assertThat(decisionCount(ticketId)).isZero();
        assertThat(slaCount(ticketId)).isZero();

        // The reaper notices the expired lease and puts the event back.
        assertThat(reaper.reapOnce()).hasSize(1);
        assertThat(outboxStatus(ticketId)).isEqualTo("PENDING");

        // A second worker finishes the job.
        assertThat(runtime.runOnce(worker)).isEqualTo(1);
        assertThat(analysisCount(ticketId)).isEqualTo(1);
        assertThat(decisionCount(ticketId)).isEqualTo(1);
        assertThat(slaCount(ticketId)).isEqualTo(2);
    }

    @Test
    @DisplayName("A redelivery re-pays for the classification but not for the embedding")
    void redeliveryCostIsBoundedByTheCaches() {
        Long ticketId = createTicket();
        runtime.runOnce(worker);

        assertThat(LlmStub.chatCallCount()).isEqualTo(1);
        assertThat(LlmStub.embeddingCallCount()).isEqualTo(1);

        // Put the event back, as the reaper would after a crash that happened between
        // the model call and the commit.
        jdbc.update("""
                UPDATE outbox_event SET status = 'PENDING', locked_until = NULL,
                       processed_at = NULL
                 WHERE aggregate_id = ?
                """, ticketId);
        runtime.runOnce(worker);

        // The embedding is free: its cache key is a hash of the redacted text, which is
        // byte-identical on the retry, so Redis answers and no request is made.
        assertThat(LlmStub.embeddingCallCount())
                .as("the content-hash cache absorbs the retry")
                .isEqualTo(1);

        // THE CLASSIFICATION IS NOT FREE, and it is worth being precise about that
        // rather than claiming the caches cover everything. There is no chat cache -
        // deliberately, because a cached classification would silently survive a prompt
        // change and the eval suite would measure the cache instead of the prompt. So a
        // redelivery costs one more model call.
        //
        // What bounds it is the claim, not a cache: an event is claimed by exactly one
        // worker for five minutes, so a redelivery only happens after a genuine crash
        // or a genuinely stuck call. The wasted spend is one call per crash, and the
        // unique constraint makes sure the second result cannot corrupt the first.
        assertThat(LlmStub.chatCallCount()).isEqualTo(2);
        assertThat(analysisCount(ticketId))
                .as("and the duplicate result is discarded, not written")
                .isEqualTo(1);
    }

    // ── Task 32: load distribution ──────────────────────────────────────────

    @Test
    @DisplayName("Fifty tickets across five agents land evenly, with a skew of at most one")
    void burstSpreadsAcrossAgents() throws Exception {
        List<Long> agents = new ArrayList<>(List.of(tenant.agentId()));
        for (int i = 0; i < 4; i++) {
            agents.add(agent("burst" + i));
        }

        List<Long> ticketIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ticketIds.add(createTicket());
        }

        // Drained through the real runtime, in batches of five on virtual threads, so
        // the routers genuinely contend for agent rows.
        //
        // The loop runs until the QUEUE is empty, not until fifty events have been
        // claimed. Counting claims was the first version and it is subtly wrong: a
        // failed event is retried, so it is claimed twice, and the counter reaches
        // fifty while fewer than fifty tickets have actually been triaged. The test
        // then stops early and reports a load imbalance that is really a drain that
        // never finished - a failure message pointing at the wrong component.
        for (int pass = 0; pass < 60 && unfinishedEvents() > 0; pass++) {
            runtime.runOnce(worker);
        }
        assertThat(unfinishedEvents())
                .as("the queue must drain; outbox=%s analyses=%s",
                        outboxByStatus(), analysesByStatus())
                .isZero();

        List<Integer> loads = agents.stream().map(this::openCount).toList();
        int total = loads.stream().mapToInt(Integer::intValue).sum();
        int skew = loads.stream().mapToInt(Integer::intValue).max().orElseThrow()
                   - loads.stream().mapToInt(Integer::intValue).min().orElseThrow();

        assertThat(total).as("""
                every ticket booked exactly one slot
                  loads      : %s
                  analyses   : %s
                  outbox     : %s
                  unassigned : %s
                  chat calls : %s""", loads, analysesByStatus(), outboxByStatus(),
                unassignedCount(ticketIds), com.resolveai.platform.ai.LlmStub.chatCallCount())
                .isEqualTo(50);
        assertThat(unassignedCount(ticketIds)).isZero();
        assertThat(skew).as("load skew across %s", loads).isLessThanOrEqualTo(1);
        assertThat(loads).allSatisfy(load -> assertThat(load).isLessThanOrEqualTo(50));
    }

    /**
     * <b>The negative control: the same claim with {@code SKIP LOCKED} removed.</b>
     *
     * <p>Disabled because it is a demonstration rather than a regression guard — enable
     * it by hand, read the numbers it prints into its assertion messages, and disable it
     * again. It is the most useful test in this file precisely because it never runs in
     * CI: it is the evidence that two words in one query are load-bearing.
     *
     * <h2>Measured on this machine</h2>
     *
     * <pre>
     *   SKIP LOCKED       267 ms   loads [1, 1, 1, 1, 1, 1, 1, 1, 0, 0]
     *   plain FOR UPDATE  907 ms   loads [8, 0, 0, 0, 0, 0, 0, 0, 0, 0]
     * </pre>
     *
     * <p>Eight concurrent routers, ten available agents, each holding its lock for
     * 100 ms of simulated work. Without {@code SKIP LOCKED} <b>every one of the eight
     * lands on the same agent</b> and they run one at a time; with it, eight different
     * agents and the whole burst overlaps.
     *
     * <p>Two distinct failures, and the first is the one that hurts silently:
     *
     * <ul>
     *   <li><b>The distribution collapses.</b> All eight routers evaluate
     *       {@code ORDER BY open_count} against the same committed snapshot, all pick
     *       the same least-loaded agent, and each one in turn wakes to find the row
     *       still satisfies the {@code WHERE} clause — so it takes it anyway. One agent
     *       gets the burst, nine sit idle, and <b>every request returns 200</b>.
     *   <li><b>The routers serialise.</b> 907 ms against 267 ms here, and the ratio
     *       grows with the burst: a throughput ceiling that only appears under exactly
     *       the load it matters under.
     * </ul>
     *
     * <p>The hold matters. An earlier version of this test committed immediately after
     * the claim, and the threads barely overlapped — the skew came out at 2 and the
     * timings were indistinguishable, which would have read as "plain {@code FOR UPDATE}
     * is fine". A concurrency control that only shows itself when the lock is held for
     * real work needs a test that holds the lock for real work.
     */
    @Test
    @Disabled("Negative control: a demonstration, not a regression guard. Enable by hand.")
    @DisplayName("Without SKIP LOCKED the burst collapses onto one agent and the load goes uneven")
    void withoutSkipLockedTheRoutersSerialise() throws Exception {
        List<Long> agents = new ArrayList<>(List.of(tenant.agentId()));
        for (int i = 0; i < 9; i++) {
            agents.add(agent("plain" + i));
        }

        // Eight concurrent routers, under the Hikari maximum of ten so the pool is not
        // itself the bottleneck being measured.
        Measured withSkipLocked = measureClaims(agents, true);
        resetLoads();
        Measured plain = measureClaims(agents, false);

        assertThat(plain.elapsedMillis())
                .as("plain FOR UPDATE %s ms / loads %s vs SKIP LOCKED %s ms / loads %s",
                        plain.elapsedMillis(), plain.loads(),
                        withSkipLocked.elapsedMillis(), withSkipLocked.loads())
                .isGreaterThan(withSkipLocked.elapsedMillis() * 2);

        assertThat(plain.skew())
                .as("plain FOR UPDATE load %s vs SKIP LOCKED load %s",
                        plain.loads(), withSkipLocked.loads())
                .isGreaterThan(withSkipLocked.skew());
    }

    private record Measured(long elapsedMillis, List<Integer> loads, int skew) {
    }

    private Measured measureClaims(List<Long> agents, boolean skipLocked) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        long startedAt = System.nanoTime();
        inParallel(8, () -> {
            release.await(5, TimeUnit.SECONDS);
            return claimDirectly(skipLocked);
        }, release);
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;

        List<Integer> loads = agents.stream().map(this::openCount).toList();
        int skew = loads.stream().mapToInt(Integer::intValue).max().orElseThrow()
                   - loads.stream().mapToInt(Integer::intValue).min().orElseThrow();
        return new Measured(elapsed, loads, skew);
    }

    /** The router's claim, with and without the two words under test. */
    private Integer claimDirectly(boolean skipLocked) {
        return new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).execute(status -> {
                    List<Long> found = jdbc.queryForList("""
                            SELECT ap.user_id FROM agent_profile ap
                             WHERE ap.tenant_id = ? AND ap.is_available = TRUE
                               AND ap.open_count < ap.max_concurrent
                             ORDER BY ap.open_count ASC, ap.id ASC
                             LIMIT 1
                             FOR UPDATE"""
                            + (skipLocked ? " SKIP LOCKED" : ""),
                            Long.class, tenant.tenantId());
                    if (found.isEmpty()) {
                        return 0;
                    }
                    // The lock is held while the router does its work: resolving the
                    // team, writing the assignment, recording the event. Without this
                    // the transactions are too short to overlap and the comparison
                    // measures nothing - the misleading version of this test, which
                    // would suggest plain FOR UPDATE is fine.
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    jdbc.update("UPDATE agent_profile SET open_count = open_count + 1 "
                                + "WHERE user_id = ?", found.get(0));
                    return 1;
                });
    }

    private void resetLoads() {
        jdbc.update("UPDATE agent_profile SET open_count = 0 WHERE tenant_id = ?",
                tenant.tenantId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private <T> List<T> inParallel(int threads, Callable<T> task, CountDownLatch release)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(task));
            }
            release.countDown();
            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private Long createTicket() {
        return tickets.createId(rest, customerToken, "Payment stuck",
                "UPI debited but the order is still unpaid.");
    }

    private Long agent(String localPart) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO app_user (tenant_id, email, password_hash, full_name, role, team_id)
                VALUES (?, ?, 'x', ?, 'AGENT', ?) RETURNING id
                """, Long.class, tenant.tenantId(), localPart + "@burst.test",
                "Agent " + localPart, tenant.teamId());
        jdbc.update("""
                INSERT INTO agent_profile (user_id, tenant_id, max_concurrent, open_count)
                VALUES (?, ?, 50, 0)
                """, userId, tenant.tenantId());
        return userId;
    }

    private boolean insertDuplicateAnalysis(Long ticketId, Map<String, Object> existing) {
        try {
            jdbc.update("""
                    INSERT INTO ai_analysis (ticket_id, tenant_id, prompt_version_id, model_id,
                                             signals, attempt, status)
                    VALUES (?, ?, ?, 'gpt-4.1-mini', '{}'::jsonb, ?, 'OK')
                    """, ticketId, tenant.tenantId(), existing.get("prompt_version_id"),
                    existing.get("attempt"));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }

    private int openCount(Long userId) {
        return jdbc.queryForObject("SELECT open_count FROM agent_profile WHERE user_id = ?",
                Integer.class, userId);
    }

    private int unassignedCount(List<Long> ticketIds) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket
                 WHERE tenant_id = ? AND assignee_id IS NULL
                """, Integer.class, tenant.tenantId());
    }

    /** Events still to be processed: anything not DONE and not dead-lettered. */
    private int unfinishedEvents() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING', 'IN_FLIGHT')
                """, Integer.class);
    }

    private Map<String, Object> analysesByStatus() {
        return jdbc.query("SELECT status, COUNT(*) AS n FROM ai_analysis GROUP BY status",
                rs -> {
                    Map<String, Object> counts = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("status"), rs.getInt("n"));
                    }
                    return counts;
                });
    }

    private Map<String, Object> outboxByStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS n, MIN(last_error) AS sample
                  FROM outbox_event GROUP BY status
                """,
                rs -> {
                    Map<String, Object> counts = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("status"),
                                rs.getInt("n") + " (" + rs.getString("sample") + ")");
                    }
                    return counts;
                });
    }

    private int analysisCount(Long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private int decisionCount(Long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM priority_decision WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private int slaCount(Long ticketId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sla_record WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private String outboxStatus(Long ticketId) {
        return jdbc.queryForObject("""
                SELECT status FROM outbox_event WHERE aggregate_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, ticketId);
    }
}
