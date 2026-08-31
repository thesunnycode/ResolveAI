package com.resolveai.drafting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One claim exactly as the model produced it — <b>unverified.</b>
 *
 * <p>Everything downstream of generation exists to turn a list of these into a list of
 * {@code draft_claim} rows with a verdict. Nothing here is trusted yet: not the text, not
 * the citation ids, and least of all any number inside the text — that is what
 * {@code NumericVerifier} and the entailment verifier are for.
 *
 * @param citationIds the chunk ids the model claims support this, e.g. {@code "chunk:33401"}.
 *                    <b>Not verified to exist</b> until {@code ClaimGenerator} checks
 *                    every one against the chunks actually in context — a citation
 *                    referencing a chunk id that was never offered is a fabricated
 *                    citation, and the whole generation is rejected for it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawClaim(String text, List<String> citationIds) {

    public RawClaim {
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
