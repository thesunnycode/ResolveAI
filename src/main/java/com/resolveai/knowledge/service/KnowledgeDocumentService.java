package com.resolveai.knowledge.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.knowledge.domain.KnowledgeDocument;
import com.resolveai.knowledge.repository.KnowledgeChunkRepository;
import com.resolveai.knowledge.repository.KnowledgeDocumentRepository;
import com.resolveai.knowledge.web.dto.DocumentDetailResponse;
import com.resolveai.knowledge.web.dto.DocumentSummaryResponse;
import com.resolveai.knowledge.web.dto.ReindexResponse;
import com.resolveai.platform.ai.model.ModelRates;
import com.resolveai.platform.ai.model.ModelRates.Rate;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.OutboxPublisher;
import com.resolveai.platform.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading, reindexing and removing knowledge documents.
 *
 * <p>Doc 15 §Tasks 4–6. The indexing itself is {@link IndexWorker}'s job; this class owns
 * the synchronous half — validating input, deciding what needs (re)indexing, and the
 * transaction that queues the work.
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    /** Matches {@code IndexWorker.EMBEDDING_MODEL_ID}. */
    private static final String CURRENT_EMBEDDING_MODEL = "text-embedding-3-small";

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final OutboxPublisher outbox;
    private final ModelRates rates;
    private final JdbcTemplate jdbc;

    public KnowledgeDocumentService(KnowledgeDocumentRepository documents,
                                    KnowledgeChunkRepository chunks, OutboxPublisher outbox,
                                    ModelRates rates, JdbcTemplate jdbc) {
        this.documents = documents;
        this.chunks = chunks;
        this.outbox = outbox;
        this.rates = rates;
        this.jdbc = jdbc;
    }

    /**
     * Creates a document and queues it for indexing, in one transaction.
     *
     * <p>{@code content_sha256} is computed here, before the row exists, because
     * {@code uq_kb_content} is what makes uploading the same content twice a harmless
     * no-op rather than a duplicate document silently doubling every retrieval result
     * that would have matched it.
     */
    @Transactional
    public DocumentSummaryResponse create(DocumentSource source, String title, String body,
                                          String uri) {
        String hash = sha256(body);
        if (documents.findByContentSha256(hash).isPresent()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "A document with this exact content already exists in the knowledge "
                    + "base.");
        }

        KnowledgeDocument document = new KnowledgeDocument(source, title, body, uri, hash, null);
        documents.save(document);

        outbox.publish("KNOWLEDGE_DOCUMENT", document.getId(),
                EventType.KNOWLEDGE_DOCUMENT_ADDED, Map.of("documentId", document.getId()));

        log.info("Knowledge document {} ({}) queued for indexing", document.getId(), title);
        return toSummary(document, 0);
    }

    @Transactional(readOnly = true)
    public Page<DocumentSummaryResponse> list(String sourceFilter, int page, int size) {
        Page<KnowledgeDocument> found = sourceFilter == null
                ? documents.findAll(PageRequest.of(page, size))
                : documents.findBySource(sourceFilter, PageRequest.of(page, size));
        return found.map(d -> toSummary(d, chunks.countByDocumentId(d.getId())));
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse detail(Long id) {
        KnowledgeDocument document = documents.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND,
                        "Document " + id + " was not found."));
        var chunkRows = chunks.findByDocumentIdOrderByOrdinalAsc(id).stream()
                .map(c -> new DocumentDetailResponse.ChunkView(c.getId(), c.getOrdinal(),
                        c.getText(), c.getTokenCount(), c.getCharStart(), c.getCharEnd()))
                .toList();
        return new DocumentDetailResponse(document.getId(), document.getSource().name(),
                document.getTitle(), document.getBody(), document.getUri(),
                document.getKbVersion(), document.isIndexed(), chunkRows,
                document.getCreatedAt(), document.getUpdatedAt());
    }

    /**
     * Soft-deletes the document, hard-deletes its chunks.
     *
     * <p><b>The document row has to survive.</b> {@code draft_claim_citation.chunk_id} is
     * {@code ON DELETE RESTRICT}, so a citation pointing at a chunk from this document —
     * from any draft, ever shown to any agent — would make the delete fail with a raw
     * constraint violation if the chunks (and therefore, cascading, the citations) were
     * removed while a citation still referenced one. Checked here explicitly, so the
     * caller gets {@code 409 DOCUMENT_CITED} naming the drafts rather than a 500 from a
     * constraint the API never explained.
     */
    @Transactional
    public void delete(Long id) {
        KnowledgeDocument document = documents.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND,
                        "Document " + id + " was not found."));

        List<Long> citingDrafts = jdbc.queryForList("""
                SELECT DISTINCT dc.draft_id
                  FROM draft_claim_citation cit
                  JOIN knowledge_chunk kc ON kc.id = cit.chunk_id
                  JOIN draft_claim dc ON dc.id = cit.draft_claim_id
                 WHERE kc.document_id = ?
                """, Long.class, id);
        if (!citingDrafts.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_CITED,
                    "Document " + id + " is cited by draft(s) " + citingDrafts
                    + " and cannot be deleted. The drafts remain valid; their citations "
                    + "would otherwise point at nothing.");
        }

        // Hard-delete the chunks — they leave the retrieval index immediately — while
        // the document row survives the @SQLDelete soft delete below, in case a future
        // citation check needs it (or a later phase restores it).
        chunks.deleteChunksByDocumentId(id);
        document.markIndexed(OffsetDateTime.now()); // bumps kb_version one more time
        documents.delete(document);
        log.info("Knowledge document {} deleted; chunks removed from the index", id);
    }

    /**
     * Queues a re-embed of every document whose content or embedding model has changed.
     *
     * <p>Reports the estimate <b>before</b> anything is queued. A forced full reindex is
     * the single easiest way to burn a tenant's monthly AI budget by accident — 75
     * documents, each ~10 embedding calls, is not free — and the number belongs in the
     * response the caller sees before committing to it, not in a bill they discover
     * later.
     */
    @Transactional
    public ReindexResponse reindex(boolean force) {
        List<KnowledgeDocument> candidates = force
                ? documents.findAll()
                : documents.findIndexed();

        int queued = 0;
        int skipped = 0;
        long estimatedCostMicros = 0;
        Rate embeddingRate = rates.rateFor(CURRENT_EMBEDDING_MODEL);

        for (KnowledgeDocument document : candidates) {
            boolean needsReembed = force || document.needsIndexing(sha256(document.getBody()))
                    || hasStaleEmbeddingModel(document.getId());
            if (!needsReembed) {
                skipped++;
                continue;
            }
            outbox.publish("KNOWLEDGE_DOCUMENT", document.getId(),
                    EventType.KNOWLEDGE_DOCUMENT_ADDED, Map.of("documentId", document.getId()));
            queued++;
            // Estimated on the document's current chunk count as a stand-in for its
            // post-reindex count — close enough for a pre-commit estimate, and exact
            // figures are recorded per call by ModelRouter/EmbeddingService once the
            // work actually runs.
            long chunkCount = Math.max(1, chunks.countByDocumentId(document.getId()));
            estimatedCostMicros += embeddingRate.inputPerMillion() * 500 * chunkCount
                    / 1_000_000L;
        }

        // Never-yet-indexed documents always queue, regardless of force — they are not
        // being "reindexed", they simply have not been indexed yet, and force is a
        // question about documents that already have.
        for (KnowledgeDocument pending : documents.findPendingIndexing()) {
            outbox.publish("KNOWLEDGE_DOCUMENT", pending.getId(),
                    EventType.KNOWLEDGE_DOCUMENT_ADDED, Map.of("documentId", pending.getId()));
            queued++;
        }

        String jobId = "reindex-" + OffsetDateTime.now();
        log.info("Reindex {} (force={}): {} queued, {} skipped, ~{}µ estimated",
                jobId, force, queued, skipped, estimatedCostMicros);
        return new ReindexResponse(jobId, queued, skipped,
                skipped > 0 ? "content_sha256 unchanged" : null, estimatedCostMicros);
    }

    private boolean hasStaleEmbeddingModel(Long documentId) {
        Integer stale = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_chunk
                 WHERE document_id = ? AND embedding_model_id <> ?
                """, Integer.class, documentId, CURRENT_EMBEDDING_MODEL);
        return stale != null && stale > 0;
    }

    private DocumentSummaryResponse toSummary(KnowledgeDocument d, long chunkCount) {
        return new DocumentSummaryResponse(d.getId(), d.getSource().name(), d.getTitle(),
                d.getUri(), d.isIndexed(), chunkCount, d.getKbVersion(), d.getCreatedAt());
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** The tenant-wide "has anything at all been indexed" check {@code DraftWorker} needs. */
    @Transactional(readOnly = true)
    public boolean hasAnyIndexedDocument() {
        Long tenantId = TenantContext.getRequired();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_document WHERE tenant_id = ? "
                + "AND indexed_at IS NOT NULL AND deleted_at IS NULL",
                Integer.class, tenantId);
        return count != null && count > 0;
    }
}
