package com.resolveai.incidents.web;

import com.resolveai.common.pagination.Cursor;
import com.resolveai.common.pagination.CursorPage;
import com.resolveai.common.pagination.PageRequests;
import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.common.security.IsTeamLeadOrAbove;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.incidents.domain.Incident;
import com.resolveai.incidents.service.IncidentLifecycleService;
import com.resolveai.incidents.service.IncidentReadService;
import com.resolveai.incidents.service.IncidentUpdatePublisher;
import com.resolveai.incidents.web.dto.ConfirmIncidentRequest;
import com.resolveai.incidents.web.dto.ConfirmIncidentResponse;
import com.resolveai.incidents.web.dto.DeliveryStatusResponse;
import com.resolveai.incidents.web.dto.IncidentDetailResponse;
import com.resolveai.incidents.web.dto.IncidentSummaryResponse;
import com.resolveai.incidents.web.dto.LinkTicketRequest;
import com.resolveai.incidents.web.dto.PublishUpdateRequest;
import com.resolveai.incidents.web.dto.PublishUpdateResponse;
import com.resolveai.incidents.web.dto.RejectIncidentRequest;
import com.resolveai.incidents.web.dto.ResolveIncidentRequest;
import com.resolveai.incidents.web.dto.ResolveIncidentResponse;
import com.resolveai.platform.idempotency.Idempotent;
import com.resolveai.ticketing.service.EtagSupport;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Doc 05 §3.5, minus the two fan-out endpoints — those are 8D's {@code IncidentUpdateController}. */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentReadService reads;
    private final IncidentLifecycleService lifecycle;
    private final IncidentUpdatePublisher updates;

    public IncidentController(IncidentReadService reads, IncidentLifecycleService lifecycle,
                              IncidentUpdatePublisher updates) {
        this.reads = reads;
        this.lifecycle = lifecycle;
        this.updates = updates;
    }

    @GetMapping
    @IsAgentOrAbove
    public CursorPage<IncidentSummaryResponse> board(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> status) {
        Set<String> statuses = new LinkedHashSet<>();
        if (status != null) {
            status.forEach(s -> statuses.add(s.toUpperCase()));
        }
        return reads.board(principal.tenantId(), statuses,
                cursor == null ? null : Cursor.decode(cursor), PageRequests.clampSize(size));
    }

    @GetMapping("/{id}")
    @IsAgentOrAbove
    public ResponseEntity<IncidentDetailResponse> detail(@PathVariable Long id) {
        IncidentDetailResponse body = reads.detail(id);
        return ResponseEntity.ok().eTag(body.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    @PostMapping("/{id}/confirm")
    @IsTeamLeadOrAbove
    public ResponseEntity<ConfirmIncidentResponse> confirm(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) ConfirmIncidentRequest request) {
        requireIfMatch(id, ifMatch);
        ConfirmIncidentResponse response = lifecycle.confirm(principal, id, request);
        return ResponseEntity.ok().eTag(response.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    @PostMapping("/{id}/reject")
    @IsTeamLeadOrAbove
    public ResponseEntity<Void> reject(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RejectIncidentRequest request) {
        requireIfMatch(id, ifMatch);
        lifecycle.reject(principal, id, request.reason());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @IsTeamLeadOrAbove
    public void linkTicket(@AuthenticationPrincipal ResolvePrincipal principal,
                           @PathVariable Long id, @Valid @RequestBody LinkTicketRequest request) {
        lifecycle.linkTicket(principal, id, request.ticketId());
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IsTeamLeadOrAbove
    public void detachTicket(@AuthenticationPrincipal ResolvePrincipal principal,
                             @PathVariable Long id, @PathVariable Long ticketId) {
        lifecycle.detachTicket(principal, id, ticketId);
    }

    @PostMapping("/{id}/resolve")
    @IsTeamLeadOrAbove
    public ResponseEntity<ResolveIncidentResponse> resolve(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ResolveIncidentRequest request) {
        requireIfMatch(id, ifMatch);
        ResolveIncidentResponse response = lifecycle.resolve(principal, id, request);
        return ResponseEntity.ok().eTag(response.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    @PostMapping("/{id}/updates")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Idempotent
    @IsTeamLeadOrAbove
    public PublishUpdateResponse publishUpdate(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PublishUpdateRequest request) {
        return updates.publish(principal, id, request.body(), request.visibilityOrDefault(),
                request.forceOrDefault());
    }

    @GetMapping("/{id}/updates/{updateId}/deliveries")
    @IsAgentOrAbove
    public DeliveryStatusResponse deliveries(@PathVariable Long id, @PathVariable Long updateId) {
        return reads.deliveryStatus(id, updateId);
    }

    private void requireIfMatch(Long incidentId, String ifMatch) {
        Incident incident = lifecycle.requireVisible(incidentId);
        EtagSupport.requireMatch(ifMatch, incident.getVersion());
    }
}
