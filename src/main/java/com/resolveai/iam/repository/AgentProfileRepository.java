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
}
