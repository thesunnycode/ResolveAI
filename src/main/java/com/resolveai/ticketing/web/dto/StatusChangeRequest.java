package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param reason required when entering {@code WAITING_ON_CUSTOMER} or
 *               {@code PENDING_THIRD_PARTY}. Those are the two transitions that stop the
 *               resolution clock, so they are also the two an agent can use to make a
 *               ticket look on time. Requiring a reason does not prevent that - it records
 *               who paused what and why, which is what makes the pattern visible later.
 */
public record StatusChangeRequest(

        @NotNull(message = "Status is required")
        TicketStatus status,

        @Size(max = 200, message = "Reason must be at most 200 characters")
        String reason) {
}
