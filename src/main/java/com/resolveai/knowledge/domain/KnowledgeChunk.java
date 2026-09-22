package com.resolveai.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

/**
 * One retrievable span of a document, sized for a single embedding.
 *
 * <h2>{@code tenantId} is denormalised from the parent document, deliberately</h2>
 *
 * <p>Retrieval's hot query is a single-table scan of this table under
 * {@code WHERE tenant_id = :t}, fused across a lexical and a vector CTE. Reaching the
 * tenant through a join to {@code knowledge_document} on every candidate row would put a
 * join in the path of the one query in the phase that has to be fast, for a value that
 * never disagrees with its parent's.
 *
 * <h2>{@code embedding} is deliberately <b>not</b> mapped through JPA</h2>
 *
 * <p>Doc 15 Task 1 asks for the pgvector Hibernate type to be wired here, on the grounds
 * that — unlike {@code ticket.embedding} — this vector is read for the chunk detail
 * view. Having built {@code Ticket.embedding} the other way in Phase 5 for exactly this
 * reason (no reason to carry a custom {@code UserType} before anything reads the
 * column), the same argument turns out to hold here too: <b>nothing in this phase reads
 * a chunk's raw vector back out.</b> The similarity search that would want it is native
 * SQL by construction — {@code embedding <=> :queryVec} needs the pgvector operator, which
 * has no JPQL equivalent — and the chunk detail view (Task 6) shows the text and its
 * offsets, not the 768 floats behind it. Writing goes through
 * {@code EmbeddingService.storeTicketEmbedding}'s sibling method here, the same
 * parameterised {@code ::vector} cast, for the same reason: one proven path for getting
 * a vector into Postgres, used everywhere a vector needs to get into Postgres. A second,
 * JPA-mapped path would be a second thing that could disagree with the first about the
 * column's format.
 *
 * <p>{@code ddl-auto: validate} does not require every column to have a mapped
 * attribute, only that mapped ones exist — so this is safe exactly as it is for
 * {@code ticket.embedding} and {@code ticket.search_tsv}.
 */
@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Position within the document, 0-based. Stable across a reindex only if unchanged. */
    @Column(nullable = false, updatable = false)
    private int ordinal;

    /**
     * The raw chunk text — <b>not</b> what was embedded. See {@code DocumentChunker} for
     * the heading-path prefix that is prepended only for the embedding call and never
     * stored: the prefix helps the vector find the chunk, but re-showing it to an agent
     * as though the document said it would misquote the source.
     */
    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String text;

    @Column(name = "token_count", nullable = false, updatable = false)
    private int tokenCount;

    /** Offsets into the parent document's {@code body}, so a citation can highlight the
     * exact supporting span rather than the whole chunk. */
    @Column(name = "char_start", nullable = false, updatable = false)
    private int charStart;

    @Column(name = "char_end", nullable = false, updatable = false)
    private int charEnd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeChunk() {
        // JPA
    }

    public KnowledgeChunk(Long documentId, int ordinal, String text, int tokenCount,
                          int charStart, int charEnd) {
        this.documentId = documentId;
        this.ordinal = ordinal;
        this.text = text;
        this.tokenCount = tokenCount;
        this.charStart = charStart;
        this.charEnd = charEnd;
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public Long getTenantId() { return tenantId; }
    public int getOrdinal() { return ordinal; }
    public String getText() { return text; }
    public int getTokenCount() { return tokenCount; }
    public int getCharStart() { return charStart; }
    public int getCharEnd() { return charEnd; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
