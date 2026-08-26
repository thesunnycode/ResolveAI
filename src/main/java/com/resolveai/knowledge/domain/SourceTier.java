package com.resolveai.knowledge.domain;

/**
 * How much a claim citing this source should be trusted.
 *
 * <p>Two values, and the boost each carries is applied to the RRF score in retrieval —
 * see {@code HybridSearchRepository}. The UI is expected to render them differently too:
 * "the runbook says" reads as policy, "someone did this once" reads as anecdote, and
 * conflating the two in a citation is how a resolved-ticket precedent quietly becomes
 * treated as documented guidance.
 */
public enum SourceTier {

    /** A runbook or article: written deliberately, for the purpose of being read. */
    AUTHORITATIVE,

    /** A resolved ticket: evidence a fix worked once, not a policy. */
    PRECEDENT
}
