package com.resolveai.incidents.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.pagination.Cursor;
import com.resolveai.common.pagination.CursorPage;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.domain.IncidentTicket;
import com.resolveai.incidents.domain.IncidentUpdate;
import com.resolveai.incidents.repository.IncidentQueryRepository;
import com.resolveai.incidents.repository.IncidentRepository;
import com.resolveai.incidents.repository.IncidentTicketRepository;
import com.resolveai.incidents.repository.IncidentUpdateDeliveryRepository;
import com.resolveai.incidents.repository.IncidentUpdateRepository;
import com.resolveai.incidents.service.CorrelationGate.GateConfig;
import com.resolveai.incidents.web.dto.DetectionView;
import com.resolveai.incidents.web.dto.IncidentDetailResponse;
import com.resolveai.incidents.web.dto.IncidentDetailResponse.DeliverySummary;
import com.resolveai.incidents.web.dto.IncidentDetailResponse.LinkedTicketView;
import com.resolveai.incidents.web.dto.IncidentDetailResponse.UpdateView;
import com.resolveai.incidents.web.dto.DeliveryStatusResponse;
import com.resolveai.incidents.web.dto.IncidentSummaryResponse;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.EtagSupport;
import com.resolveai.ticketing.web.dto.UserRef;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** The board and the detail view — doc 05 §3.5. */
@Service
public class IncidentReadService {

    private final IncidentRepository incidents;
    private final IncidentQueryRepository incidentQuery;
    private final IncidentTicketRepository incidentTickets;
    private final IncidentUpdateRepository incidentUpdates;
    private final IncidentUpdateDeliveryRepository deliveries;
    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final GateConfig gateConfig;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public IncidentReadService(IncidentRepository incidents,
                               IncidentQueryRepository incidentQuery,
                               IncidentTicketRepository incidentTickets,
                               IncidentUpdateRepository incidentUpdates,
                               IncidentUpdateDeliveryRepository deliveries,
                               TicketRepository tickets, AppUserRepository users,
                               GateConfig gateConfig) {
        this.incidents = incidents;
        this.incidentQuery = incidentQuery;
        this.incidentTickets = incidentTickets;
        this.incidentUpdates = incidentUpdates;
        this.deliveries = deliveries;
        this.tickets = tickets;
        this.users = users;
        this.gateConfig = gateConfig;
    }

    public CursorPage<IncidentSummaryResponse> board(Long tenantId, Set<String> statuses,
                                                      Cursor cursor, int size) {
        List<Long> ids = incidentQuery.pageIds(tenantId, statuses, cursor, size);
        List<Incident> rows = incidents.findAllById(ids);
        // findAllById does not promise input order; re-sort to the keyset order the
        // query already established, id by id.
        rows.sort((a, b) -> ids.indexOf(a.getId()) - ids.indexOf(b.getId()));

        List<IncidentSummaryResponse> summaries = rows.stream().map(this::toSummary).toList();
        return CursorPage.of(summaries, size,
                s -> new Cursor(rowById(rows, s.id()).getDetectedAt(), s.id()));
    }

