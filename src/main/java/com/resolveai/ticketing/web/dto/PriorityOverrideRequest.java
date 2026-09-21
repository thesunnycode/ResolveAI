package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param reason required, and recorded as a label. An override with no reason is
 *               unanalysable: doc 05 makes the point that the value of this endpoint is
 *               being able to tell later whether the model misread the ticket or the
 *               policy is wrong, and without a reason both look identical.
 */
public record PriorityOverrideRequest(

        @NotNull(message = "Priority is required")
        Priority priority,

        @NotBlank(message = "A reason is required for a priority override")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason) {
}
