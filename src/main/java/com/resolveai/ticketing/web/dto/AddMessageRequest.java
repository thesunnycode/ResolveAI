package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param visibility defaults to {@code PUBLIC}. <b>Defaulting the other way would be safer
 *                   for leaks and wrong for the product</b> - most messages are replies to
 *                   the customer, and an agent whose reply silently became an internal note
 *                   would believe they had answered. The leak direction is closed instead by
 *                   rejecting {@code INTERNAL} from a {@code CUSTOMER} and by
 *                   {@code TicketCustomerResponse} never carrying internal messages at all.
 * @param fromDraftId the AI draft this reply came from. Accepted and stored in Phase 5;
 *                   validated against the draft's ticket and status in Phase 7.
 */
public record AddMessageRequest(

        @NotBlank(message = "Body is required")
        @Size(max = 20_000, message = "Body must be 1-20000 characters")
        String body,

        Visibility visibility,

        @Size(max = 5, message = "At most 5 attachments")
        List<Long> attachmentIds,

        Long fromDraftId) {

    public Visibility visibilityOrDefault() {
        return visibility == null ? Visibility.PUBLIC : visibility;
    }
}
