package com.resolveai.drafting.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.drafting.repository.DraftRepository;
import com.resolveai.drafting.repository.DraftRepository.CitationRow;
import com.resolveai.drafting.repository.DraftRepository.ClaimRow;
import com.resolveai.drafting.repository.DraftRepository.DraftRow;
import com.resolveai.drafting.web.dto.DraftActionResponse;
import com.resolveai.drafting.web.dto.DraftResponse;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.ai.pii.PiiRedactor;
import com.resolveai.ticketing.service.TicketAccess;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /drafts/{id}} and {@code POST /drafts/{id}/action} — doc 15 Tasks 19–20.
 *
 * <h2>{@code GET /drafts/{id}} returns every claim, including dropped ones</h2>
 *
 * <p>This is, per doc 15, the single most important decision in this endpoint. An agent
 * who sees the system caught the model inventing an amount and a date trusts the claims
 * it kept far more than one who is shown only a shorter, unexplained draft. Hiding the
 * rejection makes the verification mechanism invisible, and an invisible verification
 * mechanism earns no trust for the drafts it does approve.
 */
@Service
public class DraftReadService {

    private final DraftRepository drafts;
    private final TicketAccess access;
    private final PiiRedactor redactor;

    public DraftReadService(DraftRepository drafts, TicketAccess access, PiiRedactor redactor) {
        this.drafts = drafts;
        this.access = access;
        this.redactor = redactor;
    }

    @Transactional(readOnly = true)
    public DraftResponse get(ResolvePrincipal principal, Long draftId) {
        DraftRow draft = drafts.findById(draftId)
                .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_NOT_FOUND,
                        "Draft " + draftId + " was not found."));
        // The ticket visibility check is the authority here: a draft with no visible
        // ticket is not this caller's to read, regardless of the draft id itself.
        var ticket = access.loadVisible(principal, draft.ticketId());

        List<ClaimRow> claimRows = drafts.findClaims(draftId);
        List<DraftResponse.ClaimView> claims = claimRows.stream()
                .map(c -> toClaimView(draft.ticketId(), c))
                .toList();

        List<DraftResponse.SourceView> sources = claims.stream()
                .flatMap(c -> c.citations().stream())
                .map(cit -> new DraftResponse.SourceView(cit.documentId(), cit.documentTitle(),
                        cit.source(), tierOf(cit.source())))
                .distinct()
                .toList();

        List<String> unresolvedAspects = drafts.findUnresolvedAspects(draftId);
        String assembledText = draft.assembledText() == null ? null
                : redactor.rehydrate(draft.ticketId(), draft.assembledText());

        return new DraftResponse(draft.id(), draft.ticketId(), draft.status(),
                draft.coverage(), claims, assembledText, unresolvedAspects, sources,
                draft.suppressionReason(),
                "SUPPRESSED_LOW_COVERAGE".equals(draft.status())
                        || "SUPPRESSED_NO_EVIDENCE".equals(draft.status())
                        ? "ESCALATE_TO_HUMAN" : null,
                draft.promptVersionLabel(), draft.modelId(), draft.tokensIn(),
                draft.tokensOut(), draft.costMicros(), draft.latencyMs(), draft.createdAt());
    }

    private DraftResponse.ClaimView toClaimView(Long ticketId, ClaimRow row) {
        List<DraftResponse.CitationView> citations = row.kept()
                ? drafts.findCitations(row.id()).stream()
                        .map(cit -> toCitationView(ticketId, cit))
                        .toList()
                : List.of();
        return new DraftResponse.ClaimView(row.ordinal(),
                redactor.rehydrate(ticketId, row.text()), row.verdict(), row.kept(),
                row.rejectionReason(), citations);
    }

    private DraftResponse.CitationView toCitationView(Long ticketId, CitationRow row) {
        return new DraftResponse.CitationView(row.chunkId(), row.documentId(),
                row.documentTitle(), row.source(), row.charStart(), row.charEnd(),
                row.snippet());
    }

    private static String tierOf(String source) {
        return "RESOLVED_TICKET".equals(source) ? "PRECEDENT" : "AUTHORITATIVE";
    }

    /**
     * Records what the agent did with the draft. Server-computed Levenshtein distance —
     * never trusted from the client, because a client-reported edit distance is a client-
     * reported quality metric about its own behaviour, which is exactly the number this
     * endpoint exists to make trustworthy by not letting the client set it.
     */
    @Transactional
    public DraftActionResponse recordAction(ResolvePrincipal principal, Long draftId,
                                            String action, String finalText) {
        DraftRow draft = drafts.findById(draftId)
                .orElseThrow(() -> new ApiException(ErrorCode.DRAFT_NOT_FOUND,
                        "Draft " + draftId + " was not found."));
        access.loadVisible(principal, draft.ticketId());

        if ("EDITED".equals(action) && (finalText == null || finalText.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "finalText is required when action is EDITED.");
        }

        Integer editDistance = null;
        if ("EDITED".equals(action) && draft.assembledText() != null) {
            editDistance = levenshtein(draft.assembledText(), finalText);
        } else if ("SENT_AS_IS".equals(action)) {
            editDistance = 0;
        }

        boolean recorded = drafts.insertAction(draftId, principal.userId(), action,
                editDistance, finalText);
        if (!recorded) {
            throw new ApiException(ErrorCode.ACTION_ALREADY_RECORDED,
                    "An action has already been recorded for draft " + draftId + ".");
        }

        return new DraftActionResponse(draftId, action, editDistance,
                java.time.OffsetDateTime.now());
    }

    /**
     * Classic dynamic-programming edit distance, {@code O(n*m)} space. Draft text is
     * bounded (a resolution draft, not a novel), so the space cost is not a concern here
     * the way it might be over arbitrary user input.
     */
    static int levenshtein(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
