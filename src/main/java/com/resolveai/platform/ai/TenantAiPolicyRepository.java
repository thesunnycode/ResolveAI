package com.resolveai.platform.ai;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Policies, by tenant id.
 *
 * <p><b>Deliberately not {@code @TenantId}-filtered.</b> This is read by workers that
 * have no request and therefore no tenant context of their own — they are asking about
 * a tenant, not acting as one — and the id is always supplied explicitly.
 */
public interface TenantAiPolicyRepository extends JpaRepository<TenantAiPolicy, Long> {

    /**
     * Adds to the month's spend, in the database.
     *
     * <p><b>{@code SET spend = spend + :cost}, never read-modify-write.</b> Two triage
     * workers finishing at the same moment would both read the same starting figure and
     * both write their own total, and one of the two calls would be free. The addition
     * happens under the row lock the UPDATE takes, so the total is correct however many
     * workers are running — which matters, because this number is what a customer is
     * billed against.
     */
    @Modifying
    @Query(value = """
            UPDATE tenant_ai_policy
               SET current_month_spend_micros = current_month_spend_micros + :cost,
                   updated_at = NOW()
             WHERE tenant_id = :tenantId
            """, nativeQuery = true)
    int addSpend(@Param("tenantId") Long tenantId, @Param("cost") long costMicros);

    /**
     * Zeroes every tenant's spend. Run on the first of the month.
     *
     * @return how many tenants were reset, for the log line that proves it ran
     */
    @Modifying
    @Query(value = """
            UPDATE tenant_ai_policy
               SET current_month_spend_micros = 0, updated_at = NOW()
             WHERE current_month_spend_micros <> 0
            """, nativeQuery = true)
    int resetMonthlySpend();

    /** For the admin view, where a stale read would be confusing rather than harmful. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<TenantAiPolicy> findWithLockByTenantId(Long tenantId);
}
