package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.TicketStatus;
import java.util.Map;

/**
 * @param slaEffect what the transition did to the clocks, keyed by kind. Returned rather
 *                  than left for the client to discover, because the status change and the
 *                  clock change committed together and a follow-up GET could otherwise be
 *                  served from a moment in between.
 */
public record StatusChangeResponse(
        Long id,
        TicketStatus status,
        Map<String, Object> slaEffect,
        String etag) {
}
