package com.resolveai.sla.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * @param reason one of {@code WAITING_ON_CUSTOMER}, {@code PENDING_THIRD_PARTY},
 *               {@code INCIDENT_LINKED} - constrained by {@code ck_segment_reason} in the
 *               database as well, because a free-text pause reason makes the question
 *               "how often do we pause for the customer?" unanswerable within a year.
 * @param kinds  which clocks to pause. Defaults to {@code RESOLUTION} alone: pausing the
 *               first-response clock because a customer has not replied would mean an agent
 *               can indefinitely defer their own first reply, which is the one thing the
 *               first-response SLA exists to prevent.
 */
public record PauseRequest(

        @NotNull(message = "A pause reason is required")
        String reason,

        List<String> kinds) {

    public List<String> kindsOrDefault() {
        return kinds == null || kinds.isEmpty() ? List.of("RESOLUTION") : kinds;
    }
}
