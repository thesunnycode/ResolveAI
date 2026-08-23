package com.resolveai.triage.web;

import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.triage.service.PriorityRationaleService;
import com.resolveai.triage.service.RetriageService;
import com.resolveai.triage.web.dto.PriorityRationaleResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two triage endpoints that hang off a ticket.
 *
 * <h2>Why a second controller on the same base path</h2>
 *
 * <p>These are {@code /api/v1/tickets/{id}/…} URLs, so the obvious home is
 * {@code TicketController}. They are not there for one reason: {@code triage} already
 * depends on {@code ticketing} — the worker reads tickets, sets their priority and
 * starts their clocks — and putting these handlers in {@code TicketController} would
 * make {@code ticketing} depend back on {@code triage}. A cycle between the two largest
 * packages in the system is the thing that ends up making neither of them movable.
 *
 * <p>Spring is entirely happy with two {@code @RestController}s sharing a base path and
 * mapping different sub-paths; the URL space stays exactly what the API contract says,
 * and the dependency arrow stays pointing one way. The URL is the client's concern and
 * the package is ours, and they are allowed to disagree.
 *
 * <p><b>Both are {@code AGENT}+.</b> A rationale exposes the model's internal reading of
 * a customer's message — {@code linguisticUrgency: HIGH} is a judgement about how
 * somebody wrote to us, and they would rightly object to seeing it — and a retriage
 * spends the tenant's model budget.
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TriageController {

    private final PriorityRationaleService rationales;
    private final RetriageService retriage;

    public TriageController(PriorityRationaleService rationales, RetriageService retriage) {
        this.rationales = rationales;
        this.retriage = retriage;
    }

    /**
     * Why this ticket has this priority.
     *
     * <p>No ETag and no {@code If-Match}: a rationale is an immutable record of a
     * decision that has already been made, so there is nothing here to conflict with.
     */
    @GetMapping("/{id}/priority-rationale")
    @IsAgentOrAbove
    public PriorityRationaleResponse priorityRationale(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id) {
        return rationales.forTicket(principal, id);
    }

    /**
     * Queues a fresh triage.
     *
     * <p>{@code 202}, like ticket creation and for the same reason: the work is accepted,
     * not done, and the response points at the resource to watch.
     *
     * <p>Deliberately no {@code If-Match}. This queues a job rather than mutating the
     * ticket, so there is no version to conflict on — and requiring one would make
     * "retry the triage that failed" return {@code 428} to a client that never needed to
     * read the ticket in the first place.
     */
    @PostMapping("/{id}/retriage")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @IsAgentOrAbove
    public Map<String, Object> retriage(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id) {
        return retriage.retriage(principal, id);
    }
}
