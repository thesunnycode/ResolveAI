package com.resolveai.knowledge.web.dto;

import java.util.List;

/**
 * The hybrid search response — a ranked list when {@code explain=false}, and the full
 * per-source score breakdown when {@code explain=true}.
 *
 * @param kbVersion part of the retrieval cache key. Included in the response so a client
 *                  debugging a stale-looking result can see exactly which KB version it
 *                  was searched against.
 * @param strategy  always {@code HYBRID_RRF} today. Present so a future alternative
 *                  strategy (lexical-only, vector-only — see the tuning comparison in
 *                  Task 11) can be requested and distinguished without a response shape
 *                  change.
 */
public record SearchResponse(
        String query,
        long kbVersion,
        String strategy,
        Timings timings,
        List<ResultView> results,
        boolean cacheHit) {

    /**
     * @param embeddingMs the query's own embedding call — network time, not database time
     * @param queryMs     the single hybrid SQL statement: both CTEs and the RRF fusion,
     *                    run as one round trip. Not split into per-CTE figures, because
     *                    Task 7's design runs both scans and the fusion in one statement
     *                    deliberately (see {@code HybridSearchRepository}), and reporting
     *                    a lexical/vector split here would mean parsing
     *                    {@code EXPLAIN ANALYZE} output on every search just to populate
     *                    a field — real cost for a number nobody but this endpoint's own
     *                    designer would read.
     */
    public record Timings(long embeddingMs, long queryMs, long totalMs) {
    }

    /**
     * @param scores null unless {@code explain=true}. Absent rather than a set of nulls,
     *               so a client not asking for the breakdown does not pay to receive one
     *               full of nothing.
     */
    public record ResultView(
            Long chunkId, Long documentId, String documentTitle, String source, String tier,
            String text, int charStart, int charEnd, Scores scores) {
    }

    /** Every intermediate number behind {@code finalScore} — the debugging payoff. */
    public record Scores(
            Integer lexicalRank, Double lexicalScore,
            Integer vectorRank, Double vectorScore,
            double rrfScore, double tierBoost, double finalScore) {
    }

    public SearchResponse withCacheHit(boolean hit) {
        return new SearchResponse(query, kbVersion, strategy, timings, results, hit);
    }
}
