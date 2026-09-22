package com.resolveai.incidents.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentStatus;
import com.resolveai.incidents.domain.IncidentTicket;
import com.resolveai.incidents.repository.IncidentRepository;
import com.resolveai.incidents.repository.IncidentTicketRepository;
import com.resolveai.incidents.web.dto.ConfirmIncidentRequest;
import com.resolveai.incidents.web.dto.ConfirmIncidentResponse;
import com.resolveai.incidents.web.dto.ResolveIncidentRequest;
import com.resolveai.incidents.web.dto.ResolveIncidentResponse;
import com.resolveai.platform.time.DatabaseClock;
import com.resolveai.sla.domain.PauseReason;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.EtagSupport;
import com.resolveai.ticketing.service.SlaLifecycle;
import com.resolveai.ticketing.service.TicketEventRecorder;
import com.resolveai.ticketing.service.TicketService;
import com.resolveai.ticketing.web.dto.ResolveRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirm, reject, link, detach, resolve — doc 12 8C.
 *
 * <p>Every mutation loads the incident with {@link IncidentRepository#findByIdForUpdate},
 * the same pessimistic-read discipline {@code TicketRepository.findByIdForUpdate} uses:
 * the work between read and write is not free of side effects (clocks pause, eval cases
 * are written), so two concurrent confirms must not both believe they won.
 */
@Service
public class IncidentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(IncidentLifecycleService.class);

    private final IncidentRepository incidents;
    private final IncidentTicketRepository incidentTickets;
    private final TicketRepository tickets;
    private final SlaLifecycle sla;
    private final TicketEventRecorder eventRecorder;
    private final IncidentEvalCaseWriter evalCases;
    private final TicketService ticketService;
    private final DatabaseClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public IncidentLifecycleService(IncidentRepository incidents,
                                    IncidentTicketRepository incidentTickets,
                                    TicketRepository tickets, SlaLifecycle sla,
                                    TicketEventRecorder eventRecorder,
                                    IncidentEvalCaseWriter evalCases, TicketService ticketService,
                                    DatabaseClock clock) {
        this.incidents = incidents;
        this.incidentTickets = incidentTickets;
        this.tickets = tickets;
        this.sla = sla;
        this.eventRecorder = eventRecorder;
        this.evalCases = evalCases;
        this.ticketService = ticketService;
        this.clock = clock;
    }

    public Incident requireVisible(Long incidentId) {
        return incidents.findById(incidentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND,
                        "Incident " + incidentId + " was not found."));
    }

    /**
     * {@code PROPOSED -> CONFIRMED}. Pauses every linked ticket's resolution clock
     * (unless the caller opted out), leaves first-response clocks running, and writes a
     * positive label to the eval set.
     */
    @Transactional
    public ConfirmIncidentResponse confirm(ResolvePrincipal principal, Long incidentId,
                                           ConfirmIncidentRequest request) {
        Incident incident = loadForUpdate(incidentId);
        requireStatus(incident, IncidentStatus.PROPOSED);

        OffsetDateTime now = clock.now();
        incident.setStatus(IncidentStatus.CONFIRMED);
        incident.setConfirmedBy(principal.userId());
        incident.setConfirmedAt(now);

        List<IncidentTicket> links = incidentTickets.findLiveByIncidentId(incidentId);
        boolean pauseClocks = request == null || request.pauseClocksOrDefault();
        int paused = 0;
        for (IncidentTicket link : links) {
            Ticket ticket = tickets.findByIdForUpdate(link.getTicketId()).orElse(null);
            if (ticket == null) {
                continue;
            }
            eventRecorder.record(ticket, TicketEventType.INCIDENT_LINKED, null,
                    incident.getReference(), Map.of("incidentId", incidentId));
            if (pauseClocks) {
                Map<String, Object> effect = sla.pauseResolution(ticket,
                        PauseReason.INCIDENT_LINKED.name());
                // Empty means there was no running resolution clock to pause (untriaged,
                // already terminal) - not a real pause, so it does not inflate the count.
                if (!effect.isEmpty()) {
                    paused++;
                }
            }
        }
        incidents.saveAndFlush(incident);

        List<Long> ticketIds = links.stream().map(IncidentTicket::getTicketId).toList();
        evalCases.record(incident, ticketIds, true, null);

        log.info("Incident {} confirmed by user {}: {} ticket(s), {} clock(s) paused",
                incident.getReference(), principal.userId(), links.size(), paused);

        return new ConfirmIncidentResponse(incident.getId(), incident.getStatus().name(),
                new ConfirmIncidentResponse.Effects(links.size(), paused, links.size(), true),
                EtagSupport.etagOf(incident.getVersion()));
    }

    /**
     * {@code PROPOSED -> REJECTED}. Detaches every live link rather than leaving it in
     * place: a rejected incident is not live ({@link IncidentStatus#isLive()}), and
     * {@code uq_incident_ticket_live} would otherwise hold those tickets hostage from
     * ever being linked to a real incident later. Writes the negative eval label — the
     * more valuable half of the set, since a rejection is exactly a false positive the
     * gate's tuning wants to know about.
     */
    @Transactional
    public void reject(ResolvePrincipal principal, Long incidentId, String reason) {
        Incident incident = loadForUpdate(incidentId);
        requireStatus(incident, IncidentStatus.PROPOSED);

        incident.setStatus(IncidentStatus.REJECTED);
        incident.setRejectedReason(reason);

        List<IncidentTicket> links = incidentTickets.findLiveByIncidentId(incidentId);
        OffsetDateTime now = clock.now();
        for (IncidentTicket link : links) {
            link.detach(principal.userId(), now);
        }
        incidentTickets.saveAll(links);
        incidents.saveAndFlush(incident);

        evalCases.record(incident, links.stream().map(IncidentTicket::getTicketId).toList(),
                false, reason);

        log.info("Incident {} rejected by user {}: {}", incident.getReference(),
                principal.userId(), reason);
    }

    /** Manual link. {@code uq_incident_ticket_live} is the real guard; this is the readable error. */
    @Transactional
    public IncidentTicket linkTicket(ResolvePrincipal principal, Long incidentId, Long ticketId) {
        Incident incident = requireVisible(incidentId);
        Ticket ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ApiException(ErrorCode.TICKET_NOT_FOUND,
                        "Ticket " + ticketId + " was not found."));

        Optional<IncidentTicket> existing = incidentTickets.findLiveByTicketId(ticketId);
        if (existing.isPresent()) {
            throw new ApiException(ErrorCode.TICKET_ALREADY_LINKED,
                    "Ticket " + ticketId + " is already linked to a live incident.");
        }

        IncidentTicket link = new IncidentTicket(incidentId, ticketId, null, principal.userId());
        try {
            incidentTickets.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            // The race the existence check above cannot fully close: two concurrent
            // manual links racing past the SELECT both reach the INSERT, and
            // uq_incident_ticket_live lets exactly one through.
            throw new ApiException(ErrorCode.TICKET_ALREADY_LINKED,
                    "Ticket " + ticketId + " is already linked to a live incident.", e);
        }

        eventRecorder.record(ticket, TicketEventType.INCIDENT_LINKED, null,
                incident.getReference(), Map.of("incidentId", incidentId, "manual", true));
        return link;
    }

    /**
     * Detach. Trivially correct, and that is the payoff of the append-only SLA design:
     * because elapsed time is a {@code SUM()} over segments rather than a stored
     * counter, restoring a clock is just resuming it. See {@code SlaLifecycleService}'s
     * class comment for the general argument; this is one more place it pays off.
     */
    @Transactional
    public void detachTicket(ResolvePrincipal principal, Long incidentId, Long ticketId) {
        requireVisible(incidentId);
        IncidentTicket link = incidentTickets.findLiveByIncidentId(incidentId).stream()
                .filter(l -> l.getTicketId().equals(ticketId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.LINK_NOT_FOUND,
                        "Ticket " + ticketId + " is not live-linked to incident " + incidentId + "."));

        IncidentTicket locked = incidentTickets.findByIdForUpdate(link.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.LINK_NOT_FOUND,
                        "Ticket " + ticketId + " is not live-linked to incident " + incidentId + "."));
        locked.detach(principal.userId(), clock.now());
        incidentTickets.saveAndFlush(locked);

        Ticket ticket = tickets.findByIdForUpdate(ticketId).orElse(null);
        if (ticket != null) {
            eventRecorder.record(ticket, TicketEventType.INCIDENT_DETACHED, null, null,
                    Map.of("incidentId", incidentId));
            sla.resumeResolution(ticket);
        }
    }

    /**
     * Resolves the incident and, unless opted out, every still-live linked ticket —
     * through {@link TicketService#resolve}, never a status column write here, so a
     * linked ticket routes through the exact same {@code TicketStateMachine} check and
     * side effects an agent's own resolve would. A ticket an agent already moved to
     * {@code CLOSED} is skipped and named in the response rather than failing the whole
     * operation.
     */
    @Transactional
    public ResolveIncidentResponse resolve(ResolvePrincipal principal, Long incidentId,
                                           ResolveIncidentRequest request) {
        Incident incident = loadForUpdate(incidentId);
        if (incident.getStatus() == IncidentStatus.RESOLVED
                || incident.getStatus() == IncidentStatus.REJECTED) {
            throw new ApiException(ErrorCode.INVALID_INCIDENT_STATE,
                    "Incident " + incident.getReference() + " is already " + incident.getStatus() + ".");
        }

        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setResolvedAt(clock.now());

        List<Long> skipped = new ArrayList<>();
        int resolvedCount = 0;
        if (request.resolveLinkedTicketsOrDefault()) {
            // Checked ahead of the call, not caught from it: TicketService.resolve() is
            // its own @Transactional method sharing this transaction (REQUIRED), so a
            // RuntimeException out of it marks the whole thing rollback-only even if
            // caught here - the exception unwinds, but the doomed flag does not. A
            // pre-check avoids the exception path entirely, which is the only way one
            // already-closed ticket can be skipped without failing every other one.
            for (IncidentTicket link : incidentTickets.findLiveByIncidentId(incidentId)) {
                Ticket ticket = tickets.findById(link.getTicketId()).orElse(null);
                if (ticket == null
                        || !com.resolveai.ticketing.domain.TicketStateMachine.canTransition(
                                ticket.getStatus(), com.resolveai.ticketing.domain.TicketStatus.RESOLVED)) {
                    skipped.add(link.getTicketId());
                    continue;
                }
                ticketService.resolve(principal, link.getTicketId(),
                        new ResolveRequest(request.resolutionNote()));
                resolvedCount++;
            }
        }
        incidents.saveAndFlush(incident);

        return new ResolveIncidentResponse(incident.getId(), incident.getStatus().name(),
                resolvedCount, List.copyOf(skipped), EtagSupport.etagOf(incident.getVersion()));
    }

    private Incident loadForUpdate(Long incidentId) {
        return incidents.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND,
                        "Incident " + incidentId + " was not found."));
    }

    private static void requireStatus(Incident incident, IncidentStatus required) {
        if (incident.getStatus() != required) {
            throw new ApiException(ErrorCode.INVALID_INCIDENT_STATE,
                    "Incident " + incident.getReference() + " is " + incident.getStatus()
                    + "; this action requires " + required + ".");
        }
    }
}
