package com.resolveai.ticketing.service;

import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketMessage;
import com.resolveai.ticketing.repository.TicketEventRepository;
import com.resolveai.ticketing.repository.TicketMessageRepository;
import com.resolveai.ticketing.web.dto.MessageResponse;
import com.resolveai.ticketing.web.dto.SlaSummary;
import com.resolveai.ticketing.web.dto.TeamRef;
import com.resolveai.ticketing.web.dto.TicketCustomerResponse;
import com.resolveai.ticketing.web.dto.TicketDetailResponse;
import com.resolveai.ticketing.web.dto.TicketSummaryResponse;
import com.resolveai.ticketing.web.dto.TimelineEntry;
import com.resolveai.ticketing.web.dto.UserRef;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Entities to response DTOs.
 *
 * <p><b>The customer mapper is the important method in this file.</b> It is the last place
 * an internal note could reach the person the note is about, and the reason it cannot is
 * structural rather than careful: {@link TicketCustomerResponse} has no component to put one
 * in. The filter below is belt to that braces — it keeps internal messages out of the list
 * that gets mapped, so they are never even loaded into a DTO.
 *
 * <p>Written by hand rather than with MapStruct. Doc 05 names MapStruct, and for a
 * field-for-field copy it would be the right answer — but these two mappers differ by which
 * fields exist rather than by their names, which is the case where generated code is a
 * configuration exercise and a plain method is four lines and obvious.
 */
@Component
public class TicketMapper {

    private final TicketMessageRepository messages;
    private final TicketEventRepository events;
    private final SlaSummaryProvider slaSummaries;
    private final IncidentLinkLookup incidentLinks;
    private final LatestDraftLookup latestDrafts;

    public TicketMapper(TicketMessageRepository messages, TicketEventRepository events,
                        SlaSummaryProvider slaSummaries, IncidentLinkLookup incidentLinks,
                        LatestDraftLookup latestDrafts) {
        this.messages = messages;
        this.events = events;
        this.slaSummaries = slaSummaries;
        this.incidentLinks = incidentLinks;
        this.latestDrafts = latestDrafts;
    }

    public TicketSummaryResponse toSummary(Ticket t, long messageCount) {
        return toSummary(t, messageCount, null);
    }

    public TicketSummaryResponse toSummary(Ticket t, long messageCount,
                                           TicketSummaryResponse.LastReply lastPublicReply) {
        return toSummary(t, messageCount, lastPublicReply,
                // Confirmed incidents only: this row is also what a customer's list is built
                // from, and a customer hears about an incident once a person has confirmed it.
                incidentLinks.liveFor(t.getTenantId(), t.getId(), false),
                slaSummaries.summaryFor(t.getId()));
    }

    /**
     * A row from values the caller already fetched for the whole page - see
     * {@link #pageContext}. Same output as the per-ticket overloads.
     */
    public TicketSummaryResponse toSummary(Ticket t, long messageCount,
                                           TicketSummaryResponse.LastReply lastPublicReply,
                                           IncidentLinkLookup.Link incident, SlaSummary sla) {
        return new TicketSummaryResponse(
                t.getId(), t.getReference(), t.getSubject(), t.getStatus(), t.getPriority(),
                t.getCategory(),
                UserRef.of(t.getRequester()), UserRef.of(t.getAssignee()), TeamRef.of(t.getTeam()),
                refOf(incident), sla,
                messageCount, t.getCreatedAt(), t.getUpdatedAt(), lastPublicReply);
    }

    /** The per-page lookups a list needs, each one statement however long the page. */
    public record PageContext(java.util.Map<Long, IncidentLinkLookup.Link> incidents,
                              java.util.Map<Long, SlaSummary> sla) {
    }

    public PageContext pageContext(Long tenantId, List<Long> ticketIds) {
        return new PageContext(
                // Confirmed only - the same rule as the per-ticket overload, for the same reason.
                incidentLinks.liveForAll(tenantId, ticketIds, false),
                slaSummaries.summariesFor(ticketIds));
    }

    /** The full view, for {@code AGENT} and above. Includes {@code INTERNAL} messages. */
    public TicketDetailResponse toAgentDetail(Ticket t, boolean includeTimeline) {
        List<MessageResponse> thread = messages.findByTicketIdOrderByCreatedAtAsc(t.getId())
                .stream()
                .map(m -> MessageResponse.of(m, null))
                .toList();
        List<TimelineEntry> timeline = includeTimeline
                ? events.findByTicketIdOrderByOccurredAtAsc(t.getId()).stream()
                        .map(TimelineEntry::of).toList()
                : null;

        return new TicketDetailResponse(
                t.getId(), t.getReference(), t.getSubject(), t.getBody(),
                t.getStatus(), t.getPriority(), t.getCategory(),
                UserRef.of(t.getRequester()), UserRef.of(t.getAssignee()), TeamRef.of(t.getTeam()),
                t.getReopenCount(), thread, timeline,
                slaSummaries.detailFor(t.getId()),
                mapOf(incidentLinks.liveFor(t.getTenantId(), t.getId(), true)), null,
                // Phase 6 fills this in. A fixed value rather than an omission, so clients
                // can bind the field now and see it change rather than appear.
                "NOT_STARTED", latestDrafts.latestFor(t.getTenantId(), t.getId()),
                EtagSupport.etagOf(t.getVersion()),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private static String refOf(IncidentLinkLookup.Link link) {
        return link == null ? null : link.reference();
    }

    private static Object mapOf(IncidentLinkLookup.Link link) {
        return link == null ? null : link.asMap();
    }

    /**
     * The customer's view.
     *
     * <p>{@code INTERNAL} messages are filtered before mapping, so nothing internal is ever
     * held in a DTO that is about to be serialised. And the response type has no
     * {@code priorityRationale}, {@code analysisStatus}, {@code latestDraftId} or SLA
     * detail at all — those fields are <b>absent from the JSON, not null</b>, so the
     * response does not advertise that something is being withheld.
     */
    public TicketCustomerResponse toCustomerDetail(Ticket t) {
        List<MessageResponse> publicThread =
                messages.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                        .filter(TicketMessage::isPublic)
                        .map(m -> MessageResponse.of(m, null))
                        .toList();

        return new TicketCustomerResponse(
                t.getId(), t.getReference(), t.getSubject(), t.getBody(),
                t.getStatus(), t.getPriority(), t.getCategory(),
                UserRef.of(t.getRequester()), UserRef.of(t.getAssignee()),
                t.getReopenCount(), publicThread,
                responseTargetOf(t.getId()),
                EtagSupport.etagOf(t.getVersion()),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    /** The first-response promise, reduced to what a customer may see. */
    private TicketCustomerResponse.ResponseTarget responseTargetOf(Long ticketId) {
        SlaResponse sla = slaSummaries.detailFor(ticketId);
        if (sla == null || sla.clocks() == null || sla.calendar() == null) {
            return null;
        }
        return sla.clocks().stream()
                .filter(c -> "FIRST_RESPONSE".equals(c.kind()))
                .findFirst()
                .map(c -> new TicketCustomerResponse.ResponseTarget(c.state(),
                        c.targetBusinessMinutes(), sla.calendar().timezone(),
                        sla.calendar().workingDays(), sla.calendar().dayStart(),
                        sla.calendar().dayEnd()))
                .orElse(null);
    }
}
