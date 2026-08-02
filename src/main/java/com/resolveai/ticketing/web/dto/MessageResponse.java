package com.resolveai.ticketing.web.dto;

import com.resolveai.iam.domain.Role;
import com.resolveai.ticketing.domain.TicketMessage;
import com.resolveai.ticketing.domain.Visibility;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One message, as returned to an agent.
 *
 * @param slaEffect what posting this did to the clocks. Present only on the response to a
 *                  {@code POST}, and only when something actually happened. <b>The reason it
 *                  exists is that the write and the clock change are one transaction</b> -
 *                  so the client can be told the outcome instead of re-fetching and
 *                  possibly reading a state that was never simultaneously true.
 */
public record MessageResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String authorName,
        Role authorRole,
        Visibility visibility,
        String body,
        boolean isFirstResponse,
        Long fromDraftId,
        List<Object> attachments,
        SlaEffectResponse slaEffect,
        OffsetDateTime createdAt) {

    /** @param firstResponse the first-response clock's new state, e.g. {@code MET} */
    public record SlaEffectResponse(String firstResponse, OffsetDateTime metAt) {
    }

    public static MessageResponse of(TicketMessage m, SlaEffectResponse effect) {
        return new MessageResponse(
                m.getId(), m.getTicket().getId(),
                m.getAuthor().getId(), m.getAuthor().getFullName(), m.getAuthor().getRole(),
                m.getVisibility(), m.getBody(), m.isFirstResponse(), m.getFromDraftId(),
                List.of(), effect, m.getCreatedAt());
    }
}
