package com.resolveai.drafting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * What {@code draft@1} produces: claims, a suggested tone, and what it could not answer.
 *
 * <p>Structurally the mirror of {@code TriageSignals}: the model reports observations —
 * here, candidate claims and their citations — and deterministic code (the numeric
 * filter, the entailment verifier, the coverage rule) decides which of them survive. The
 * model never decides what the agent sees; it proposes, and everything after this record
 * is verification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftGeneration(List<RawClaim> claims, Tone suggestedTone,
                              List<String> unresolvedAspects) {

    public enum Tone {
        APOLOGETIC, NEUTRAL, REASSURING
    }

    public DraftGeneration {
        claims = claims == null ? List.of() : List.copyOf(claims);
        unresolvedAspects = unresolvedAspects == null ? List.of() : List.copyOf(unresolvedAspects);
    }
}
