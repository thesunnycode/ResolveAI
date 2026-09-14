package com.resolveai.incidents.web.dto;

/** One board card. */
public record IncidentSummaryResponse(Long id, String reference, String title, String status,
                                      int linkedTicketCount, long timeToDetectSeconds,
                                      DetectionView detection) {
}
