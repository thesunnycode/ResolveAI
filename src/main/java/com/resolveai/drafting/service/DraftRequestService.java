package com.resolveai.drafting.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.knowledge.service.KnowledgeDocumentService;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxPublisher;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.service.TicketAccess;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /tickets/{id}/drafts} — doc 15 Task 18, the synchronous half.
 *
 * <p>{@code DraftWorker} does the actual work; this class validates the request, creates
 * the {@code PENDING} row and queues the event, in one transaction, the same pattern
 * {@code TicketService.create} uses for triage.
 */
@Service
public class DraftRequestService {

    /** Doc 05: 10 requests per minute per agent. Enforced in Phase 9's rate limiter; the
     * constant lives here so the limiter and this class agree on the number. */
    public static final int RATE_LIMIT_PER_MINUTE = 10;

    private final TicketAccess access;
    private final PromptVersionRepository prompts;
    private final AiPolicyService policies;
    private final KnowledgeDocumentService knowledgeDocuments;
    private final com.resolveai.drafting.repository.DraftRepository drafts;
    private final OutboxPublisher outbox;

    public DraftRequestService(TicketAccess access, PromptVersionRepository prompts,
                               AiPolicyService policies,
                               KnowledgeDocumentService knowledgeDocuments,
                               com.resolveai.drafting.repository.DraftRepository drafts,
                               OutboxPublisher outbox) {
        this.access = access;
        this.prompts = prompts;
        this.policies = policies;
        this.knowledgeDocuments = knowledgeDocuments;
        this.drafts = drafts;
        this.outbox = outbox;
    }

    public record DraftAccepted(Long draftId, String status, int estimatedSeconds) {
    }

    @Transactional
    public DraftAccepted request(ResolvePrincipal principal, Long ticketId, String instruction) {
        Ticket ticket = access.loadVisible(principal, ticketId);
        access.requireAgentOrAbove(principal, "request a draft");

        if (drafts.hasPendingDraft(ticketId)) {
            throw new ApiException(ErrorCode.DRAFT_IN_PROGRESS,
                    "A draft is already pending for this ticket.");
        }
        if (!knowledgeDocuments.hasAnyIndexedDocument()) {
            throw new ApiException(ErrorCode.NO_KNOWLEDGE_BASE,
                    "This tenant has no indexed knowledge base content; a grounded draft "
                    + "cannot be produced.");
        }

        AiPolicyService.Decision decision = policies.check(principal.tenantId());
        if (!decision.externalModelAllowed()) {
            throw new ApiException(ErrorCode.AI_DISABLED_BY_POLICY,
                    "This tenant does not permit external models.");
        }
        // The budget check here is advisory — the authoritative check happens inside
        // ModelRouter.call at generation time, against the estimate for that specific
        // call. This one exists only to fail fast with 402 before an outbox event is
        // even queued, for the common case of a budget that is obviously already spent.
        if (decision.budgetRemainingMicros() <= 0) {
            throw new ApiException(ErrorCode.AI_BUDGET_EXHAUSTED,
                    "This tenant's monthly AI budget is exhausted.");
        }

        PromptVersion prompt = prompts.findByNameAndActiveTrue("draft")
                .orElseThrow(() -> new IllegalStateException("No active prompt named draft"));

        Long draftId = drafts.insertPending(ticketId, principal.tenantId(), prompt.getId(),
                prompt.getModelId(), principal.userId(), instruction);

        outbox.publish("DRAFT", draftId, EventType.DRAFT_REQUESTED,
                Map.of("draftId", draftId));

        return new DraftAccepted(draftId, "PENDING", 18);
    }
}
