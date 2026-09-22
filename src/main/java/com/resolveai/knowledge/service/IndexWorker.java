package com.resolveai.knowledge.service;

import com.resolveai.knowledge.domain.KnowledgeDocument;
import com.resolveai.knowledge.repository.KnowledgeChunkRepository;
import com.resolveai.knowledge.repository.KnowledgeChunkWriter;
import com.resolveai.knowledge.repository.KnowledgeDocumentRepository;
import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.outbox.EventType;
import com.resolveai.platform.outbox.NonRetryableException;
import com.resolveai.platform.outbox.OutboxEvent;
import com.resolveai.platform.outbox.Worker;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Chunks a document, embeds every chunk, and replaces the index atomically.
 *
 * <h2>The same three-phase shape as {@code TriageWorker}, for the same reason</h2>
 *
 * <pre>
 *   tx1  short read   — load the document
 *   ---  no tx        — chunk, then embed every chunk (one network call each)
 *   tx2  short write  — delete old chunks, insert new ones, mark indexed
 * </pre>
 *
 * <p>A forty-chunk runbook is forty embedding calls. Holding a connection across even
 * one of them for the length of a batch would be the same connection-pool exhaustion
 * {@code WorkerRuntime}'s class comment describes for triage, just reached by a document
 * upload instead of a ticket burst.
 *
 * <h2>Delete-then-insert, both in {@code tx2}</h2>
 *
 * <p>Chunk boundaries are not stable across a content edit — see
 * {@code KnowledgeChunkRepository.deleteChunksByDocumentId} — so there is nothing to diff.
 * Doing both inside one transaction is what stops a reader ever observing a document with
 * no chunks at all: the old set and the new set swap in one commit.
 */
@Component
public class IndexWorker implements Worker {

    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);

    /** Matches {@code EmbeddingService}'s pinned model id. */
    private static final String EMBEDDING_MODEL_ID = "text-embedding-3-small";

    private final TransactionTemplate txTemplate;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeChunkWriter chunkWriter;
    private final DocumentChunker chunker;
    private final EmbeddingService embeddings;
    private final ObjectMapper objectMapper;

    public IndexWorker(TransactionTemplate txTemplate, KnowledgeDocumentRepository documents,
                       KnowledgeChunkRepository chunks, KnowledgeChunkWriter chunkWriter,
                       DocumentChunker chunker, EmbeddingService embeddings,
                       ObjectMapper objectMapper) {
        this.txTemplate = txTemplate;
        this.documents = documents;
        this.chunks = chunks;
        this.chunkWriter = chunkWriter;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<EventType> handles() {
        return Set.of(EventType.KNOWLEDGE_DOCUMENT_ADDED);
    }

    /**
     * Ten minutes. A large runbook legitimately takes minutes to chunk and embed — many
     * small network calls, each with its own latency — and five minutes (triage's
     * timeout, for a single call) would let the reaper hand a slow-but-working document
     * to a second worker mid-embed.
     */
    @Override
    public Duration visibilityTimeout() {
        return Duration.ofMinutes(10);
    }

    @Override
    public int batchSize() {
        return 3;
    }

    /** A chunk spec paired with the vector computed for its (heading-prefixed) embedded form. */
    private record EmbeddedChunk(DocumentChunker.ChunkSpec spec, float[] vector) {
    }

    @Override
    public void process(OutboxEvent event) {
        Long documentId = readDocumentId(event);

        KnowledgeDocument document = txTemplate.execute(status ->
                documents.findById(documentId).orElse(null));
        if (document == null) {
            throw new NonRetryableException("Knowledge document " + documentId
                    + " no longer exists");
        }

        List<DocumentChunker.ChunkSpec> specs = chunker.chunk(document.getBody());
        if (specs.isEmpty()) {
            log.warn("Document {} produced zero chunks; indexing it as empty", documentId);
        }

        // Embedded outside any transaction: one network call per chunk, and holding a
        // connection across all of them is the exact mistake WorkerRuntime's class
        // comment is written to prevent — here reached through document upload instead
        // of a ticket burst.
        List<EmbeddedChunk> embedded = specs.stream()
                .map(spec -> new EmbeddedChunk(spec,
                        embeddings.embed(event.tenantId(), spec.embeddedText())))
                .toList();

        txTemplate.executeWithoutResult(status -> persist(documentId, embedded));
    }

    private void persist(Long documentId, List<EmbeddedChunk> embeddedChunks) {
        // Re-read inside the write transaction: the document may have been re-edited
        // between the read phase and now, and this must not silently index a version
        // that was already superseded by a second concurrent upload.
        KnowledgeDocument current = documents.findById(documentId)
                .orElseThrow(() -> new NonRetryableException(
                        "Knowledge document " + documentId + " vanished mid-index"));

        chunks.deleteChunksByDocumentId(current.getId());

        int ordinal = 0;
        for (EmbeddedChunk chunk : embeddedChunks) {
            chunkWriter.insert(current.getId(), ordinal++, chunk.spec().text(),
                    chunk.spec().tokenCount(), chunk.spec().charStart(), chunk.spec().charEnd(),
                    EmbeddingService.toVectorLiteral(chunk.vector()), EMBEDDING_MODEL_ID);
        }

        current.markIndexed(OffsetDateTime.now());
        documents.saveAndFlush(current);
        log.info("Indexed document {} ({}): {} chunk(s), kb_version now {}",
                current.getId(), current.getTitle(), embeddedChunks.size(),
                current.getKbVersion());
    }

    @SuppressWarnings("unchecked")
    private Long readDocumentId(OutboxEvent event) {
        try {
            Map<String, Object> body = objectMapper.readValue(event.payload(), Map.class);
            Object id = body.get("documentId");
            return id == null ? event.aggregateId() : Long.valueOf(String.valueOf(id));
        } catch (RuntimeException e) {
            throw new NonRetryableException("Unreadable payload on outbox event "
                    + event.id(), e);
        }
    }
}
