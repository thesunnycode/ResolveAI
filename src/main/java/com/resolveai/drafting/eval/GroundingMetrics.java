package com.resolveai.drafting.eval;

import java.util.List;

/**
 * Claim-level and response-level groundedness. Doc 15 Task 22.
 *
 * <h2>Why both, and why the gap between them is the finding</h2>
 *
 * <p>This targets a documented 2026 case: a groundedness metric read <b>0.92</b> while a
 * claim-level audit of the same runs found hallucination in <b>30%</b> of responses. Both
 * numbers were correct — the metric averaged support <i>per response</i> (a response with
 * four supported claims and one unsupported one still counts as "mostly grounded" in a
 * response-level average, or even scores 1.0 if the average is computed some other lenient
 * way), while the audit decomposed into <i>claims</i> and found the one bad claim in each
 * response the aggregate metric was hiding.
 *
 * <p>Response-level groundedness answers "how many of our responses are entirely clean?" —
 * the number that matters for trust. Claim-level answers "of everything we asserted, how
 * much was true?" — the number that matters for risk, because one fabricated number in an
 * otherwise-perfect response is the one a customer holds the business to. Reporting only
 * the response-level number, as this project's own suppression design already argues
 * against doing implicitly, would hide exactly the failure mode the numeric pre-filter and
 * entailment verifier exist to catch.
 */
public final class GroundingMetrics {

    private GroundingMetrics() {
    }

    /** @param label the hand-verified truth, read against the claim's cited span directly */
    public record LabelledClaim(String caseId, String claimText, boolean citationSupportsClaim,
                                String label) {
    }

    public record Scores(
            int totalClaims, int supportedClaims, double claimLevelGroundedness,
            int totalResponses, int fullyGroundedResponses, double responseLevelGroundedness,
            int totalCitations, int accurateCitations, double citationPrecision) {
    }

    public static Scores score(List<LabelledClaim> claims) {
        int totalClaims = claims.size();
        long supportedClaims = claims.stream()
                .filter(c -> "SUPPORTED".equals(c.label()) || "PARTIAL".equals(c.label()))
                .count();

        java.util.Map<String, java.util.List<LabelledClaim>> byResponse = claims.stream()
                .collect(java.util.stream.Collectors.groupingBy(LabelledClaim::caseId,
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()));
        int totalResponses = byResponse.size();
        long fullyGrounded = byResponse.values().stream()
                .filter(group -> group.stream().allMatch(c -> "SUPPORTED".equals(c.label())
                        || "PARTIAL".equals(c.label())))
                .count();

        int totalCitations = claims.size();
        long accurateCitations = claims.stream().filter(LabelledClaim::citationSupportsClaim)
                .count();

        return new Scores(totalClaims, (int) supportedClaims,
                round(safeDivide(supportedClaims, totalClaims)),
                totalResponses, (int) fullyGrounded,
                round(safeDivide(fullyGrounded, totalResponses)),
                (int) totalCitations, (int) accurateCitations,
                round(safeDivide(accurateCitations, totalCitations)));
    }

    private static double safeDivide(long numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
