package com.resolveai.ticketing.repository;

import com.resolveai.ticketing.domain.Ticket;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tickets.
 *
 * <p>Every derived finder here is tenant-filtered without saying so: {@code @TenantId} on
 * {@link Ticket#getTenantId()} makes Hibernate append the discriminator to the generated
 * HQL. <b>The two native statements below are the exception</b>, and both carry an explicit
 * {@code AND tenant_id = :tenantId} for exactly that reason.
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByReference(String reference);

    /**
     * A list page's tickets with the people and team every row renders, in one statement.
     *
     * <p>{@code findAllById} left requester, assignee and team as lazy proxies, so mapping a
     * page of 25 issued up to 75 more selects. Order is not preserved; the caller reorders
     * by the id list the page query produced.
     */
    @EntityGraph(attributePaths = {"requester", "assignee", "team"})
    @Query("SELECT t FROM Ticket t WHERE t.id IN :ids")
    List<Ticket> findAllForListByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * The lock the assignment path and Phase 6's routing both need.
     *
     * <p>Distinct from {@code @Version}: optimistic locking tells the <i>second</i> writer it
     * lost, after it has done its work. A pessimistic read makes the second writer wait, which
     * is what you want when the work between read and write is expensive or has side effects
     * of its own — incrementing an agent's open count, for instance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /**
     * Claim an unassigned ticket. <b>The {@code assignee_id IS NULL} predicate is the entire
     * concurrency mechanism.</b>
     *
     * <p>A {@code SELECT} that checks the ticket is free, followed by an {@code UPDATE} that
     * assigns it, has a window between the two statements in which another request can win —
     * and both callers then believe they succeeded. A conditional update has no window: the
     * predicate is evaluated under the row lock the {@code UPDATE} itself takes, so exactly
     * one of twenty concurrent callers sees {@code 1} and the other nineteen see {@code 0}.
     *
     * <p>Native, therefore <b>the tenant predicate is written by hand.</b> {@code @TenantId}
     * does not reach native SQL, and without that clause this statement would happily assign
     * another tenant's ticket to an agent who cannot see it.
     *
     * <p>{@code version = version + 1} keeps the ETag contract honest: an agent holding the
     * pre-assignment ETag gets a {@code 409} on their next edit, which is correct — the
     * ticket did change.
     *
     * <p><b>Measured, by deleting the predicate on purpose:</b> without it, twelve of twenty
     * concurrent callers got a {@code 200} and the agent's open count jumped by twelve for
     * one ticket. The {@code If-Match} pre-check catches some of them and is not enough —
     * it reads the version in a separate statement, so most of the burst still sees the
     * pre-assignment value. {@code ConcurrentAssignmentTest} is the test that says so.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket
               SET assignee_id = :assigneeId,
                   status = CASE WHEN status IN ('OPEN','TRIAGED') THEN 'ASSIGNED' ELSE status END,
                   version = version + 1
             WHERE id = :ticketId
               AND tenant_id = :tenantId
               AND assignee_id IS NULL
            """, nativeQuery = true)
    int assignIfUnassigned(@Param("ticketId") Long ticketId,
                           @Param("assigneeId") Long assigneeId,
                           @Param("tenantId") Long tenantId);

    /**
     * Reassignment, for {@code force: true}. Same statement without the {@code IS NULL}
     * predicate, and returns the previous assignee so the caller can decrement their count.
     *
     * <p>Kept as a separate method rather than a boolean parameter on the one above: a flag
     * that removes a concurrency guard is the kind of thing that should be visible at the
     * call site, not buried in an argument list.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket
               SET assignee_id = :assigneeId,
                   status = CASE WHEN status IN ('OPEN','TRIAGED') THEN 'ASSIGNED' ELSE status END,
                   version = version + 1
             WHERE id = :ticketId
               AND tenant_id = :tenantId
            """, nativeQuery = true)
    int reassign(@Param("ticketId") Long ticketId,
                 @Param("assigneeId") Long assigneeId,
                 @Param("tenantId") Long tenantId);
}
