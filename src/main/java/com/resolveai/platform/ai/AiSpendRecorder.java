package com.resolveai.platform.ai;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds what a model call cost to the tenant's month.
 *
 * <h2>Why this is its own bean and not a method on the router</h2>
 *
 * <p>It was a method on the router, annotated {@code @Transactional(REQUIRES_NEW)}, and
 * it did nothing: <b>Spring's proxy is bypassed by self-invocation</b>. A call from one
 * method of a bean to another goes straight down the object's own vtable, the
 * transactional advice never runs, and the {@code @Modifying} query then fails with
 * "No active transaction" — or worse, on a path that happens to have one already, it
 * silently joins that transaction and the annotation looks like it worked.
 *
 * <p>Moving it to a collaborator makes the call go through the proxy, which is the only
 * way the propagation means anything.
 *
 * <h2>Why {@code REQUIRES_NEW}</h2>
 *
 * <p>The money was spent. The provider does not refund a call because the transaction
 * that followed it rolled back, so the spend must commit even when the caller's work
 * does not. Joining the caller's transaction would let a failed triage make its own
 * model call free, and a tenant could then exhaust a provider's rate limit at no
 * recorded cost.
 */
@Component
public class AiSpendRecorder {

    private final TenantAiPolicyRepository policies;
    private final AiPolicyService policyService;

    public AiSpendRecorder(TenantAiPolicyRepository policies, AiPolicyService policyService) {
        this.policies = policies;
        this.policyService = policyService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long tenantId, long costMicros) {
        if (costMicros <= 0) {
            return;
        }
        // An atomic increment, never a read-modify-write: two workers finishing together
        // would each read the same starting figure and one of the two calls would be
        // free. The pre-call budget *check* is deliberately racy; the total is not.
        policies.addSpend(tenantId, costMicros);
        // The cached decision holds a stale remainder now. Evicting keeps the next
        // budget check honest instead of up to five minutes optimistic.
        policyService.evict(tenantId);
    }
}
