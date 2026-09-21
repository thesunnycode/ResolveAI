package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * @param shiftStart {@code HH:mm}, or null to clear. Validated by pattern rather than bound
 *                   to {@code LocalTime}: a malformed time should be a 400 with a field
 *                   name, not a Jackson deserialisation failure with none.
 */
public record AvailabilityRequest(

        @NotNull(message = "isAvailable is required")
        Boolean isAvailable,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Must be HH:mm")
        String shiftStart,

        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Must be HH:mm")
        String shiftEnd) {
}
