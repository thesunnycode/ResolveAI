package com.resolveai.knowledge.repository;

import com.resolveai.platform.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a chunk, vector and all. Raw JDBC, not the JPA repository.
 *
 * <h2>Why this has to exist alongside {@link KnowledgeChunkRepository}</h2>
 *
 * <p>{@code knowledge_chunk.embedding} is {@code vector(768) NOT NULL}, and
 * {@code KnowledgeChunk} deliberately leaves it unmapped — see that class's comment for
 * why. An unmapped column is simply absent from Hibernate's generated {@code INSERT},
 * which is harmless for {@code ticket.embedding} (nullable, filled in later) and fatal
 * here: the row would violate {@code NOT NULL} the instant it was saved. So the insert
 * that actually creates the row has to supply the vector, which means it cannot go
 * through the entity at all. Every subsequent <i>read</i> — the chunk detail view, the
 * count on a document — still goes through {@link KnowledgeChunkRepository} exactly as
 * for any other entity; only the write of a brand-new chunk is different, and only
 * because of the one column that cannot be.
 *
 * <p>{@code text_tsv} needs no equivalent here: {@code trg_chunk_tsv} derives it from
 * {@code text} on every INSERT, so leaving it out of this statement is not a gap, it is
 * the correct amount of work.
 */
@Component
public class KnowledgeChunkWriter {

    private final JdbcTemplate jdbc;

    public KnowledgeChunkWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param embeddingVector pgvector's own literal form, {@code [0.1,0.2,...]} — see
     *                        {@code EmbeddingService.toVectorLiteral}, the one place
     *                        that format is produced, so this and the ticket path can
     *                        never quietly diverge on it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long insert(Long documentId, int ordinal, String text, int tokenCount,
                       int charStart, int charEnd, String embeddingVector,
                       String embeddingModelId) {
        return jdbc.queryForObject("""
                INSERT INTO knowledge_chunk
                    (document_id, tenant_id, ordinal, text, embedding, token_count,
                     char_start, char_end, embedding_model_id)
                VALUES (?, ?, ?, ?, ?::vector, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, documentId, TenantContext.getRequired(), ordinal, text,
                embeddingVector, tokenCount, charStart, charEnd, embeddingModelId);
    }
}
