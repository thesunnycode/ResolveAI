package com.resolveai.sla.repository;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.sla.domain.SlaPolicy;
import com.resolveai.ticketing.domain.Priority;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    /**
     * The policy in force for this combination <b>at a given instant</b>.
     *
     * <p>HQL, so {@code @TenantId} supplies the tenant predicate — no hand-written
     * {@code tenant_id} needed here, unlike the native poller query below.
     *
     * <p><b>{@code at} is a parameter, not {@code now()}.</b> That is what makes the
     * Phase 10 what-if replay possible ("what would this ticket's SLA have been under
     * last quarter's policy?") and what makes this method testable without a clock. A
     * method that reads the current time internally can only ever be tested for the
     * present.
     *
     * <p>{@code effective_to} is compared with {@code >}, not {@code >=}: the instant a
     * policy is superseded belongs to its successor, so the two never both match and the
     * {@code LIMIT 1} is not deciding anything.
     */
    @Query("""
            SELECT p FROM SlaPolicy p
             WHERE p.priority = :priority
               AND p.planTier = :planTier
               AND p.effectiveFrom <= :at
               AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
             ORDER BY p.effectiveFrom DESC
            """)
    List<SlaPolicy> findEffective(@Param("priority") Priority priority,
                                  @Param("planTier") PlanTier planTier,
                                  @Param("at") OffsetDateTime at);

    Optional<SlaPolicy> findByPriorityAndPlanTierAndEffectiveToIsNull(Priority priority,
                                                                      PlanTier planTier);
}