    public IncidentDetailResponse detail(Long incidentId) {
        Incident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND,
                        "Incident " + incidentId + " was not found."));

        List<IncidentTicket> allLinks = incidentTickets.findByIncidentId(incidentId);
        List<LinkedTicketView> linked = allLinks.stream().filter(IncidentTicket::isLive)
                .map(this::toLinkedView).toList();
        List<LinkedTicketView> detached = allLinks.stream().filter(l -> !l.isLive())
                .map(this::toLinkedView).toList();

        List<UpdateView> updateViews = incidentUpdates.findByIncidentIdOrderByPublishedAtAsc(incidentId)
                .stream().map(this::toUpdateView).toList();

        UserRef confirmedBy = incident.getConfirmedBy() == null ? null
                : UserRef.of(users.findById(incident.getConfirmedBy()).orElse(null));

        return new IncidentDetailResponse(incident.getId(), incident.getReference(),
                incident.getTitle(), incident.getSummary(), incident.getStatus().name(),
                detectionOf(incident), incident.getGeneratedByModel(), confirmedBy,
                incident.getConfirmedAt(), incident.getRejectedReason(), linked.size(), linked,
                detached, updateViews, EtagSupport.etagOf(incident.getVersion()));
    }

    private IncidentSummaryResponse toSummary(Incident incident) {
        long linkedCount = incidentTickets.countByIncidentIdAndDetachedAtIsNull(incident.getId());
        DetectionView detection = detectionOf(incident);
        return new IncidentSummaryResponse(incident.getId(), incident.getReference(),
                incident.getTitle(), incident.getStatus().name(), (int) linkedCount,
                detection.timeToDetectSeconds(), detection);
    }

    private DetectionView detectionOf(Incident incident) {
        long timeToDetect = incident.getFirstTicketAt() == null || incident.getDetectedAt() == null
                ? 0
                : Duration.between(incident.getFirstTicketAt(), incident.getDetectedAt()).toSeconds();

        // The exact historical baseline count and sample-week source are not persisted
        // on the incident row (doc 04's schema has room only for the rate multiple
        // itself) - this note is reconstructed from what is stored rather than a second
        // read of the (possibly since-refreshed) baseline view, so it always agrees
        // with the number the gate actually evaluated.
        String baselineNote = "Arrival rate was %.1fx this tenant's usual rate for the hour"
                .formatted(incident.getArrivalRateMultiple() == null
                        ? 0.0 : incident.getArrivalRateMultiple().doubleValue());

        return new DetectionView(incident.getDetectionMethod().name(),
                incident.getClusterSizeAtDetection(),
                incident.getArrivalRateMultiple() == null ? 0.0
                        : incident.getArrivalRateMultiple().doubleValue(),
                baselineNote,
                new DetectionView.GateThresholds(gateConfig.minClusterSize(),
                        gateConfig.minRateMultiple(), gateConfig.maxWindowMinutes()),
                incident.getFirstTicketAt(), incident.getDetectedAt(), timeToDetect);
    }

    private LinkedTicketView toLinkedView(IncidentTicket link) {
        Ticket ticket = tickets.findById(link.getTicketId()).orElse(null);
        UserRef linkedBy = link.getLinkedBy() == null ? null
                : UserRef.of(users.findById(link.getLinkedBy()).orElse(null));
        return new LinkedTicketView(link.getTicketId(),
                ticket == null ? null : ticket.getReference(),
                ticket == null ? null : ticket.getSubject(),
                link.getLinkConfidence() == null ? null : link.getLinkConfidence().doubleValue(),
                link.getLinkedAt(), linkedBy, link.getDetachedAt());
    }

    private UpdateView toUpdateView(IncidentUpdate update) {
        var rows = deliveries.findByIncidentUpdateId(update.getId());
        int total = rows.size();
        int sent = (int) rows.stream().filter(d -> d.getStatus().name().equals("SENT")).count();
        int pending = (int) rows.stream().filter(d -> d.getStatus().name().equals("PENDING")).count();
        int failed = (int) rows.stream().filter(d -> d.getStatus().name().equals("FAILED")).count();

        UserRef author = UserRef.of(users.findById(update.getAuthorId()).orElse(null));
        return new UpdateView(update.getId(), update.getBody(), update.getVisibility().name(),
                author == null ? null : author.fullName(), update.getPublishedAt(),
                new DeliverySummary(total, sent, pending, failed));
    }

    /**
     * {@code GET /incidents/{id}/updates/{updateId}/deliveries}, doc 12 Task 20.
     *
     * <p><b>{@code incidentId} is not decoration here.</b> {@code IncidentUpdate} carries
     * no {@code tenant_id} of its own — see its class comment — so a lookup by
     * {@code updateId} alone would let any tenant read any other tenant's delivery
     * failures (ticket ids, error text) simply by guessing an id. Routing the check
     * through {@link #incidents}, which <i>is</i> {@code @TenantId}-filtered, is what
     * makes a foreign {@code updateId} 404 rather than a cross-tenant read.
     */
    public DeliveryStatusResponse deliveryStatus(Long incidentId, Long updateId) {
        IncidentUpdate update = incidentUpdates.findById(updateId).orElseThrow(() -> new ApiException(
                ErrorCode.INCIDENT_UPDATE_NOT_FOUND, "Incident update " + updateId + " was not found."));
        if (!update.getIncidentId().equals(incidentId) || incidents.findById(incidentId).isEmpty()) {
            throw new ApiException(ErrorCode.INCIDENT_UPDATE_NOT_FOUND,
                    "Incident update " + updateId + " was not found.");
        }

        var rows = deliveries.findByIncidentUpdateId(updateId);
        int sent = 0;
        int pending = 0;
        int failed = 0;
        List<DeliveryStatusResponse.Failure> failures = new java.util.ArrayList<>();
        for (var d : rows) {
            switch (d.getStatus()) {
                case SENT -> sent++;
                case PENDING -> pending++;
                case FAILED -> {
                    failed++;
                    failures.add(new DeliveryStatusResponse.Failure(d.getTicketId(),
                            d.getAttempts(), d.getLastError()));
                }
                default -> { }
            }
        }
        return new DeliveryStatusResponse(updateId,
                new DeliveryStatusResponse.Summary(rows.size(), sent, pending, failed), failures);
    }

    private static Incident rowById(List<Incident> rows, Long id) {
        return rows.stream().filter(i -> i.getId().equals(id)).findFirst().orElseThrow();
    }
}
