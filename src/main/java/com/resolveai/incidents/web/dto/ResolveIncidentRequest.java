package com.resolveai.incidents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveIncidentRequest(
        @NotBlank @Size(min = 1, max = 5000) String resolutionNote,
        Boolean resolveLinkedTickets) {

    public boolean resolveLinkedTicketsOrDefault() {
        return resolveLinkedTickets == null || resolveLinkedTickets;
    }
}
