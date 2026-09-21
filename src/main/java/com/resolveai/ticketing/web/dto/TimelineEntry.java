package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.TicketEvent;
import com.resolveai.ticketing.domain.TicketEventType;
import java.time.OffsetDateTime;

/**
 * One audit entry.
 *
 * @param actorName {@code null} when the system did it - the SLA poller, or a Phase 6
 *                  worker. Rendered as "System" by the UI; kept null here so that the
 *                  distinction survives into anything else that reads the API.
 */
public record TimelineEntry(
        Long id,
        TicketEventType type,
        Long actorId,
        String actorName,
        String from,
        String to,
        OffsetDateTime occurredAt) {

    public static TimelineEntry of(TicketEvent e) {
        return new TimelineEntry(
                e.getId(), e.getEventType(),
                e.getActor() == null ? null : e.getActor().getId(),
                e.getActor() == null ? null : e.getActor().getFullName(),
                e.getFromValue(), e.getToValue(), e.getOccurredAt());
    }
}
