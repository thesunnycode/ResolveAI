package com.resolveai.ticketing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param reason why it is not actually resolved. Required: a reopen with no cause is noise. */
public record ReopenRequest(

        @NotBlank(message = "A reason is required")
        @Size(max = 2000, message = "Reason must be at most 2000 characters")
        String reason) {
}
