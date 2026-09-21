package com.resolveai.iam.repository;

import com.resolveai.iam.domain.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Note what is absent: no method takes a {@code tenantId}. Hibernate's {@code @TenantId}
 * discriminator adds the predicate to every query, so a {@code findByTenantId} would be
 * both redundant and misleading - it would suggest the filtering is the caller's job.
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByIsDefaultTrue();

    Optional<Team> findByName(String name);

    List<Team> findAllByOrderByNameAsc();

    /**
     * The most specific team whose skills cover this category, for Phase 6 routing.
     *
     * <p><b>{@code skills && ARRAY[...]} rather than a {@code LIKE}</b> on a
     * comma-separated string. The column is a real {@code TEXT[]} with a GIN index, so
     * the overlap operator is indexed; {@code LIKE '%PAYMENT%'} is a sequential scan that
     * also matches a skill called {@code NON_PAYMENT}.
     *
     * <p>{@code ORDER BY array_length(skills, 1)} implements "most specific wins". A team
     * skilled in {@code {PAYMENT}} beats one skilled in
     * {@code {PAYMENT, BILLING, DATA, API}} — the second is a catch-all, and routing to a
     * catch-all when a specialist exists is how a specialist team stops seeing the work
     * it was formed for. The {@code id} tiebreak keeps the choice deterministic when two
     * teams are equally specific, which is what makes the routing test assertable rather
     * than merely usually right.
     *
     * <p>Native, so the tenant predicate is explicit: {@code @TenantId} does not reach
     * native SQL, and a routing query without it would hand a ticket to another
     * customer's team.
     */
    @Query(value = """
            SELECT * FROM team
             WHERE tenant_id = :tenantId
               AND deleted_at IS NULL
               AND skills && ARRAY[CAST(:category AS text)]
             ORDER BY COALESCE(array_length(skills, 1), 0) ASC, id ASC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Team> findMostSpecificForSkill(@Param("tenantId") Long tenantId,
                                            @Param("category") String category);
}
