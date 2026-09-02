package com.resolveai.drafting.service;

import com.resolveai.drafting.domain.ClaimVerdict;
import com.resolveai.drafting.domain.DraftStatus;
import com.resolveai.drafting.domain.RawClaim;
import com.resolveai.drafting.repository.DraftRepository;
import com.resolveai.knowledge.repository.HybridSearchRepository;
import com.resolveai.knowledge.repository.HybridSearchRepository.HybridResult;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.ai.model.LlmExceptions.BudgetExhaustedException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmParseException;
import com.resolveai.platform.ai.model.LlmExceptions.LlmUnavailableException;
import com.resolveai.platform.ai.pii.PiiRedactor;
import com.resolveai.platform.ai.prompt.PromptVersion;
import com.resolveai.platform.ai.prompt.PromptVersionRepository;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.NonRetryableException;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.Worker;
import com.resolveai.ticketing.domain.Ticket;
import com.resolveai.ticketing.domain.TicketEventType;
import com.resolveai.ticketing.repository.TicketRepository;
import com.resolveai.ticketing.service.TicketEventRecorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Assembles one draft: retrieve, generate claims, verify them, decide whether to show
 * anything. Doc 15 Task 18.
 *
 * <h2>The same three-phase shape as {@code TriageWorker} and {@code IndexWorker}</h2>
 *
 * <pre>
 *   tx1  short read   — ticket text, active prompts, AI policy
 *   ---  no tx        — embed, hybrid retrieve, generate, numeric filter, entailment
 *   tx2  short write  — draft, claims, citations, unresolved aspects
 * </pre>
 *
 * <p>The middle phase here is the longest in the whole system — a hybrid search, a
 * generation call, and up to several entailment calls run concurrently — which makes it
 * the phase where holding a database connection would be most damaging, not least.
 *
 * <h2>Failure is a first-class outcome, the same table as {@code TriageWorker}'s</h2>
 *
 * <table border="1">
 *   <caption>What each failure does</caption>
 *   <tr><th>Cause</th><th>Draft ends</th><th>Event</th></tr>
 *   <tr><td>No knowledge base content retrieved</td><td>{@code SUPPRESSED_NO_EVIDENCE}</td>
 *       <td>done</td></tr>
 *   <tr><td>Coverage below threshold</td><td>{@code SUPPRESSED_LOW_COVERAGE}</td>
 *       <td>done</td></tr>
 *   <tr><td>Fabricated citation in generation</td><td>{@code FAILED}</td><td>done —
 *       retrying asks the same deterministic-temperature model the same question</td></tr>
 *   <tr><td>Budget exhausted mid-pipeline</td><td>{@code FAILED}</td><td>done</td></tr>
 *   <tr><td>Provider unreachable during generation or verification</td>
 *       <td>none written</td><td>rethrown: retried with backoff, then dead-lettered</td></tr>
 * </table>
 *
 * <p><b>A draft partially verified by a provider failure is never shown.</b>
 * {@link EntailmentVerifier.EntailmentUnavailableException} propagates out of this method
 * entirely — see that class's comment — which is what stops a five-claim draft where two
 * verifications could not run from being scored as though those two had failed and shown
 * anyway with an understated coverage.
 */
