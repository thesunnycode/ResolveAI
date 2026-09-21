package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.TicketStatus;
import java.time.OffsetDateTime;

/**
 * A ticket as it appears in a list. <b>No body, no messages.</b>
 *
 * <p>A queue page of twenty-five tickets each carrying a 20,000-character body is half a
 * megabyte of JSON to render a table of subjects. The body is on the detail endpoint, which
 * is where something actually displays it.
 */
public record TicketSummaryResponse(
        Long id,
        String reference,
        String subject,
        TicketStatus status,
        Priority priority,
        String category,
        UserRef requester,
        UserRef assignee,
        TeamRef team,
        String incidentRef,
        SlaSummary sla,
        long messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
