package com.resolveai.sla.repository;

import com.resolveai.sla.domain.SlaKind;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlaRecordRepository extends JpaRepository<SlaRecord, Long> {

    List<SlaRecord> findByTicketId(Long ticketId);

    /** Every clock for a page of tickets at once - the list's SLA column. */
    List<SlaRecord> findByTicketIdIn(Collection<Long> ticketIds);

    Optional<SlaRecord> findByTicketIdAndKindAndStateNot(Long ticketId, SlaKind kind,
                                                         SlaState state);

    /**
     * <b>The most important query in the system.</b>
     *
     * <p>Claims up to {@code batchSize} due records and returns <i>ids only</i>, in a
     * transaction that ends immediately.
     *
     * <h2>Why ids, and why a separate short transaction</h2>
     *
     * <p>Processing all two hundred records inside this transaction would hold every one
     * of these row locks for the whole batch — and an agent replying to any of those two
     * hundred tickets would block behind it. Claiming ids in milliseconds and then
     * re-locking each record in its own transaction keeps every lock short.
     *
     * <h2>Why {@code SKIP LOCKED}</h2>
     *
     * <p>Two application instances polling at once must not queue behind each other. With
     * {@code SKIP LOCKED} the second instance steps over the rows the first has claimed
     * and takes the next ones, so the pollers scale horizontally with no coordination.
     *
     * <h2>Why the cheap claim is safe</h2>
     *
     * <p>Because it is at-least-once, not exactly-once: two runs can pick the same id
     * after the first has released its lock. That is fine, and it is fine <i>because</i>
     * {@code uq_escalation_rung} makes firing a rung twice a no-op. A database constraint
     * buying a simpler application design.
     *
     * <h2>Why this query is deliberately cross-tenant</h2>
     *
     * <p>It is a system job; there is no request and no tenant. Native SQL gets no
     * {@code @TenantId} filter anyway, which here is what we want — and is exactly why
     * each record must then be processed inside {@code TenantContext.runAs}. Without that,
     * everything the per-record work touches runs under the no-tenant sentinel.
     *
     * <p>{@code idx_sla_poller} is partial on {@code state = 'RUNNING'}, so the cost of a
     * poll is proportional to the number of <i>due</i> records, not to the number of open
     * tickets.
     */
    /**
     * <p><b>The tenant id comes back with the row id, and that is not incidental.</b>
     * {@code @TenantId} is resolved when the Hibernate session opens, not when a row is
     * read, so the poller has to know whose record it is <i>before</i> it starts the
     * transaction that processes it. Returning the id alone forced the tenant to be set
     * after the session already existed, and every lazy association loaded inside it —
     * the ticket, most obviously — then resolved under the no-tenant sentinel and threw
     * {@code EntityNotFoundException} on a row that plainly exists.
     */
    /**
     * <p><b>{@code :now} is a parameter, supplied by {@code DatabaseClock}.</b> Inlining
     * {@code NOW()} would read the same clock and be equivalent, but passing it makes the
     * rule visible at the call site: the instant a deadline is <i>compared</i> against has
     * to come from the same clock that <i>wrote</i> it. When the deadlines were written
     * from the JVM and claimed with the database's {@code NOW()}, a containerised Postgres
     * running a second behind its host left due rungs unclaimed — a poller that "sometimes
     * misses one", which is close to undiagnosable from the outside.
     */
    @Query(value = """
            SELECT id, tenant_id FROM sla_record
             WHERE state = 'RUNNING' AND next_deadline_at <= :now
             ORDER BY next_deadline_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Object[]> claimDue(@Param("now") java.time.OffsetDateTime now,
                            @Param("batchSize") int batchSize);

    /**
     * Re-read one record under a row lock.
     *
     * <p>Cross-tenant by design, like the claim above: the poller has already decided which
     * record to process and needs to read it before it knows whose it is. Everything the
     * caller does <i>with</i> it happens inside {@code TenantContext.runAs}.
     */
    // No @Lock here, deliberately: the FOR UPDATE is in the SQL, and Hibernate refuses
    // ("Illegal attempt to set lock mode for a native query") if both are present. The
    // annotation would be redundant even if it were allowed - it exists to make Hibernate
    // append the clause this query already spells out.
    @Query(value = "SELECT * FROM sla_record WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<SlaRecord> findByIdForUpdate(@Param("id") Long id);

    /**
     * Running clocks whose deadline is inside the window, for the at-risk queue.
     *
     * <p>Tenant-filtered by {@code @TenantId} because this one runs on a request thread,
     * unlike the two above.
     */
    @Query("""
            SELECT r FROM SlaRecord r
             WHERE r.state = 'RUNNING'
               AND r.nextDeadlineAt IS NOT NULL
             ORDER BY r.nextDeadlineAt ASC, r.id ASC
            """)
    List<SlaRecord> findRunning();
}
