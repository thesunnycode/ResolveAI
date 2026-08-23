package com.resolveai.triage.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.resolveai.triage.policy.RuleTrace;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Why this ticket has this priority. <b>The endpoint body that makes the AI's role
 * auditable.</b>
 *
 * <p>{@code inputs} is split into {@code fromModel} and {@code fromSystem}, and that
 * split is the entire reason the endpoint exists. When an agent overrides a priority,
 * this is what lets somebody tell afterwards whether:
 *
 * <ul>
 *   <li>the <b>model misread the ticket</b> — {@code reportedImpact: ORG_WIDE} on one
 *       person's login problem — which is fixed by changing the prompt and re-running
 *       the evals; or
 *   <li>the <b>policy is wrong</b> — the model read it correctly and
 *       {@code PAYMENT_BUMP} should not apply to tickets like this — which is fixed by
 *       editing {@code PriorityPolicy} and shipping a new version.
 * </ul>
 *
 * <p>Two different bugs, two different fixes, and an API where the model returns
 * {@code "priority": "P2"} makes the distinction permanently unrecoverable. That is not
 * a hypothetical cost; it is the difference between being able to improve the system and
 * being able only to argue about it.
 *
 * @param rules        every rule the policy evaluated, in order, matched or not. The
 *                     unmatched ones carry the near-misses, which is what an agent
 *                     deciding whether to override actually wants to see.
 * @param overridable  whether an agent may still change it. False once the ticket is
 *                     closed — the priority is then a historical fact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriorityRationaleResponse(
        Long ticketId,
        String computedPriority,
        String policyVersion,
        OffsetDateTime decidedAt,
        Map<String, Object> inputs,
        List<RuleTrace> rules,
        String humanReadable,
        boolean overridable) {
}