@Component
public class DraftWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(DraftWorker.class);

    private static final String PROMPT_NAME = "draft";
    private static final int RETRIEVAL_K = 8;

    private final TransactionTemplate txTemplate;
    private final TicketRepository tickets;
    private final PromptVersionRepository prompts;
    private final PiiRedactor redactor;
    private final EmbeddingService embeddings;
    private final AiPolicyService policies;
    private final HybridSearchRepository hybridSearch;
    private final ClaimGenerator claimGenerator;
    private final NumericVerifier numericVerifier;
    private final EntailmentVerifier entailmentVerifier;
    private final CoverageService coverage;
    private final DraftRepository drafts;
    private final TicketEventRecorder eventRecorder;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DraftWorker(TransactionTemplate txTemplate, TicketRepository tickets,
                       PromptVersionRepository prompts, PiiRedactor redactor,
                       EmbeddingService embeddings, AiPolicyService policies,
                       HybridSearchRepository hybridSearch, ClaimGenerator claimGenerator,
                       NumericVerifier numericVerifier, EntailmentVerifier entailmentVerifier,
                       CoverageService coverage, DraftRepository drafts,
                       TicketEventRecorder eventRecorder, ObjectMapper objectMapper) {
        this.txTemplate = txTemplate;
        this.tickets = tickets;
        this.prompts = prompts;
        this.redactor = redactor;
        this.embeddings = embeddings;
        this.policies = policies;
        this.hybridSearch = hybridSearch;
        this.claimGenerator = claimGenerator;
        this.numericVerifier = numericVerifier;
        this.entailmentVerifier = entailmentVerifier;
        this.coverage = coverage;
        this.drafts = drafts;
        this.eventRecorder = eventRecorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EventType> handles() {
        return Set.of(EventType.DRAFT_REQUESTED);
    }

    /** Retrieval, generation and several entailment calls: the slowest pipeline in the system. */
    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(8);
    }

    @Override
    public int batchSize() {
        return 3;
    }

    private record Context(Long tenantId, Long draftId, Long ticketId, String subject,
                           String body, PromptVersion draftPrompt) {
    }

    @Override
    public void process(OutboxEvent event) {
        Long draftId = readDraftId(event);

        Context context = txTemplate.execute(status -> load(event, draftId));
        if (context == null) {
            throw new NonRetryableException("Draft " + draftId + " or its ticket no longer exists");
        }

        try {
            runPipeline(context);
        } catch (BudgetExhaustedException | LlmParseException
                 | ClaimGenerator.FabricatedCitationException e) {
            log.warn("Draft {} failed terminally: {}", context.draftId(), e.getMessage());
            txTemplate.executeWithoutResult(status -> drafts.markFailed(context.draftId()));
        }
        // LlmUnavailableException and EntailmentUnavailableException propagate
        // uncaught — retried with backoff by WorkerRuntime, exactly like a triage
        // provider outage. Nothing has been written for this attempt, so a retry finds
        // the draft still PENDING and tries the whole pipeline again cleanly.
    }

    private void runPipeline(Context context) {
        // ── no transaction: retrieval, generation, verification ─────────────
        String redactedTicketText = redactor.redact(context.tenantId(), context.ticketId(),
                context.subject() + "\n\n" + context.body()).redactedText();

        float[] queryVector = embeddings.embed(context.tenantId(), redactedTicketText);
        List<HybridResult> retrieved = hybridSearchInTenant(context, redactedTicketText,
                queryVector);

        if (retrieved.isEmpty()) {
            txTemplate.executeWithoutResult(status -> drafts.markSuppressed(context.draftId(),
                    DraftStatus.SUPPRESSED_NO_EVIDENCE.name(), 0,
                    coverage.suppressionReasonFor(DraftStatus.SUPPRESSED_NO_EVIDENCE, 0, 0, 0),
                    0, 0, 0, 0));
            recordSuppressed(context, DraftStatus.SUPPRESSED_NO_EVIDENCE);
            return;
        }

        ClaimGenerator.Generation generation = claimGenerator.generate(context.tenantId(),
                context.draftPrompt(), redactedTicketText, retrieved);

        Map<Long, HybridResult> chunksById = retrieved.stream()
                .collect(java.util.stream.Collectors.toMap(HybridResult::chunkId, r -> r));

        List<VerifiedClaim> verified = verifyClaims(context.tenantId(), generation.claims(),
                chunksById);

        List<ClaimVerdict> verdicts = verified.stream().map(VerifiedClaim::verdict).toList();
        double coverageScore = coverage.coverage(verdicts);
        DraftStatus status = coverage.statusFor(coverageScore, true);

        long totalCost = generation.costMicros()
                + verified.stream().mapToLong(VerifiedClaim::costMicros).sum();

        // ── tx2: persist everything ──────────────────────────────────────
        txTemplate.executeWithoutResult(status0 -> persist(context, status, coverageScore,
                verified, generation, totalCost, chunksById));
    }

    /**
     * The ticket's own subject and body is the query, sent as text for the lexical half
     * as well as embedded for the vector half — a real ticket body contains real words
     * ("UPI", "ERR_..."), so the lexical CTE is not wasted here the way it would be if
     * this were an empty string.
     */
    private List<HybridResult> hybridSearchInTenant(Context context, String queryText,
                                                     float[] queryVector) {
        return com.resolveai.platform.tenant.TenantContext.callAs(context.tenantId(),
                () -> hybridSearch.search(queryText, queryVector, RETRIEVAL_K, null));
    }

    private record VerifiedClaim(RawClaim raw, ClaimVerdict verdict, String verifierModel,
                                 String rejectionReason, List<Long> citedChunkIds,
                                 long costMicros) {
    }

    /**
     * The verification order Task 14 insists on: numeric first, because it is free and
     * certain, and catches the most damaging failure mode at zero LLM cost. Only claims
     * that survive it reach the entailment verifier at all.
     */
    private List<VerifiedClaim> verifyClaims(Long tenantId, List<RawClaim> claims,
                                             Map<Long, HybridResult> chunksById) {
        List<VerifiedClaim> results = new ArrayList<>();
        List<String[]> pairs = new ArrayList<>();
        List<Integer> pairClaimIndex = new ArrayList<>();
        List<List<Long>> pairChunkIds = new ArrayList<>();

        for (int i = 0; i < claims.size(); i++) {
            RawClaim claim = claims.get(i);
            List<Long> citedChunkIds = claim.citationIds().stream()
                    .map(id -> Long.valueOf(id.replace("chunk:", "")))
                    .toList();
            List<String> spans = citedChunkIds.stream()
                    .map(id -> chunksById.get(id).text())
                    .toList();

            NumericVerifier.Verdict numeric = numericVerifier.verify(claim.text(), spans);
            if (numeric instanceof NumericVerifier.Verdict.Fail fail) {
                results.add(new VerifiedClaim(claim, ClaimVerdict.FAILED_NUMERIC_CHECK, null,
                        fail.reason(), citedChunkIds, 0));
                continue;
            }

            // Verified against its single best (first-cited) span. A claim citing
            // several chunks is checked against the one it named first; doc 15 Task 15
            // verifies "a claim against its cited span" in the singular, and the first
            // citation is the model's own primary evidence for the claim.
            results.add(null); // placeholder to keep index alignment; filled after entailment
            pairs.add(new String[]{claim.text(), spans.get(0)});
            pairClaimIndex.add(i);
            pairChunkIds.add(citedChunkIds);
        }

        if (!pairs.isEmpty()) {
            List<EntailmentVerifier.Result> entailed = entailmentVerifier.verifyAll(tenantId, pairs);
            for (int j = 0; j < entailed.size(); j++) {
                int claimIndex = pairClaimIndex.get(j);
                RawClaim claim = claims.get(claimIndex);
                EntailmentVerifier.Result er = entailed.get(j);
                ClaimVerdict verdict = switch (er.verdict()) {
                    case SUPPORTED -> ClaimVerdict.SUPPORTED;
                    case PARTIAL -> ClaimVerdict.PARTIAL;
                    case NOT_SUPPORTED -> ClaimVerdict.NOT_SUPPORTED;
                };
                String rejection = verdict == ClaimVerdict.NOT_SUPPORTED
                        ? "The cited span does not support this claim." : null;
                results.set(claimIndex, new VerifiedClaim(claim, verdict, "entailment@1",
                        rejection, pairChunkIds.get(j), er.costMicros()));
            }
        }
        return results;
    }

    private void persist(Context context, DraftStatus status, double coverageScore,
                         List<VerifiedClaim> verified, ClaimGenerator.Generation generation,
                         long totalCost, Map<Long, HybridResult> chunksById) {
        String assembledText = null;
        String suppressionReason = null;
        int supportedOrPartial = (int) verified.stream()
                .filter(v -> v.verdict().isKept()).count();

        if (status == DraftStatus.SHOWN) {
            assembledText = assemble(verified);
            drafts.markShown(context.draftId(), coverageScore, assembledText,
                    generation.tokensIn(), generation.tokensOut(), totalCost,
                    generation.latencyMs());
        } else {
            suppressionReason = coverage.suppressionReasonFor(status, supportedOrPartial,
                    verified.size(), coverageScore);
            drafts.markSuppressed(context.draftId(), status.name(), coverageScore,
                    suppressionReason, generation.tokensIn(), generation.tokensOut(), totalCost,
                    generation.latencyMs());
        }

        int ordinal = 1;
        for (VerifiedClaim v : verified) {
            Long claimId = drafts.insertClaim(context.draftId(), ordinal++, v.raw().text(),
                    v.verdict().name(), v.verifierModel(), v.verdict().isKept(),
                    v.rejectionReason());
            if (claimId != null && v.verdict().isKept()) {
                for (Long chunkId : v.citedChunkIds()) {
                    // The whole chunk is the cited span in this phase (no sub-chunk span
                    // selection), so the citation's offsets are chunk-relative — 0 to the
                    // chunk's own text length — not the chunk's offsets into its parent
                    // DOCUMENT (knowledge_chunk.char_start/char_end), which describe a
                    // different, longer string. DraftRepository.findCitations slices
                    // knowledge_chunk.text with these, and a document-relative offset
                    // against the chunk's own (shorter) text would slice the wrong span
                    // or run past the end of it.
                    HybridResult chunk = chunksById.get(chunkId);
                    if (chunk != null) {
                        drafts.insertCitation(claimId, chunkId, 0, chunk.text().length());
                    }
                }
            }
        }

        int aspectOrdinal = 1;
        for (String aspect : generation.unresolvedAspects()) {
            drafts.insertUnresolvedAspect(context.draftId(), aspectOrdinal++, aspect);
        }

        Ticket ticket = tickets.findById(context.ticketId()).orElseThrow(
                () -> new NonRetryableException("Ticket " + context.ticketId()
                                                + " vanished mid-draft"));
        eventRecorder.record(ticket, TicketEventType.MESSAGE_ADDED, null,
                "DRAFT_" + status.name(),
                Map.of("draftId", context.draftId(), "coverage", coverageScore,
                        "claimCount", verified.size(), "supportedOrPartial", supportedOrPartial));

        log.info("Draft {} for ticket {}: {} ({} of {} claims kept, coverage {})",
                context.draftId(), ticket.getReference(), status, supportedOrPartial,
                verified.size(), coverageScore);
    }

    private String assemble(List<VerifiedClaim> verified) {
        StringBuilder text = new StringBuilder();
        for (VerifiedClaim v : verified) {
            if (v.verdict().isKept()) {
                text.append(v.raw().text()).append(' ');
            }
        }
        return text.toString().strip();
    }

    private void recordSuppressed(Context context, DraftStatus status) {
        Ticket ticket = tickets.findById(context.ticketId()).orElse(null);
        if (ticket != null) {
            txTemplate.executeWithoutResult(tx -> eventRecorder.record(ticket,
                    TicketEventType.MESSAGE_ADDED, null, "DRAFT_" + status.name(), Map.of()));
        }
    }

    private Context load(OutboxEvent event, Long draftId) {
        var draftRow = drafts.findById(draftId);
        if (draftRow.isEmpty()) {
            return null;
        }
        Ticket ticket = tickets.findById(draftRow.get().ticketId()).orElse(null);
        if (ticket == null) {
            return null;
        }
        PromptVersion prompt = prompts.findByNameAndActiveTrue(PROMPT_NAME)
                .orElseThrow(() -> new NonRetryableException(
                        "No active prompt named " + PROMPT_NAME));
        return new Context(event.tenantId(), draftId, ticket.getId(), ticket.getSubject(),
                ticket.getBody(), prompt);
    }

    @SuppressWarnings("unchecked")
    private Long readDraftId(OutboxEvent event) {
        try {
            Map<String, Object> body = objectMapper.readValue(event.payload(), Map.class);
            Object id = body.get("draftId");
            return id == null ? event.aggregateId() : Long.valueOf(String.valueOf(id));
        } catch (RuntimeException e) {
            throw new NonRetryableException("Unreadable payload on outbox event "
                    + event.id(), e);
        }
    }
}
