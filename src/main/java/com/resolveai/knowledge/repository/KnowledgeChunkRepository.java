package com.resolveai.knowledge.repository;

import com.resolveai.knowledge.domain.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByDocumentIdOrderByOrdinalAsc(Long documentId);

    /**
     * Hard-delete every chunk of a document, ahead of re-chunking or a soft delete.
     *
     * <p><b>Delete-then-insert, never a diff.</b> Chunk boundaries shift the moment the
     * source text changes — a heading added near the top renumbers and re-splits
     * everything after it — so there is no stable identity between an old chunk and a
     * new one for a diff to line up. Both statements run inside the same transaction as
     * the inserts that replace them, so a reader never observes a document with no
     * chunks at all.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM KnowledgeChunk c WHERE c.documentId = :documentId")
    int deleteChunksByDocumentId(@Param("documentId") Long documentId);

    long countByDocumentId(Long documentId);
}
