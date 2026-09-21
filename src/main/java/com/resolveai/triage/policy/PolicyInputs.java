package com.resolveai.triage.policy;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.platform.ai.model.TriageSignals;
import com.resolveai.ticketing.domain.Priority;

/**
 * Everything the priority policy is allowed to look at — and the split that makes the
 * rationale endpoint worth having.
 *
 * <h2>Two sources, kept apart on purpose</h2>
 *
 * <p>{@link #signals} came from a language model. Everything else came from the system's
 * own records and is not a guess. The endpoint that renders a decision reports the two
 * groups separately, because when an agent overrides a priority the only useful question
 * is <b>which half was wrong</b>:
 *
 * <ul>
 *   <li>the model said {@code reportedImpact: ORG_WIDE} about a single user's problem —
 *       a prompt or model bug, fixed by changing the prompt and re-running the evals;
 *   <li>the model was right and {@code PAYMENT_BUMP} should not apply to this tenant —
 *       a policy bug, fixed by editing {@code PriorityPolicy} and shipping {@code v2}.
 * </ul>
 *
 * <p>Those are two completely different fixes, and a design where the model returns
 * {@code "priority": "P2"} makes them permanently indistinguishable. Merging the two
 * groups into one flat map here would do the same thing more slowly.
 *
 * @param signals                 the model's observations. Never a conclusion — see
 *                                {@link TriageSignals}, which has no priority field and
 *                                must never acquire one.
 * @param planTier                the tenant's plan, from {@code tenant.plan_tier}.
 * @param linkedIncidentPriority  the priority of the live incident this ticket is linked
 *                                to, or {@code null}. <b>Always {@code null} in Phase
 *                                6</b>: incident correlation is Phase 8, and there is
 *                                nothing yet that links a ticket to an incident. The rule
 *                                is implemented and tested anyway, because it is a pure
 *                                function and testing it costs nothing now, where
 *                                retrofitting it into a policy whose trace format is
 *                                already persisted in production costs a migration.
 * @param reopenCount             {@code ticket.reopen_count}. A ticket reopened twice is
 *                                evidence the previous resolutions did not work, which is
 *                                a fact about this ticket and not about its text.
 */
public record PolicyInputs(
        TriageSignals signals,
        PlanTier planTier,
        Priority linkedIncidentPriority,
        int reopenCount) {

    public PolicyInputs {
        if (signals == null) {
            throw new IllegalArgumentException("PolicyInputs.signals is required");
        }
        if (planTier == null) {
            // Not defaulted to FREE. A missing plan tier is a provisioning bug, and
            // silently treating the tenant as FREE would quietly downgrade every ticket
            // they raise — visible only as "our tickets always come out low".
            throw new IllegalArgumentException("PolicyInputs.planTier is required");
        }
        if (reopenCount < 0) {
            throw new IllegalArgumentException("PolicyInputs.reopenCount cannot be negative");
        }
    }
}
