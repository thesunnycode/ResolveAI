package com.resolveai.incidents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublishUpdateRequest(
        @NotBlank @Size(min = 1, max = 5000) String body,
        String visibility,
        Boolean force) {

    public String visibilityOrDefault() {
        return visibility == null || visibility.isBlank() ? "INTERNAL" : visibility.toUpperCase();
    }

    public boolean forceOrDefault() {
        return force != null && force;
    }
}
