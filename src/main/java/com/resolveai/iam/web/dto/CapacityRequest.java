package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CapacityRequest(

        @NotNull(message = "maxConcurrent is required")
        @Min(value = 1, message = "maxConcurrent must be at least 1")
        @Max(value = 200, message = "maxConcurrent must be at most 200")
        Integer maxConcurrent) {
}
