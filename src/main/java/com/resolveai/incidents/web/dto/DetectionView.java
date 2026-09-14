package com.resolveai.incidents.web.dto;

import java.time.OffsetDateTime;

/**
 * The gate's evidence, exposed on every board card and detail view — not behind a click.
 * See doc 05 §3.5: a team lead deciding whether to confirm reads
 * {@code "38 tickets, 12.6x baseline, gate: >=5 AND >3x - both passed"} in the two seconds
 * before deciding.
 */
public record DetectionView(String method, int clusterSizeAtDetection,
                            double arrivalRateMultiple, String baselineNote,
                            GateThresholds gateThresholds, OffsetDateTime firstTicketAt,
                            OffsetDateTime detectedAt, long timeToDetectSeconds) {

    public record GateThresholds(int minClusterSize, double minRateMultiple, int windowMinutes) {
    }
}
