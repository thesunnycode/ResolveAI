package com.resolveai.ticketing.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The status resource {@code POST /tickets} points at.
 *
 * <h2>Three states, one status code</h2>
 *
 * <p>{@code PROCESSING}, {@code READY} and {@code UNAVAILABLE} all return {@code 200},
 * and the third is the interesting one. The instinct is {@code 503} — the AI failed, so
 * the feature is unavailable — but that is wrong twice over. The analysis <i>resource</i>
 * exists and this is a successful read of its real state: "we tried, and we could not".
 * And the ticket is entirely workable throughout; an agent picks it up and triages it by
 * hand, exactly as they would have before any of this existed. A {@code 5xx} would make a
 * degraded optional feature look like a broken API, and clients that retry on {@code 5xx}
 * would hammer a path that has already given its final answer.
 *
 * @param retryAfterSeconds set only while {@code PROCESSING} — the server telling the
 *                          client how often to poll rather than leaving it to guess, which
 *                          is how a status endpoint acquires a thousand requests a second
 * @param reason            set only when {@code UNAVAILABLE}: {@code PROVIDER_ERROR},
 *                          {@code BUDGET_EXCEEDED}, {@code PARSE_FAILED}
 * @param manualTriageRequired
 *                          also only when {@code UNAVAILABLE}, and the field that matters
 *                          to a human: it tells the UI to surface the ticket for manual
 *                          triage rather than leaving it looking like it is still thinking
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResponse(
        Long ticketId,
        String status,
        OffsetDateTime enqueuedAt,
        Integer retryAfterSeconds,
        Map<String, Object> signals,
        Double modelConfidence,
        String promptVersion,
        String modelId,
        Integer tokensIn,
        Integer tokensOut,
        Long costMicros,
        Integer latencyMs,
        OffsetDateTime completedAt,
        String reason,
        Integer attempts,
        Boolean manualTriageRequired) {

    public static AnalysisResponse processing(Long ticketId, OffsetDateTime enqueuedAt,
                                              int retryAfterSeconds) {
        return new AnalysisResponse(ticketId, "PROCESSING", enqueuedAt, retryAfterSeconds,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static AnalysisResponse unavailable(Long ticketId, String reason, int attempts) {
        return new AnalysisResponse(ticketId, "UNAVAILABLE", null, null,
                null, null, null, null, null, null, null, null, null,
                reason, attempts, true);
    }
}
