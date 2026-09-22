package com.resolveai.incidents.web.dto;

/** {@code pauseResolutionClocks} defaults to true when the body is omitted entirely. */
public record ConfirmIncidentRequest(Boolean pauseResolutionClocks) {

    public boolean pauseClocksOrDefault() {
        return pauseResolutionClocks == null || pauseResolutionClocks;
    }
}
