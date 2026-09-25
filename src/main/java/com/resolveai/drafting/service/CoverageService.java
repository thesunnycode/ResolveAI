package com.resolveai.drafting.service;

import com.resolveai.drafting.domain.ClaimVerdict;
import com.resolveai.drafting.domain.DraftStatus;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Coverage arithmetic and the suppression rule. Doc 15 Task 16.
 *
 * <h2>{@code coverage = (SUPPORTED + 0.5 × PARTIAL) / totalClaims}</h2>
 *
 * <p>{@code PARTIAL} is weighted at half, not zero and not one — see
 * {@code EntailmentVerdict}'s own comment for why it is a first-class outcome. A claim the
 * span partially backs is worth something, and worth less than one it fully backs.
 *
 * <h2>{@code assembledText} is null, never a hedged draft</h2>
 *
 * <p>The design position, stated once here because it is the decision the whole
 * suppression mechanism exists to enforce: <b>a confidently wrong answer is worse than no
 * answer.</b> An agent shown a weak draft edits it rather than writing from scratch — the
 * draft becomes the anchor whether or not it deserved to be — which is exactly the wrong
 * outcome for a response the system itself is not confident in. Suppressing entirely, with
 * a clear reason and a recommendation to escalate, is the only response shape that does
 * not risk becoming the anchor.
 */
@Component
public class CoverageService {

    private final double threshold;

    public CoverageService(
            @Value("${resolveai.drafting.coverage-threshold:0.8}") double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    public double coverage(List<ClaimVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (ClaimVerdict v : verdicts) {
            if (v == ClaimVerdict.SUPPORTED) {
                sum += 1.0;
            } else if (v == ClaimVerdict.PARTIAL) {
                sum += 0.5;
            }
        }
        return sum / verdicts.size();
    }

    /**
     * @param hadAnyRetrievedContext whether the search that fed this draft returned
     *                               anything at all. Distinguishes "we looked and found
     *                               nothing" ({@code SUPPRESSED_NO_EVIDENCE}) from "we
     *                               found something but it did not cover the claims well
     *                               enough" ({@code SUPPRESSED_LOW_COVERAGE}) — the same
     *                               null {@code assembledText} either way, but a
     *                               different diagnosis for whoever reads it later:
     *                               the first says the knowledge base has a gap, the
     *                               second says retrieval or generation underperformed
     *                               against content that does exist.
     */
    public DraftStatus statusFor(double coverage, boolean hadAnyRetrievedContext) {
        if (!hadAnyRetrievedContext) {
            return DraftStatus.SUPPRESSED_NO_EVIDENCE;
        }
        return coverage >= threshold ? DraftStatus.SHOWN : DraftStatus.SUPPRESSED_LOW_COVERAGE;
    }

    public String suppressionReasonFor(DraftStatus status, int supportedOrPartial, int total,
                                       double coverage) {
        return switch (status) {
            // Read by agents, not engineers - the numbers travel separately as `coverage`
            // on the draft. And no promise of escalation: suppression routes nothing
            // anywhere, it leaves the ticket with the person already looking at it.
            case SUPPRESSED_NO_EVIDENCE -> "Nothing in the knowledge base covers this "
                    + "ticket, so no reply was drafted rather than guessing.";
            case SUPPRESSED_LOW_COVERAGE -> ("The knowledge base backed up %d of the %d "
                    + "points a reply would need to make (%.0f%%; at least %.0f%% is "
                    + "required), so no reply was drafted rather than risk a wrong answer.")
                    .formatted(supportedOrPartial, total, coverage * 100, threshold * 100);
            default -> null;
        };
    }
}
