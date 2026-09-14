package com.resolveai.incidents.web.dto;

import com.resolveai.ticketing.web.dto.UserRef;
import java.time.OffsetDateTime;
import java.util.List;

/** Doc 05 §3.5's full detail shape. */
public record IncidentDetailResponse(Long id, String reference, String title, String summary,
                                     String status, DetectionView detection,
                                     String titleGeneratedBy, UserRef confirmedBy,
                                     OffsetDateTime confirmedAt, String rejectedReason,
                                     int linkedTicketCount, List<LinkedTicketView> linkedTickets,
                                     List<LinkedTicketView> detachedTickets,
                                     List<UpdateView> updates, String etag) {

    public record LinkedTicketView(Long ticketId, String reference, String subject,
                                   Double linkConfidence, OffsetDateTime linkedAt,
                                   UserRef linkedBy, OffsetDateTime detachedAt) {
    }

    public record UpdateView(Long id, String body, String visibility, String authorName,
                             OffsetDateTime publishedAt, DeliverySummary delivery) {
    }

    public record DeliverySummary(int total, int sent, int pending, int failed) {
    }
}
