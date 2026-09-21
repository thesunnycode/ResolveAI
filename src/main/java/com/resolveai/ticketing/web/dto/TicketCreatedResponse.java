package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.TicketStatus;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * What {@code POST /tickets} returns now that triage is asynchronous.
 *
 * <h2>Why this is not {@link TicketSummaryResponse}</h2>
 *
 * <p>A summary is a description of a ticket. This is a <b>receipt</b>: it says the ticket
 * exists, that several of its fields are deliberately empty, that they will fill in
 * without the client doing anything, and where to look. Returning the summary shape with
 * nulls in it would say the same thing only by omission, and a client reading
 * {@code priority: null} has no way to tell "not decided yet" from "this system does not
 * do priorities".
 *
 * @param analysisStatus always {@code PROCESSING} here. It is stated rather than implied
 *                       because the difference between "still working" and "we tried and
 *                       failed" is the difference between polling and giving up, and a
 *                       client cannot infer it from a null.
 * @param links          where to poll. A URL the server owns beats a documented path the
 *                       client assembles: the path can then change without every client
 *                       changing with it.
 */
public record TicketCreatedResponse(
        Long id,
        String reference,
        String subject,
        TicketStatus status,
        Priority priority,
        String category,
        String analysisStatus,
        UserRef requester,
        UserRef assignee,
        TeamRef team,
        OffsetDateTime createdAt,
        Map<String, String> links) {

    public static TicketCreatedResponse accepted(TicketSummaryResponse summary) {
        return new TicketCreatedResponse(
                summary.id(),
                summary.reference(),
                summary.subject(),
                summary.status(),
                summary.priority(),
                summary.category(),
                "PROCESSING",
                summary.requester(),
                summary.assignee(),
                summary.team(),
                summary.createdAt(),
                Map.of("self", "/api/v1/tickets/" + summary.id(),
                        "analysis", "/api/v1/tickets/" + summary.id() + "/analysis"));
    }
}
