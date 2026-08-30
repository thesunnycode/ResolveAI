package com.resolveai.knowledge.web.dto;

/**
 * @param documentsSkipped reported alongside {@code skipReason} deliberately: reindex
 *                         cost is proportional to *changed* documents only because
 *                         content_sha256 lets the indexer skip the rest, and the skip
 *                         count is the evidence that it did.
 * @param estimatedCostMicros shown before the job is queued, so a forced full reindex on
 *                            a large corpus does not silently spend a month's AI budget.
 */
public record ReindexResponse(
        String jobId,
        int documentsQueued,
        int documentsSkipped,
        String skipReason,
        long estimatedCostMicros) {
}
