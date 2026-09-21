package com.resolveai.ticketing.web;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.pagination.Cursor;
import com.resolveai.common.pagination.CursorPage;
import com.resolveai.common.pagination.PageRequests;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.idempotency.Idempotent;
import com.resolveai.ticketing.repository.TicketQueryRepository;
import com.resolveai.ticketing.service.EtagSupport;
import com.resolveai.ticketing.service.TicketAccess;
import com.resolveai.ticketing.service.TicketService;
import com.resolveai.ticketing.web.dto.AddMessageRequest;
import com.resolveai.ticketing.web.dto.AssignRequest;
import com.resolveai.ticketing.web.dto.CreateTicketRequest;
import com.resolveai.ticketing.web.dto.MessageResponse;
import com.resolveai.ticketing.web.dto.ReopenRequest;
import com.resolveai.ticketing.web.dto.ResolveRequest;
import com.resolveai.ticketing.web.dto.StatusChangeRequest;
import com.resolveai.ticketing.web.dto.StatusChangeResponse;
import com.resolveai.ticketing.web.dto.TicketSummaryResponse;
import com.resolveai.ticketing.web.dto.UpdateTicketRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ticket endpoints from doc 05 §3.2.
 *
 * <p>Thin on purpose: bind, authorise coarsely, delegate, set headers. Every rule that
 * matters — visibility, the state machine, the concurrency guards — lives in the service,
 * because Phase 6's workers reach that code without passing through a controller and a
 * check written here simply would not run for them.
 *
 * <h2>Verb-like sub-resources</h2>
 *
 * <p>{@code /assign}, {@code /status}, {@code /resolve}, {@code /reopen} are not REST
 * nouns. Doc 05 §5 Deviation 1 argues the case and it is the right call: each has its own
 * authorization, its own side effects and its own failure modes, and collapsing them into
 * {@code PATCH /tickets/{id}} would mean one handler branching on which field changed, with
 * the permission check for reassignment buried inside it.
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Validated
public class TicketController {

    private final TicketService tickets;
    private final TicketAccess access;

    public TicketController(TicketService tickets, TicketAccess access) {
        this.tickets = tickets;
        this.access = access;
    }

    /**
     * {@code 201} in Phase 5, {@code 202} from Phase 6. See {@code TicketService.create}.
     *
     * <p>The {@code Idempotency-Key} header is declared here as well as enforced by the
     * aspect, so that it appears in the generated documentation and in the method
     * signature. The aspect is what actually rejects a bad one.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    public TicketSummaryResponse create(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTicketRequest request) {
        return tickets.create(principal, request);
    }

    /**
     * The queue.
     *
     * <p>{@code status} and {@code priority} are repeatable. {@code assigneeId} accepts the
     * literal {@code me} and the literal {@code none} — the second is how an agent finds
     * unclaimed work, which is the single most common thing this endpoint is asked for.
     */
    @GetMapping
    public CursorPage<TicketSummaryResponse> list(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> priority,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) Long incidentId,
            @RequestParam(required = false) String slaState,
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(required = false) OffsetDateTime createdFrom,
            @RequestParam(required = false) OffsetDateTime createdTo,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {

        // A customer has no team and no queue; letting them pass teamId would be an
        // attempt to widen a scope the server does not let them widen. 403 rather than a
        // silent ignore: quietly dropping a filter returns data the caller did not ask for.
        if (principal.role() == Role.CUSTOMER && (teamId != null || assigneeId != null)) {
            throw ApiException.forbidden("teamId and assigneeId are not available to "
                    + "customers; you already see only your own tickets.");
        }

        boolean unassignedOnly = "none".equalsIgnoreCase(assigneeId);
        Long resolvedAssignee = unassignedOnly ? null
                : "me".equalsIgnoreCase(assigneeId) ? principal.userId()
                : assigneeId == null ? null : parseId(assigneeId);

        var filters = new TicketQueryRepository.Filters(
                cursor == null ? null : Cursor.decode(cursor),
                PageRequests.clampSize(size),
                upper(status), upper(priority),
                teamId, resolvedAssignee, unassignedOnly, incidentId,
                slaState == null ? null : slaState.toUpperCase(),
                q, createdFrom, createdTo, sort, order);

        return tickets.list(principal, filters);
    }

