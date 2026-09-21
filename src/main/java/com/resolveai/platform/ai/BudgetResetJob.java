package com.resolveai.platform.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zeroes every tenant's monthly AI spend on the first of the month.
 *
 * <h2>Why a job and not a computed window</h2>
 *
 * <p>The alternative is to derive spend from {@code SUM(cost_micros)} over
 * {@code ai_analysis} rows in the current calendar month, which needs no job at all and
 * is tempting for that reason. It is also an aggregate over a growing table on the hot
 * path of every model call — the budget check happens before <i>every</i> call — and it
 * quietly gets slower for the tenants who use the product most.
 *
 * <p>A counter plus a reset is O(1) to read, and the aggregate remains available for the
 * admin usage view where a full scan is fine. The counter can drift from the sum if a
 * reset runs at the wrong moment; the analysis rows are the audit trail either way, so
 * the drift is recoverable rather than lost.
 *
 * <h2>Running twice is harmless, not running is not</h2>
 *
 * <p>{@code SET spend = 0} is idempotent, so two instances both firing at midnight do no
 * damage. Missing the run entirely means every tenant stays at last month's spend and
 * their AI stops working on the first — so the schedule is checked hourly and resets
 * only when the month has actually turned, rather than firing once at a precise instant
 * that a restart can step over.
 */
@Component
public class BudgetResetJob {

    private static final Logger log = LoggerFactory.getLogger(BudgetResetJob.class);

    private final TenantAiPolicyRepository policies;
    private final AiPolicyService policyService;

    private volatile java.time.YearMonth lastResetMonth;

    public BudgetResetJob(TenantAiPolicyRepository policies, AiPolicyService policyService) {
        this.policies = policies;
        this.policyService = policyService;
        // Started mid-month, this must not immediately zero everybody's spend: the
        // current month has already been partly spent and forgetting that hands every
        // tenant a free month. The first tick therefore records the month rather than
        // acting on it.
        this.lastResetMonth = java.time.YearMonth.now();
    }

    /** Hourly, so a restart cannot step over the one instant a daily job would fire at. */
    @Scheduled(fixedDelayString = "${resolveai.ai.budget-reset-check-ms:3600000}")
    public void resetIfMonthTurned() {
        java.time.YearMonth now = java.time.YearMonth.now();
        if (now.equals(lastResetMonth)) {
            return;
        }
        int reset = resetAll();
        lastResetMonth = now;
        log.info("Monthly AI budgets reset for {} tenant(s) at the start of {}", reset, now);
    }

    @Transactional
    public int resetAll() {
        int count = policies.resetMonthlySpend();
        // Every cached decision now holds last month's remainder. Flushing the whole
        // prefix is cruder than evicting per tenant and is the right thing here: this
        // runs twelve times a year and correctness beats precision.
        policies.findAll().forEach(policy -> policyService.evict(policy.getTenantId()));
        return count;
    }
}
