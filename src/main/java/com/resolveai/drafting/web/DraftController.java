package com.resolveai.drafting.web;

import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.drafting.service.DraftReadService;
import com.resolveai.drafting.service.DraftRequestService;
import com.resolveai.drafting.web.dto.DraftAcceptedResponse;
import com.resolveai.drafting.web.dto.DraftActionResponse;
import com.resolveai.drafting.web.dto.DraftResponse;
import com.resolveai.drafting.web.dto.RecordActionRequest;
import com.resolveai.drafting.web.dto.RequestDraftRequest;
import com.resolveai.iam.security.ResolvePrincipal;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three drafting endpoints from doc 05 §3.3: request, read, act on.
 *
 * <p>Split across two base paths in one controller, matching the API contract exactly:
 * {@code POST /tickets/{id}/drafts} hangs off a ticket, {@code GET /drafts/{id}} and
 * {@code POST /drafts/{id}/action} stand alone once a draft exists. All three are
 * {@code AGENT}+ — a draft is a tool for the person replying, never surfaced to a
 * customer.
 */
@RestController
public class DraftController {

    private final DraftRequestService requests;
    private final DraftReadService reads;

    public DraftController(DraftRequestService requests, DraftReadService reads) {
        this.requests = requests;
        this.reads = reads;
    }

    @PostMapping("/api/v1/tickets/{id}/drafts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @IsAgentOrAbove
    public DraftAcceptedResponse request(
            @AuthenticationPrincipal ResolvePrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) RequestDraftRequest request) {
        String instruction = request == null ? null : request.instruction();
        var accepted = requests.request(principal, id, instruction);
        return new DraftAcceptedResponse(accepted.draftId(), accepted.status(),
                Map.of("self", "/api/v1/drafts/" + accepted.draftId()),
                accepted.estimatedSeconds());
    }

    @GetMapping("/api/v1/drafts/{id}")
    @IsAgentOrAbove
    public DraftResponse get(@AuthenticationPrincipal ResolvePrincipal principal,
                             @PathVariable Long id) {
        return reads.get(principal, id);
    }

    @PostMapping("/api/v1/drafts/{id}/action")
    @ResponseStatus(HttpStatus.CREATED)
    @IsAgentOrAbove
    public DraftActionResponse action(@AuthenticationPrincipal ResolvePrincipal principal,
                                      @PathVariable Long id,
                                      @Valid @RequestBody RecordActionRequest request) {
        return reads.recordAction(principal, id, request.action(), request.finalText());
    }
}
