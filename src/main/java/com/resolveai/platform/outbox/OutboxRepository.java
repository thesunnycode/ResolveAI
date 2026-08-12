package com.resolveai.platform.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The queue itself: claim, complete, fail, reap.
 *
 * <h2>Claim-and-return in one statement</h2>
 *
 * <p>The obvious shape is {@code SELECT … FOR UPDATE SKIP LOCKED} followed by an
 * {@code UPDATE}. It is wrong in a way that only shows under load: between the two
 * statements the rows are locked but <i>not yet marked</i>, and the moment the selecting
 * transaction commits, a second worker whose own {@code SELECT} ran in that window sees
 * them as {@code PENDING} again. The window is small and the duplicate is silent.
 *
 * <p>An {@code UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *} has no
 * window at all: the predicate is evaluated under the lock the UPDATE itself takes, and
 * the rows come back already claimed. One round trip, one statement, no gap.
 *
 * <h2>Why {@code SKIP LOCKED} and not {@code NOWAIT} or plain {@code FOR UPDATE}</h2>
 *
 * <p>Plain {@code FOR UPDATE} makes the second worker <i>wait</i> for the first — the
 * workers serialise, and adding instances buys nothing. {@code NOWAIT} makes it throw.
 * {@code SKIP LOCKED} makes it step over the claimed rows and take the next ones, which
 * is the entire reason a database can be used as a work queue at all.
 *
 * <h2>Why this is not a Spring Data JPA repository</h2>
 *
 * <p>See {@link OutboxEvent}. The short version: Hibernate's first-level cache and dirty
 * checking are actively harmful on a queue claim, and {@code @Version} would replace a
 * lock-free queue with a contended one.
 *
 * <p>It is also <b>deliberately cross-tenant</b>. Workers are system jobs with no request
 * and no tenant; the tenant comes off the claimed row and the worker sets it before doing
 * anything tenant-scoped, exactly as the SLA poller does.
 */
@Repository
public class OutboxRepository {

    /**
     * {@code event_type = ANY(?)} rather than {@code IN (…)}: a single array parameter
     * means one prepared statement whatever the number of types, so Postgres keeps one
     * plan for it instead of a new entry per distinct arity.
     */
    private static final String CLAIM = """
            UPDATE outbox_event
               SET status = 'IN_FLIGHT',
                   locked_until = NOW() + (? * INTERVAL '1 second'),
                   attempts = attempts + 1
             WHERE id IN (
                 SELECT id FROM outbox_event
                  WHERE status = 'PENDING'
                    AND next_attempt_at <= NOW()
                    AND event_type = ANY(?)
                  ORDER BY next_attempt_at
                  LIMIT ?
                  FOR UPDATE SKIP LOCKED
             )
            RETURNING *
            """;

    private static final RowMapper<OutboxEvent> MAPPER = OutboxRepository::map;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims up to {@code batchSize} due events of the given types.
     *
     * @param visibility how long the claim holds. A worker that has not finished by then
     *                   has its event reaped and redelivered, so this must exceed the
     *                   worker's realistic worst case — an LLM call plus its retries —
     *                   or the system cheerfully processes everything twice.
     */
    public List<OutboxEvent> claim(List<EventType> types, int batchSize, Duration visibility) {
        if (types.isEmpty()) {
            return List.of();
        }
        String[] names = types.stream().map(Enum::name).toArray(String[]::new);
        return jdbc.query(con -> {
            var ps = con.prepareStatement(CLAIM);
            ps.setLong(1, visibility.toSeconds());
            ps.setArray(2, con.createArrayOf("varchar", names));
            ps.setInt(3, batchSize);
            return ps;
        }, MAPPER);
    }

    /** Done. The row stays for audit until the pruner takes it. */
    public void markDone(Long id) {
        jdbc.update("""
                UPDATE outbox_event
                   SET status = 'DONE', processed_at = NOW(), locked_until = NULL,
                       last_error = NULL
                 WHERE id = ?
                """, id);
    }

    /**
     * Failed, and worth another go.
     *
     * <p>{@code locked_until} is cleared as well as the status reset: an event that is
     * {@code PENDING} with a stale lock is invisible to the claim query's predicate but
     * visible to the reaper, which would then "recover" a row that was never stuck.
     */
    public void markForRetry(Long id, OffsetDateTime nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE outbox_event
                   SET status = 'PENDING', next_attempt_at = ?, locked_until = NULL,
                       last_error = ?
                 WHERE id = ?
                """, nextAttemptAt, error, id);
    }

    /** Out of attempts, or refused outright. Nothing retries this without a human. */
    public void markDead(Long id, String error) {
        jdbc.update("""
                UPDATE outbox_event
                   SET status = 'DEAD', locked_until = NULL, last_error = ?, processed_at = NOW()
                 WHERE id = ?
                """, error, id);
    }

    /**
     * Returns events whose visibility timeout has passed to {@code PENDING}.
     *
     * @return the ids reaped, so the caller can log them individually — a count alone
     *         tells you something is wrong but not what
     */
    public List<Long> reapExpired() {
        return jdbc.queryForList("""
                UPDATE outbox_event
                   SET status = 'PENDING', locked_until = NULL
                 WHERE status = 'IN_FLIGHT' AND locked_until < NOW()
                RETURNING id
                """, Long.class);
    }

    public Optional<OutboxEvent> findById(Long id) {
        return jdbc.query("SELECT * FROM outbox_event WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** For the admin DLQ view, and for tests that assert on what is queued. */
    public List<OutboxEvent> findByAggregate(String aggregateType, Long aggregateId) {
        return jdbc.query("""
                SELECT * FROM outbox_event
                 WHERE aggregate_type = ? AND aggregate_id = ?
                 ORDER BY id
                """, MAPPER, aggregateType, aggregateId);
    }

    public long countByStatus(OutboxStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status = ?", Long.class, status.name());
        return count == null ? 0L : count;
    }

    /** Seconds since the oldest unfinished event was written; 0 when the queue is clear. */
    public long oldestPendingAgeSeconds() {
        Long age = jdbc.queryForObject("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0)::bigint
                  FROM outbox_event WHERE status IN ('PENDING', 'IN_FLIGHT')
                """, Long.class);
        return age == null ? 0L : age;
    }

    private static OutboxEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                rs.getLong("id"),
                rs.getObject("tenant_id", Long.class),
                rs.getString("aggregate_type"),
                rs.getLong("aggregate_id"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getShort("attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("processed_at", OffsetDateTime.class));
    }
}
