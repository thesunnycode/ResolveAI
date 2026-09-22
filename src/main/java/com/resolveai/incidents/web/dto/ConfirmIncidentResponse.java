package com.resolveai.incidents.web.dto;

public record ConfirmIncidentResponse(Long id, String status, Effects effects, String etag) {

    public record Effects(int ticketsLinked, int resolutionClocksPaused,
                          int firstResponseClocksUnaffected, boolean evalLabelRecorded) {
    }
}
