package com.resolveai.sla.web;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.pagination.CursorPage;
import com.resolveai.common.pagination.PageRequests;
import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.sla.domain.PauseReason;
import com.resolveai.sla.service.AtRiskService;
import com.resolveai.sla.service.SlaClockService;
import com.resolveai.sla.service.SlaReadService;
import com.resolveai.sla.web.dto.AtRiskRow;
import com.resolveai.sla.web.dto.PauseRequest;
import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.service.TicketAccess;
import jakarta.validation.Valid;
import com.resolveai.platform.time.DatabaseClock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SLA endpoints from doc 05 §3.4.
 *
 * <p>{@code AGENT} and above. A customer sees their ticket's status and nothing about the
 * clock behind it: an SLA is a promise between the vendor and the customer's <i>account</i>,
 * and exposing "you are 94% of the way to a breach" to the person waiting turns a
 * management tool into a stick.
 */
@RestController
@RequestMapping("/api/v1")
public class SlaController {

    private final SlaReadService read;
    private final SlaClockService clocks;
    private final AtRiskService atRisk;
    private final TicketAccess access;
    private final DatabaseClock clock;

    public SlaController(SlaReadService read, SlaClockService clocks, AtRiskService atRisk,
                         TicketAccess access, DatabaseClock clock) {
        this.read = read;
        this.clocks = clocks;
        this.atRisk = atRisk;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Both clocks with their full segment histories.
     *
     * <p>The segment arrays are exposed deliberately: they make the append-only design
     * checkable from one API call, and they are the fastest way to work out why a clock
     * says what it says.
     */
    @GetMapping("/tickets/{id}/sla")
    @IsAgentOrAbove
    public ResponseEntity<SlaResponse> forTicket(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id) {
        // Through TicketAccess, so a ticket the caller cannot see is 404 here too rather
        // than an SLA response that confirms it exists.
        access.loadVisible(principal, id);

        SlaResponse response = read.detailFor(id);
        if (response == null) {
            throw new ApiException(ErrorCode.SLA_NOT_STARTED,
                    "Ticket " + id + " has no SLA clocks. They start when its priority is "
                    + "known.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    /**
     * Pauses a clock. <b>Idempotent by contract</b>: pausing an already-paused clock is a
     * {@code 200} with the current state, not an error. The caller wanted it paused and it
     * is, and making them check first would be an extra round trip for no benefit.
     */
    @PostMapping("/tickets/{id}/sla/pause")
    @IsAgentOrAbove
    @Transactional
    public SlaResponse pause(@AuthenticationPrincipal ResolvePrincipal principal,
                             @PathVariable Long id,
                             @Valid @RequestBody PauseRequest request) {
        Ticket ticket = access.loadVisibleForUpdate(principal, id);
        PauseReason reason = parseReason(request.reason());
        OffsetDateTime now = clock.now();

        for (String kind : request.kindsOrDefault()) {
            clocks.activeRecord(ticket.getId(), parseKind(kind))
                    .ifPresent(record -> clocks.pause(record, reason, now));
        }
        return read.detailFor(id);
    }

    @PostMapping("/tickets/{id}/sla/resume")
    @IsAgentOrAbove
    @Transactional
    public SlaResponse resume(@AuthenticationPrincipal ResolvePrincipal principal,
                              @PathVariable Long id,
                              @RequestBody(required = false) PauseRequest request) {
        Ticket ticket = access.loadVisibleForUpdate(principal, id);
        OffsetDateTime now = clock.now();
        List<String> kinds = request == null ? List.of("RESOLUTION") : request.kindsOrDefault();

        for (String kind : kinds) {
            clocks.activeRecord(ticket.getId(), parseKind(kind))
                    .ifPresent(record -> clocks.resume(record, now));
        }
        return read.detailFor(id);
    }

    /** The ranked queue of clocks that are going to breach. */
    @GetMapping("/sla/at-risk")
    @IsAgentOrAbove
    public CursorPage<AtRiskRow> atRisk(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Integer withinBusinessMinutes,
            @RequestParam(required = false) Integer size) {
        return atRisk.atRisk(principal, teamId, withinBusinessMinutes,
                PageRequests.clampSize(size));
    }

    private static PauseReason parseReason(String reason) {
        try {
            return PauseReason.valueOf(reason);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.INVALID_PAUSE_REASON,
                    "reason must be one of " + List.of(PauseReason.values()) + ".", e);
        }
    }

    private static com.resolveai.sla.domain.SlaKind parseKind(String kind) {
        try {
            return com.resolveai.sla.domain.SlaKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "kinds must contain only FIRST_RESPONSE or RESOLUTION.", e);
        }
    }
}
