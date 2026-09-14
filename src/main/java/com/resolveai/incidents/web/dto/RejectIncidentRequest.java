package com.resolveai.incidents.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectIncidentRequest(
        @NotBlank @Size(min = 10, max = 500) String reason) {
}