    /**
     * The detail view. The response type depends on the caller's role — see
     * {@code TicketCustomerResponse}.
     *
     * <p>{@code ETag} is set from the version and is required back as {@code If-Match} on
     * every mutation. It is also mirrored into the body, because a browser cannot read the
     * header across CORS unless it is explicitly exposed, and a client that cannot read the
     * ETag cannot send {@code If-Match}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> detail(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestParam(required = false) String include) {

        boolean timeline = include != null && include.contains("timeline");
        Object body = tickets.detail(principal, id, timeline);
        int version = access.loadVisible(principal, id).getVersion();

        return ResponseEntity.ok()
                .eTag(EtagSupport.etagOf(version))
                // Everything here is tenant data. A shared cache holding a ticket is a
                // cross-tenant leak waiting for a proxy misconfiguration.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody UpdateTicketRequest request) {
        requireIfMatch(principal, id, ifMatch);
        if (request.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Send at least one of subject, category or teamId.");
        }
        return ok(tickets.update(principal, id, request), principal, id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse addMessage(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody AddMessageRequest request) {
        // No If-Match here, deliberately. Adding a message is an append, not an edit: two
        // agents replying at once should both succeed, and requiring a matching version
        // would reject the second for a conflict that does not exist.
        return tickets.addMessage(principal, id, request);
    }

    /**
     * The one human path to a priority. Doc 05 §3.3.
     *
     * <p>Lives on this controller rather than a triage one because in Phase 5 there is no
     * triage: this is a plain ticket mutation with an audit label. When Phase 6 adds the
     * worker, the computed path goes beside it and this stays as the override.
     */
    @PostMapping("/{id}/priority-override")
    public ResponseEntity<Object> overridePriority(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody com.resolveai.ticketing.web.dto.PriorityOverrideRequest request) {
        requireIfMatch(principal, id, ifMatch);
        return ok(tickets.overridePriority(principal, id, request), principal, id);
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<Object> assign(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) AssignRequest request) {
        requireIfMatch(principal, id, ifMatch);
        AssignRequest effective = request == null ? new AssignRequest(null, false) : request;
        var summary = tickets.assign(principal, id, effective);
        return ResponseEntity.ok()
                .eTag(EtagSupport.etagOf(access.loadVisible(principal, id).getVersion()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(summary);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<StatusChangeResponse> changeStatus(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody StatusChangeRequest request) {
        requireIfMatch(principal, id, ifMatch);
        StatusChangeResponse response = tickets.changeStatus(principal, id, request);
        return ResponseEntity.ok().eTag(response.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<StatusChangeResponse> resolve(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ResolveRequest request) {
        requireIfMatch(principal, id, ifMatch);
        StatusChangeResponse response = tickets.resolve(principal, id, request);
        return ResponseEntity.ok().eTag(response.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<StatusChangeResponse> reopen(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ReopenRequest request) {
        requireIfMatch(principal, id, ifMatch);
        StatusChangeResponse response = tickets.reopen(principal, id, request);
        return ResponseEntity.ok().eTag(response.etag())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * The optimistic-concurrency pre-check.
     *
     * <p>Applied to every mutation except {@code /messages}. It is <b>advisory</b>: the
     * authority is {@code @Version} on the entity, and Hibernate raises
     * {@code OptimisticLockingFailureException} — mapped to the same {@code 409} — for
     * anything that slips between this check and the write. What the pre-check buys is a
     * cheap, unambiguous refusal before any work is done.
     *
     * <p>The ticket is loaded through {@link TicketAccess}, so a caller who cannot see the
     * ticket gets {@code 404} here rather than a {@code 428} that tells them it exists.
     */
    private void requireIfMatch(ResolvePrincipal principal, Long ticketId, String ifMatch) {
        EtagSupport.requireMatch(ifMatch, access.loadVisible(principal, ticketId).getVersion());
    }

    private ResponseEntity<Object> ok(Object body, ResolvePrincipal principal, Long id) {
        return ResponseEntity.ok()
                .eTag(EtagSupport.etagOf(access.loadVisible(principal, id).getVersion()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /**
     * Uppercases enum-valued filters so {@code ?status=open} works.
     *
     * <p>Not validated against the enum here: an unknown value simply matches nothing, and
     * a 400 for a typo in a filter is a worse experience than an empty list. The values are
     * bound as parameters, so there is no injection risk in letting them through.
     */
    private static Set<String> upper(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        values.forEach(v -> out.add(v.toUpperCase()));
        return out;
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "assigneeId must be a user id, 'me' or 'none'.", e);
        }
    }
}
