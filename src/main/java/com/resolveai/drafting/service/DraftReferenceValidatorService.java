package com.resolveai.drafting.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.drafting.repository.DraftRepository;
import com.resolveai.drafting.repository.DraftRepository.DraftRow;
import com.resolveai.ticketing.service.DraftReferenceValidator;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real implementation of the port {@code ticketing} calls. Doc 15 Task 21.
 *
 * <p>Its mere existence replaces {@code NoOpDraftReferenceValidator}, registered
 * {@code @ConditionalOnMissingBean} — the same switch-over {@code SlaLifecycleService}
 * performed for the SLA port in Phase 5, with no call site in {@code TicketService}
 * changing.
 */
@Service
public class DraftReferenceValidatorService implements DraftReferenceValidator {

    private final DraftRepository drafts;

    public DraftReferenceValidatorService(DraftRepository drafts) {
        this.drafts = drafts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireValid(Long ticketId, Long draftId) {
        DraftRow draft = drafts.findById(draftId).orElse(null);
        if (draft == null || !Objects.equals(draft.ticketId(), ticketId)
                || !"SHOWN".equals(draft.status())) {
            throw new ApiException(ErrorCode.INVALID_DRAFT_REFERENCE,
                    "Draft " + draftId + " does not exist, does not belong to this ticket, "
                    + "or is not SHOWN.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAutomaticAction(Long draftId, Long agentId, String sentBody) {
        DraftRow draft = drafts.findById(draftId).orElse(null);
        if (draft == null) {
            return;
        }
        // Already handled: an agent who called POST /drafts/{id}/action by hand before
        // sending must not have that overwritten by automatic capture racing in behind
        // them — the explicit call is the more deliberate signal and wins.
        if (drafts.findAction(draftId).isPresent()) {
            return;
        }

        boolean identical = draft.assembledText() != null
                && draft.assembledText().equals(sentBody);
        String action = identical ? "SENT_AS_IS" : "EDITED";
        Integer editDistance = identical ? 0
                : DraftReadService.levenshtein(draft.assembledText(), sentBody);

        drafts.insertAction(draftId, agentId, action, editDistance, sentBody);
    }
}
