package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import com.resolveai.ticketing.domain.TicketStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The full ticket, <b>as the customer who raised it sees it</b>.
 *
 * <h2>Why this is a separate type and not a flag on {@link TicketDetailResponse}</h2>
 *
 * <p>The tempting version is one DTO and a line in the mapper:
 *
 * <pre>{@code
 * if (role == CUSTOMER) {
 *     dto.setPriorityRationale(null);
 *     dto.setAnalysisStatus(null);
 *     dto.setLatestDraftId(null);
 *     dto.setMessages(publicOnly(dto.getMessages()));
 * }
 * }</pre>
 *
 * <p>It is four lines and it is correct today. It is one forgotten branch away from a
 * customer reading the internal note an agent wrote about them, and the forgetting happens
 * six months later when somebody adds a fifth internal field and does not know this block
 * exists. Nothing fails; the field simply serialises.
 *
 * <p>With two types the same mistake does not compile: there is no component on this record
 * to put an internal field into. <b>The omitted fields are absent from the JSON, not
 * null</b>, so the response does not even advertise that there is something being withheld.
 *
 * <p>The cost is a second mapper and this comment. That is a small price for turning the
 * project's worst possible bug into a compile error.
 */
public record TicketCustomerResponse(
        Long id,
        String reference,
        String subject,
        String body,
        TicketStatus status,
        Priority priority,
        String category,
        UserRef requester,
        UserRef assignee,
        int reopenCount,
        List<MessageResponse> messages,
        ResponseTarget responseTarget,
        String etag,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * The promise, and only the promise: how long until a person replies, in which business
     * hours. Deliberately none of the SLA internals an agent sees - no segments, no
     * escalation rungs, no breach prediction. {@code null} until triage has decided the
     * priority, because until then there is no promise to state.
     *
     * @param state {@code RUNNING}, {@code PAUSED}, {@code MET} or {@code BREACHED}
     */
    public record ResponseTarget(String state, int targetBusinessMinutes, String timezone,
                                 List<Integer> workingDays, String dayStart, String dayEnd) {
    }
}
