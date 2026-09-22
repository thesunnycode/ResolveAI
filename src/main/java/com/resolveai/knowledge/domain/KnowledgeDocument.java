package com.resolveai.knowledge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One piece of source material for retrieval and drafting: a runbook, a customer-facing
 * article, or an automatically indexed resolved ticket.
 *
 * <h2>Soft-deleted, and the reason is a foreign key</h2>
 *
 * <p>{@code draft_claim_citation.chunk_id} is {@code ON DELETE RESTRICT}. A chunk that a
 * past draft cited must go on existing, or {@code GET /drafts/{id}} would render a
 * citation pointing at nothing — the exact failure mode the citation mechanism exists to
 * prevent. So the document survives ({@code @SQLDelete} sets {@code deleted_at}) while
 * its chunks are hard-deleted from the retrieval index: the document stays as the
 * citation's anchor, but nothing about it is findable or servable any more. See
 * {@code KnowledgeDocumentService.delete} for where that split happens.
 *
 * <h2>{@code kbVersion} is a cache-busting counter, not a document version</h2>
 *
 * <p>Bumped on any change to this document — reindex, edit, delete — and carried in
 * every retrieval cache key. A KB edit therefore makes every previously cached search
 * result unreachable with no explicit eviction: the old cache entries are simply never
 * looked up again. It is tenant-tracked at the document level rather than globally so
 * that editing tenant A's KB does not invalidate tenant B's cache for no reason — see
 * {@code KnowledgeSearchService} for how the tenant-wide figure is derived.
 */
@Entity
@Table(name = "knowledge_document")
@SQLDelete(sql = "UPDATE knowledge_document SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentSource source;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(length = 500)
    private String uri;

    /**
     * SHA-256 of {@code body}. Compared on reindex to skip documents whose content has
     * not changed — see {@code KnowledgeDocumentService.reindex}.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.CHAR)}: the column is {@code CHAR(64)}, not
     * {@code VARCHAR}. Same trap {@code RefreshToken.tokenHash} and
     * {@code Attachment.contentSha256} hit in Phases 4 and 5 — without it Hibernate
     * binds {@code varchar} and {@code ddl-auto: validate} refuses to start.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "kb_version", nullable = false)
    private long kbVersion = 1;

    /** Set only for {@code RESOLVED_TICKET} documents. */
    @Column(name = "source_ticket_id")
    private Long sourceTicketId;

    /** Null until the {@code IndexWorker} has chunked and embedded this document. */
    @Column(name = "indexed_at")
    private OffsetDateTime indexedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected KnowledgeDocument() {
        // JPA
    }

    public KnowledgeDocument(DocumentSource source, String title, String body, String uri,
                             String contentSha256, Long sourceTicketId) {
        this.source = source;
        this.title = title;
        this.body = body;
        this.uri = uri;
        this.contentSha256 = contentSha256;
        this.sourceTicketId = sourceTicketId;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public DocumentSource getSource() { return source; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getUri() { return uri; }
    public String getContentSha256() { return contentSha256; }
    public long getKbVersion() { return kbVersion; }
    public Long getSourceTicketId() { return sourceTicketId; }
    public OffsetDateTime getIndexedAt() { return indexedAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setTitle(String title) { this.title = title; }

    public void setBody(String body, String contentSha256) {
        this.body = body;
        this.contentSha256 = contentSha256;
    }

    public void markIndexed(OffsetDateTime at) {
        this.indexedAt = at;
        this.kbVersion++;
    }

    /** Content changed since it was last indexed, or never indexed at all. */
    public boolean needsIndexing(String currentSha256) {
        return indexedAt == null || !this.contentSha256.equals(currentSha256);
    }

    public boolean isIndexed() {
        return indexedAt != null;
    }
}
