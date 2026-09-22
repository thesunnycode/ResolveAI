package com.resolveai.knowledge.repository;

import com.resolveai.platform.ai.model.EmbeddingService;
import com.resolveai.platform.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The retrieval centrepiece: one SQL statement, two ranking CTEs, fused by Reciprocal
 * Rank Fusion.
 *
 * <h2>Why RRF and not normalise-and-add</h2>
 *
 * <p>{@code ts_rank_cd} returns roughly 0–1 with a distribution that depends on document
 * length; pgvector's cosine distance returns 0–2 with a completely different shape.
 * <b>They are not comparable</b>, and any normalisation invented to make them so is a
 * hyperparameter nobody can justify — there is no principled answer to "how much of a
 * lexical point is one vector point worth?" RRF sidesteps the question entirely: it
 * discards the scores and fuses on <i>rank</i>, which is unit-free by construction. The
 * damping constant (60) is the standard term from the original paper and is not tuned.
 *
 * <h2>Tenant pre-filtering — the single most likely silent bug in retrieval</h2>
 *
 * <p><b>{@code tenant_id = :tenantId} is inside each CTE's {@code WHERE}, before the
 * {@code LIMIT 50}.</b> Filtering after the top-k would silently shrink the effective k:
 * if thirty of the top fifty vector hits belong to another tenant, filtering them out
 * afterwards leaves twenty, and recall drops with no error and no visible symptom short
 * of an eval suite catching it. Pre-filtering inside the CTE means the candidate pool is
 * already tenant-scoped before ranking begins, so the full fifty are always this
 * tenant's.
 *
 * <h2>Source-tier weighting</h2>
 *
 * <p>Applied to the fused RRF score, not to either half separately — a runbook that
 * ranks 2nd lexically and 4th semantically should out-rank a resolved ticket that ranks
 * 1st on one axis and nowhere on the other, and multiplying after fusion is what makes
 * that comparison apply uniformly regardless of which signal found the result.
 */
@Repository
public class HybridSearchRepository {

    /** RUNBOOK and ARTICLE. Somebody wrote them deliberately to be read. */
    private static final double AUTHORITATIVE_BOOST = 1.15;

    /** RESOLVED_TICKET. Evidence a fix worked once, not documented policy. */
    private static final double PRECEDENT_BOOST = 1.0;

    /** How many candidates each ranking method contributes before fusion. Tuned in Task 11. */
    private static final int CANDIDATE_DEPTH = 50;

    /** The RRF damping constant from the original paper. Not a tuning knob. */
    private static final int RRF_K = 60;

    private final NamedParameterJdbcTemplate jdbc;

    public HybridSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One fused result row, with every intermediate score preserved for {@code explain}. */
    public record HybridResult(
            Long chunkId, Long documentId, String documentTitle, String source,
            String text, int charStart, int charEnd,
            Integer lexicalRank, Double lexicalScore,
            Integer vectorRank, Double vectorScore,
            double rrfScore, double tierBoost, double finalScore) {
    }

    /**
     * @param query        raw text. Passed as a bind parameter to {@code plainto_tsquery},
     *                     never concatenated — the search box is a public endpoint and
     *                     {@code tsquery} has an operator grammar ({@code |}, {@code &},
     *                     {@code !}) that string-building would hand straight to an
     *                     attacker.
     * @param queryVector  the embedding of {@code query}, computed by the caller so this
     *                     class stays free of any dependency on {@link EmbeddingService}
     *                     and therefore free of any budget or policy decision — a search
     *                     query embedding is charged and gated exactly like any other
     *                     embedding, by the same one code path.
     */
    public List<HybridResult> search(String query, float[] queryVector, int k,
                                      String sourceFilter) {
        Long tenantId = TenantContext.getRequired();
        String vectorLiteral = EmbeddingService.toVectorLiteral(queryVector);

        String sourceClause = sourceFilter == null ? "" : "AND d.source = :sourceFilter";

        String sql = """
                WITH lex AS (
                    SELECT c.id,
                           ROW_NUMBER() OVER (
                               ORDER BY ts_rank_cd(c.text_tsv, plainto_tsquery('english', :query))
                                   DESC) AS rnk,
                           ts_rank_cd(c.text_tsv, plainto_tsquery('english', :query)) AS score
                      FROM knowledge_chunk c
                      JOIN knowledge_document d ON d.id = c.document_id
                     WHERE c.tenant_id = :tenantId
                       AND c.text_tsv @@ plainto_tsquery('english', :query)
                       %s
                     ORDER BY score DESC
                     LIMIT :depth
                ),
                vec AS (
                    SELECT c.id, ROW_NUMBER() OVER (ORDER BY c.embedding <=> CAST(:queryVec AS vector)) AS rnk,
                           1 - (c.embedding <=> CAST(:queryVec AS vector)) AS score
                      FROM knowledge_chunk c
                      JOIN knowledge_document d ON d.id = c.document_id
                     WHERE c.tenant_id = :tenantId
                       %s
                     ORDER BY c.embedding <=> CAST(:queryVec AS vector)
                     LIMIT :depth
                )
                SELECT c.id AS chunk_id, c.document_id, d.title AS document_title,
                       d.source, c.text, c.char_start, c.char_end,
                       lex.rnk AS lexical_rank, lex.score AS lexical_score,
                       vec.rnk AS vector_rank, vec.score AS vector_score,
                       COALESCE(1.0 / (:rrfK + lex.rnk), 0)
                         + COALESCE(1.0 / (:rrfK + vec.rnk), 0) AS rrf_score
                  FROM knowledge_chunk c
                  JOIN knowledge_document d ON d.id = c.document_id
                  LEFT JOIN lex ON lex.id = c.id
                  LEFT JOIN vec ON vec.id = c.id
                 WHERE (lex.id IS NOT NULL OR vec.id IS NOT NULL)
                 ORDER BY rrf_score DESC
                 LIMIT :k
                """.formatted(sourceClause, sourceClause);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("queryVec", vectorLiteral)
                .addValue("tenantId", tenantId)
                .addValue("depth", CANDIDATE_DEPTH)
                .addValue("rrfK", RRF_K)
                .addValue("k", k);
        if (sourceFilter != null) {
            params.addValue("sourceFilter", sourceFilter);
        }

        return jdbc.query(sql, params, (rs, rowNum) -> {
            String source = rs.getString("source");
            double tierBoost = "RESOLVED_TICKET".equals(source)
                    ? PRECEDENT_BOOST : AUTHORITATIVE_BOOST;
            double rrf = rs.getDouble("rrf_score");
            // ROW_NUMBER() is bigint in Postgres, not int -- cast through Number rather
            // than assuming Integer, or a JDBC driver that returns Long here (as this
            // one does) throws a ClassCastException on every single search.
            Long lexRankRaw = (Long) rs.getObject("lexical_rank");
            Long vecRankRaw = (Long) rs.getObject("vector_rank");
            Integer lexRank = lexRankRaw == null ? null : lexRankRaw.intValue();
            Integer vecRank = vecRankRaw == null ? null : vecRankRaw.intValue();
            return new HybridResult(
                    rs.getLong("chunk_id"), rs.getLong("document_id"),
                    rs.getString("document_title"), source, rs.getString("text"),
                    rs.getInt("char_start"), rs.getInt("char_end"),
                    lexRank, lexRank == null ? null : rs.getDouble("lexical_score"),
                    vecRank, vecRank == null ? null : rs.getDouble("vector_score"),
                    rrf, tierBoost, rrf * tierBoost);
        });
    }
}
