package com.resolveai.sla.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.PlanTier;
import com.resolveai.sla.domain.SlaPolicy;
import com.resolveai.sla.repository.SlaPolicyRepository;
import com.resolveai.ticketing.domain.Priority;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the SLA policy that was in force for a (priority, plan tier) at a given instant.
 *
 * <h2>{@code at} is a parameter</h2>
 *
 * <p>Not {@code now()} read inside. Two things fall out of that, and the second is the one
 * that matters day to day:
 *
 * <ul>
 *   <li>Phase 10's what-if replay becomes possible — "what would this ticket's SLA have
 *       been under last quarter's policy?" is the same call with a different argument.
 *   <li>The class is testable without a clock. A method that reads the current time
 *       internally can only ever be tested for the present, which means the effective-dating
 *       logic — the entire reason this class exists — cannot be tested at all.
 * </ul>
 *
 * <h2>A missing policy is loud</h2>
 *
 * <p>No fallback to a default. A tenant with no policy for P1/ENTERPRISE is a
 * configuration error: somebody has not decided what they are promising. Substituting a
 * plausible default would give the ticket a deadline nobody agreed to, and the first
 * anybody would hear of it is a customer disputing a breach against a target that was
 * invented by this method.
 *
 * <p>{@code UNTRIAGED} is the one case that is not an error — it simply has no policy,
 * because a ticket has no SLA until its priority is known. Callers check
 * {@link Priority#isTriaged()} first; this method's job is to be unambiguous about
 * everything else.
 */
@Service
public class SlaPolicyResolver {

    private final SlaPolicyRepository policies;

    public SlaPolicyResolver(SlaPolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * @throws ApiException {@code SLA_POLICY_NOT_FOUND} when nothing covers the
     *                      combination at that instant
     */
    @Transactional(readOnly = true)
    public SlaPolicy resolve(Priority priority, PlanTier planTier, OffsetDateTime at) {
        if (!priority.isTriaged()) {
            throw new ApiException(ErrorCode.SLA_POLICY_NOT_FOUND,
                    "A ticket has no SLA until it has been triaged.");
        }

        List<SlaPolicy> matches = policies.findEffective(priority, planTier, at);
        if (matches.isEmpty()) {
            throw new ApiException(ErrorCode.SLA_POLICY_NOT_FOUND,
                    "No SLA policy is configured for " + priority + " on the " + planTier
                    + " plan as of " + at + ". Configure one before tickets of this class "
                    + "can be given a deadline.");
        }
        // Ordered by effectiveFrom DESC. uq_sla_policy_live makes overlapping *live*
        // policies impossible, but two closed historical periods can legitimately both
        // contain `at` if somebody backdated a correction - in which case the later one
        // is the intended answer.
        return matches.get(0);
    }
}
