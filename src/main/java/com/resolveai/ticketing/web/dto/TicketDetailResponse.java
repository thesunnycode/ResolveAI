package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.TicketStatus;
import com.resolveai.sla.web.dto.SlaResponse;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The full ticket, <b>for {@code AGENT} and above</b>.
 *
 * <p>Its counterpart is {@link TicketCustomerResponse}. Read the class comment there: the
 * fact that these are two types rather than one with conditional nulls is the single
 * highest-consequence decision in this package.
 *
 * @param messages        every message, including {@code INTERNAL} ones
 * @param timeline        present only when {@code include=timeline} was asked for
 * @param analysisStatus  Phase 6. Fixed at {@code NOT_STARTED} until then, rather than
 *                        omitted, so the field's shape is settled before anything fills it.
 * @param etag            the version, mirrored into the body as well as the header. A
 *                        browser client cannot always read {@code ETag} across CORS without
 *                        it being exposed explicitly, and a client that cannot read the
 *                        ETag cannot send {@code If-Match}.
 */
public record TicketDetailResponse(
        Long id,
        String reference,
        String subject,
        String body,
        TicketStatus status,
        Priority priority,
        String category,
        UserRef requester,
        UserRef assignee,
        TeamRef team,
        int reopenCount,
        List<MessageResponse> messages,
        List<TimelineEntry> timeline,
        SlaResponse sla,
        Object incident,
        Object priorityRationale,
        String analysisStatus,
        Long latestDraftId,
        String etag,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
