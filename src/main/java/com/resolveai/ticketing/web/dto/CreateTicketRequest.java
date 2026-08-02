package com.resolveai.ticketing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param body <b>The 20,000-character cap is a security control, not a style rule.</b> Every
 *             ticket triggers an LLM call from Phase 6 onwards, and an uncapped body is a
 *             token-cost denial of service: one request with a 4 MB paste costs real money
 *             and can be repeated. The database carries the same bound as
 *             {@code ck_ticket_body}, so removing this annotation downgrades the failure
 *             rather than removing it.
 * @param onBehalfOf an agent filing on a customer's behalf - a phone call, an email that
 *             arrived outside the system. Rejected with {@code 403} for a {@code CUSTOMER};
 *             otherwise any customer could attribute a ticket to anyone.
 */
public record CreateTicketRequest(

        @NotBlank(message = "Subject is required")
        @Size(max = 200, message = "Subject must be at most 200 characters")
        String subject,

        @NotBlank(message = "Body is required")
        @Size(max = 20_000, message = "Body must be 1-20000 characters")
        String body,

        @Size(max = 5, message = "At most 5 attachments")
        List<Long> attachmentIds,

        Long onBehalfOf) {
}
