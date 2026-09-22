package com.resolveai.incidents.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentTicket;
import com.resolveai.incidents.domain.IncidentUpdate;
import com.resolveai.incidents.domain.IncidentUpdateDelivery;
import com.resolveai.incidents.domain.UpdateVisibility;
import com.resolveai.incidents.repository.IncidentRepository;
import com.resolveai.incidents.repository.IncidentTicketRepository;
import com.resolveai.incidents.repository.IncidentUpdateDeliveryRepository;
import com.resolveai.incidents.repository.IncidentUpdateRepository;
import com.resolveai.incidents.web.dto.PublishUpdateResponse;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxPublisher;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /incidents/{id}/updates}, doc 12 Task 18.
 *
 * <h2>N independent delivery rows and N independent outbox events, not one event carrying a list</h2>
 *
 * <p>One event over N tickets means a single failure rolls back all N and re-sends to
 * everyone on retry. Per-ticket rows are what make delivery 23 retryable in isolation, and
 * this is the one place in the whole project where a queue genuinely earns its keep, as
 * opposed to ticket creation where the outbox alone suffices — see {@link FanoutWorker}'s
 * class comment.
 */
@Service
public class IncidentUpdatePublisher {

    /** Above this many linked tickets, an accidental fan-out is not recoverable. */
    static final int MAX_LINKED_WITHOUT_FORCE = 500;

    private final IncidentRepository incidents;
    private final IncidentTicketRepository incidentTickets;
    private final IncidentUpdateRepository updates;
    private final IncidentUpdateDeliveryRepository deliveries;
    private final OutboxPublisher outbox;

    public IncidentUpdatePublisher(IncidentRepository incidents,
                                   IncidentTicketRepository incidentTickets,
                                   IncidentUpdateRepository updates,
                                   IncidentUpdateDeliveryRepository deliveries,
                                   OutboxPublisher outbox) {
        this.incidents = incidents;
        this.incidentTickets = incidentTickets;
        this.updates = updates;
        this.deliveries = deliveries;
        this.outbox = outbox;
    }

    @Transactional
    public PublishUpdateResponse publish(ResolvePrincipal principal, Long incidentId,
                                         String body, String visibilityRaw, boolean force) {
        Incident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND,
                        "Incident " + incidentId + " was not found."));
        UpdateVisibility visibility = parseVisibility(visibilityRaw);

        List<IncidentTicket> links = incidentTickets.findLiveByIncidentId(incidentId);
        if (links.size() > MAX_LINKED_WITHOUT_FORCE && !force) {
            throw new ApiException(ErrorCode.TOO_MANY_LINKED_TICKETS,
                    "This incident has " + links.size() + " linked tickets, above the "
                    + MAX_LINKED_WITHOUT_FORCE + " safety limit. Resend with force: true "
                    + "if this fan-out is intentional.");
        }

        IncidentUpdate update = new IncidentUpdate(incidentId, principal.userId(), body, visibility);
        updates.save(update);

        for (IncidentTicket link : links) {
            IncidentUpdateDelivery delivery = deliveries.save(
                    new IncidentUpdateDelivery(update.getId(), link.getTicketId()));
            outbox.publish("INCIDENT_UPDATE", update.getId(), EventType.INCIDENT_UPDATE_PUBLISHED,
                    Map.of("incidentUpdateId", update.getId(), "ticketId", link.getTicketId(),
                            "deliveryId", delivery.getId()));
        }

        return new PublishUpdateResponse(update.getId(), incidentId, visibility.name(),
                new PublishUpdateResponse.Fanout(links.size(), "QUEUED"),
                Map.of("deliveries", "/api/v1/incidents/" + incidentId + "/updates/"
                        + update.getId() + "/deliveries"),
                update.getPublishedAt());
    }

    private static UpdateVisibility parseVisibility(String raw) {
        try {
            return UpdateVisibility.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "visibility must be PUBLIC or INTERNAL.", e);
        }
    }
}
