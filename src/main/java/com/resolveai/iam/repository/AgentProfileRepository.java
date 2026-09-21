package com.resolveai.iam.repository;

import com.resolveai.iam.domain.AgentProfile;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentProfileRepository extends JpaRepository<AgentProfile, Long> {

    Optional<AgentProfile> findByUserId(Long userId);

    /**
     * The profile, locked for the rest of the transaction.
     *
     * <p>The capacity check and the assignment that follows it must not be separable. Without
     * the lock, five leads assigning to the same agent all read {@code openCount = 14} against
     * a ceiling of 15, all pass the check, and the agent ends up with nineteen tickets — and
     * because each request succeeded, nothing anywhere reports it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM AgentProfile p WHERE p.user.id = :userId")
    Optional<AgentProfile> findByUserIdForUpdate(@Param("userId") Long userId);

    /** Available agents in a team, for Phase 6's routing and the load-distribution test. */
    @Query("""
            SELECT p FROM AgentProfile p
             WHERE p.user.team.id = :teamId
               AND p.available = true
               AND p.openCount < p.maxConcurrent
             ORDER BY p.openCount ASC, p.user.id ASC
            """)
    List<AgentProfile> findAvailableInTeam(@Param("teamId") Long teamId);

    /**
     * {@code open_count = open_count + 1}, as one statement.
     *
     * <p><b>Read-modify-write on a counter is a lost update</b>, and this counter is written
     * from three places under concurrency. {@code SET n = n + 1} is evaluated by the
     * database under the row lock the UPDATE takes, so twenty concurrent assignments add
     * twenty — where twenty read-then-writes would add somewhere between one and twenty and
     * report success every time.
     *
     * <p>Native, so the tenant predicate is explicit: {@code @TenantId} does not reach
     * native SQL.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE agent_profile SET open_count = open_count + 1
             WHERE user_id = :userId AND tenant_id = :tenantId
            """, nativeQuery = true)
    int incrementOpenCount(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * The mirror.
     *
     * <p>{@code GREATEST(open_count - 1, 0)} rather than a bare decrement: the counter is a
     * cache of "how much work is on this desk", and the one thing worse than it drifting
     * high is it going negative, which would let an agent be assigned an unbounded number of
     * tickets. The floor makes the worst case a slightly overstated load.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE agent_profile SET open_count = GREATEST(open_count - 1, 0)
             WHERE user_id = :userId AND tenant_id = :tenantId
            """, nativeQuery = true)
    int decrementOpenCount(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * The least loaded available agent in a team, locked for this transaction.
     *
     * <h2>{@code SKIP LOCKED} is what makes this correct under a burst</h2>
     *
     * <p>A plain {@code ORDER BY open_count LIMIT 1 FOR UPDATE} looks equivalent and is
     * not. Twenty concurrent routers all evaluate the ORDER BY against the same
     * committed snapshot, all pick the <i>same</i> least-loaded agent, and nineteen of
     * them block on that one row lock. They then wake one at a time and each assigns to
     * that same agent, because each re-reads a row that is now one busier but still, as
     * far as its own already-decided choice goes, the winner. The result is a pile-up on
     * one desk while everyone else sits idle — and every request returns 200, so nothing
     * reports it.
     *
     * <p>{@code SKIP LOCKED} makes each concurrent router step over the rows its peers
     * have locked and take the next one down the list. Twenty routers reach twenty
     * different agents in one pass. That is not an optimisation; it is the difference
     * between the load distribution the product promises and a queue of one.
     *
     * <p>Returns the {@code user_id}, not the profile. The caller needs the user to set
     * {@code ticket.assignee_id}, and mapping a locked row to a managed entity here
     * would put a Hibernate first-level cache between the lock and the update.
     *
     * @param localTime the tenant's local wall-clock time, passed in rather than read
     *                  from the database: an agent's shift is expressed in their own
     *                  business hours, and {@code NOW()::time} is UTC on the server.
     *                  Getting that wrong shifts every shift window by the tenant's
     *                  offset and only ever shows up as "the night shift never gets
     *                  tickets".
     */
    @Query(value = """
            SELECT ap.user_id
              FROM agent_profile ap
             WHERE ap.tenant_id = :tenantId
               AND ap.is_available = TRUE
               AND ap.open_count < ap.max_concurrent
               AND ap.user_id IN (
                     SELECT u.id FROM app_user u
                      WHERE u.team_id = :teamId
                        AND u.tenant_id = :tenantId
                        AND u.deleted_at IS NULL
                        AND u.is_active = TRUE
                        AND u.role IN ('AGENT', 'TEAM_LEAD'))
               AND (ap.shift_start IS NULL
                    OR CAST(:localTime AS time) BETWEEN ap.shift_start AND ap.shift_end)
             ORDER BY ap.open_count ASC, ap.id ASC
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Long> claimLeastLoadedAgent(@Param("tenantId") Long tenantId,
                                         @Param("teamId") Long teamId,
                                         @Param("localTime") String localTime);
}
