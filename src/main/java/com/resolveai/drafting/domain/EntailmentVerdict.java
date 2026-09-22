package com.resolveai.drafting.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The entailment verifier's answer for one claim against one cited span, and nothing else.
 *
 * <p>{@code PARTIAL} is a first-class outcome, not a rounding of the other two. Most
 * claims in practice are partially supported — the span backs the substance but not the
 * exact phrasing — and collapsing that to a binary either drops a claim that is
 * genuinely useful or ships one that overstates its evidence. {@code CoverageService}
 * scores it at half weight for exactly this reason.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntailmentVerdict(Result verdict) {

    public enum Result {
        SUPPORTED, PARTIAL, NOT_SUPPORTED
    }
}
