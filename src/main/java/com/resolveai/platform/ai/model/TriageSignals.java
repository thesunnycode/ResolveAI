package com.resolveai.platform.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.resolveai.ticketing.domain.Category;
import java.util.Map;

/**
 * What the model observed. <b>Not what it decided.</b>
 *
 * <h2>There is no priority field here, and that is the project's whole argument</h2>
 *
 * <p>Every field below is an <i>observation</i>: what the ticket says, how it is written,
 * what identifiers it mentions. The priority is computed from these by
 * {@code PriorityPolicy} — a pure, versioned, table-tested function — and that split is
 * what makes the outcome explainable ("P1 because serviceDownClaimed and plan tier
 * ENTERPRISE", not "because the model said so"), reproducible, testable without a model,
 * and changeable without re-running a single ticket through an LLM.
 *
 * <p>Adding {@code priority} here would be easy and would appear to work. It would also
 * quietly delete every one of those four properties, and nothing would fail.
 *
 * @param linguisticUrgency <b>named honestly.</b> It measures the urgency of the
 *                          <i>language</i> — capitals, exclamation marks, "immediately" —
 *                          and nothing else. Calling it {@code urgency} would invite the
 *                          policy to treat it as the urgency of the problem, which would
 *                          systematically deprioritise polite customers and reward
 *                          shouting. The name is the guardrail.
 * @param reportedImpact    what the customer <i>claims</i> is affected. A claim, not a
 *                          measurement: the policy weights it accordingly, and the
 *                          incident detector is what corroborates it.
 * @param extractedEntities identifiers as written, including redaction placeholders like
 *                          {@code «ORDER_REF_1»} — the model never saw the real value,
 *                          and the placeholder is the honest thing to store.
 * @param confidence        the model's confidence in the <i>category</i> specifically.
 *                          Used to decide whether a human should look, never to scale a
 *                          priority.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriageSignals(
        Category category,
        Severity reportedImpact,
        boolean serviceDownClaimed,
        boolean dataLossClaimed,
        boolean paymentAffected,
        Urgency linguisticUrgency,
        Map<String, String> extractedEntities,
        double confidence) {

    /** How many people the ticket claims are affected. */
    public enum Severity {
        SINGLE_USER, TEAM, ORG_WIDE
    }

    /** How the message is written. Not how bad the problem is. */
    public enum Urgency {
        LOW, MEDIUM, HIGH
    }

    /**
     * Rejects a partially populated result.
     *
     * <p><b>Never partially accept.</b> A {@code TriageSignals} with three of eight
     * fields set still satisfies the type system, and the policy function downstream will
     * happily compute a confident priority from it — one that is wrong in a way nobody
     * can see, because the output looks exactly like a real classification. Refusing here
     * turns that into a parse failure, which escalates and eventually dead-letters, and a
     * dead-lettered triage leaves the ticket for a human. That is the correct outcome.
     */
    public TriageSignals {
        if (category == null) {
            throw new IllegalArgumentException("TriageSignals.category is required");
        }
        if (reportedImpact == null) {
            throw new IllegalArgumentException("TriageSignals.reportedImpact is required");
        }
        if (linguisticUrgency == null) {
            throw new IllegalArgumentException("TriageSignals.linguisticUrgency is required");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException(
                    "TriageSignals.confidence must be between 0 and 1, got " + confidence);
        }
        extractedEntities = extractedEntities == null ? Map.of() : Map.copyOf(extractedEntities);
    }
}
