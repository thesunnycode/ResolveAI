package com.resolveai.knowledge.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recall@5, Precision@5, MRR and nDCG@10 — the retrieval suite's scoring, as a pure
 * function over ranked results.
 *
 * <h2>Why four metrics and not one</h2>
 *
 * <p>Each answers a different question, and a single number hides which one is failing:
 *
 * <ul>
 *   <li><b>Recall@5</b> — did the right answer appear in the top 5 at all? The number an
 *       agent cares about: if it is not in the first page of results, it might as well
 *       not exist.
 *   <li><b>Precision@5</b> — of the top 5, how many were actually relevant? Low precision
 *       with high recall means the right answer is buried among noise.
 *   <li><b>MRR</b> (mean reciprocal rank) — how far down the list, on average, is the
 *       <i>first</i> relevant result? Rewards ranking the best answer first, not merely
 *       including it somewhere in the top 5.
 *   <li><b>nDCG@10</b> — rewards relevant results ranked higher over the same results
 *       ranked lower, across a longer window than the other three. The metric most
 *       sensitive to ranking quality rather than mere presence.
 * </ul>
 *
 * <h2>Reported per group, not only in aggregate — this is the point of Task 10</h2>
 *
 * <p>The aggregate hides the finding. Computed separately for paraphrase queries,
 * identifier queries and article-derived queries, the per-group numbers are expected to
 * show dense retrieval winning on paraphrase and losing on exact identifiers — which is
 * the entire argument for hybrid search, made as a measured gap rather than an assertion.
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * @param group          which of the three eval groups this case belongs to —
     *                       {@code PARAPHRASE}, {@code IDENTIFIER} or {@code ARTICLE}
     * @param relevantChunks the gold set for this query — every chunk id belonging to a
     *                       document the case's author judged relevant
     * @param rankedChunks   what the system under test returned, best first
     */
    public record CaseResult(String caseName, String group, Set<Long> relevantChunks,
                             List<Long> rankedChunks) {

        boolean isRelevant(long chunkId) {
            return relevantChunks.contains(chunkId);
        }
    }

    /** One group's (or the whole suite's) aggregate scores. */
    public record GroupScores(int caseCount, double recallAt5, double precisionAt5,
                              double mrr, double ndcgAt10) {
    }

    /** Every group plus the overall aggregate, keyed by group name and {@code "OVERALL"}. */
    public static Map<String, GroupScores> score(List<CaseResult> results) {
        Map<String, GroupScores> byGroup = new LinkedHashMap<>();
        for (String group : List.of("PARAPHRASE", "IDENTIFIER", "ARTICLE")) {
            List<CaseResult> inGroup = results.stream().filter(r -> r.group().equals(group))
                    .toList();
            if (!inGroup.isEmpty()) {
                byGroup.put(group, scoreGroup(inGroup));
            }
        }
        byGroup.put("OVERALL", scoreGroup(results));
        return byGroup;
    }

    private static GroupScores scoreGroup(List<CaseResult> results) {
        double recallSum = 0;
        double precisionSum = 0;
        double rrSum = 0;
        double ndcgSum = 0;

        for (CaseResult r : results) {
            List<Long> top5 = r.rankedChunks().stream().limit(5).toList();
            List<Long> top10 = r.rankedChunks().stream().limit(10).toList();

            long relevantInTop5 = top5.stream().filter(r::isRelevant).count();
            recallSum += recallAt5(r, top5);
            precisionSum += top5.isEmpty() ? 0 : (double) relevantInTop5 / top5.size();

            rrSum += reciprocalRank(r);
            ndcgSum += ndcg(r, top10);
        }

        int n = results.size();
        return new GroupScores(n,
                round(safeDivide(recallSum, n)),
                round(safeDivide(precisionSum, n)),
                round(safeDivide(rrSum, n)),
                round(safeDivide(ndcgSum, n)));
    }

    /** 1 if any relevant chunk appears in the top 5, else 0 — "did we find it at all". */
    private static double recallAt5(CaseResult r, List<Long> top5) {
        return top5.stream().anyMatch(r::isRelevant) ? 1.0 : 0.0;
    }

    private static double reciprocalRank(CaseResult r) {
        List<Long> ranked = r.rankedChunks();
        for (int i = 0; i < ranked.size(); i++) {
            if (r.isRelevant(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Binary relevance nDCG: DCG with gain 1 for a relevant hit, normalised by ideal DCG. */
    private static double ndcg(CaseResult r, List<Long> top10) {
        double dcg = 0;
        for (int i = 0; i < top10.size(); i++) {
            if (r.isRelevant(top10.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealHits = Math.min(r.relevantChunks().size(), top10.size());
        double idealDcg = 0;
        for (int i = 0; i < idealHits; i++) {
            idealDcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idealDcg == 0 ? 0 : dcg / idealDcg;
    }

    private static double safeDivide(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
