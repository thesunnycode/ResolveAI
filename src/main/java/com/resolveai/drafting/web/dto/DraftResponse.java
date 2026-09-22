package com.resolveai.drafting.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The full draft, with per-claim verdicts. Doc 05 3.3 calls this "the most important
 * response body in the API," and doc 15 Task 19 is specific about why: every claim is
 * returned, including the ones that were dropped, with the reason they were dropped.
 *
 * @param assembledText      null for every status except SHOWN -- never a hedged partial
 *                           draft. See CoverageService for the reasoning.
 * @param recommendation     "ESCALATE_TO_HUMAN" on a suppressed draft, null otherwise.
 * @param sources            every document a KEPT claim cites, deduplicated, with its
 *                           tier -- so the UI can render "the runbook says" differently
 *                           from "someone did this once."
 */
public record DraftResponse(
        Long id,
        Long ticketId,
        String status,
        Double coverage,
        List<ClaimView> claims,
        String assembledText,
        List<String> unresolvedAspects,
        List<SourceView> sources,
        String suppressionReason,
        String recommendation,
        String promptVersion,
        String modelId,
        int tokensIn,
        int tokensOut,
        long costMicros,
        long latencyMs,
        OffsetDateTime createdAt) {

    /**
     * @param verdict         PENDING | SUPPORTED | PARTIAL | NOT_SUPPORTED |
     *                        FAILED_NUMERIC_CHECK
     * @param rejectionReason null for a kept claim
     */
    public record ClaimView(int ordinal, String text, String verdict, boolean kept,
                            String rejectionReason, List<CitationView> citations) {
    }

    /** @param snippet the exact cited span's text, so the UI popover needs no second call. */
    public record CitationView(Long chunkId, Long documentId, String documentTitle,
                               String source, int charStart, int charEnd, String snippet) {
    }

    public record SourceView(Long documentId, String title, String source, String tier) {
    }
}
