package com.resolveai.knowledge.domain;

/**
 * Where a knowledge document came from, and how much a claim citing it should be trusted.
 *
 * <p>Matches {@code ck_kb_source}. The tier a source maps to is a separate concept —
 * see {@link SourceTier} — because two sources ({@code RUNBOOK} and {@code ARTICLE})
 * share a tier and the mapping is a policy decision, not a database fact.
 */
public enum DocumentSource {

    /** Customer-facing help content, written for the product's users. */
    ARTICLE,

    /** Internal, procedural — written for agents, not customers. */
    RUNBOOK,

    /** A closed ticket's problem and resolution, indexed automatically. Phase 7E. */
    RESOLVED_TICKET;

    /**
     * The trust tier this source carries into retrieval and drafting.
     *
     * <p>{@code ARTICLE} and {@code RUNBOOK} are both {@code AUTHORITATIVE}: somebody
     * wrote them deliberately, for the purpose of being read. {@code RESOLVED_TICKET} is
     * {@code PRECEDENT} — evidence that a particular fix worked once, which is weaker
     * than a documented policy and must never outrank one just because it embeds closer
     * to the query.
     */
    public SourceTier tier() {
        return this == RESOLVED_TICKET ? SourceTier.PRECEDENT : SourceTier.AUTHORITATIVE;
    }
}
