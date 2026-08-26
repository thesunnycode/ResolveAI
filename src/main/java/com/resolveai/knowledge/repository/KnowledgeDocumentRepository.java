package com.resolveai.knowledge.repository;

import com.resolveai.knowledge.domain.KnowledgeDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    /**
     * Not-yet-indexed or since-edited documents. Backs {@code idx_kb_pending}, a partial
     * index on {@code WHERE indexed_at IS NULL AND deleted_at IS NULL}: cheap because it
     * only ever holds the handful of documents actually waiting, never the whole corpus.
     */
    @Query("SELECT d FROM KnowledgeDocument d WHERE d.indexedAt IS NULL")
    List<KnowledgeDocument> findPendingIndexing();

    Optional<KnowledgeDocument> findByContentSha256(String contentSha256);

    Page<KnowledgeDocument> findAll(Pageable pageable);

    @Query("SELECT d FROM KnowledgeDocument d WHERE d.source = :source")
    Page<KnowledgeDocument> findBySource(@Param("source") String source, Pageable pageable);

    /** Every currently-indexed document, for a full or content-changed reindex sweep. */
    @Query("SELECT d FROM KnowledgeDocument d WHERE d.indexedAt IS NOT NULL")
    List<KnowledgeDocument> findIndexed();
}
