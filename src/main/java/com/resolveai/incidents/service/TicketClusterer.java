package com.resolveai.incidents.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups a window of candidate tickets into clusters of mutually-similar ones.
 *
 * <p><b>No Spring, no repository, no I/O.</b> A pure function over tickets already loaded
 * with their embeddings and entities, which is what makes it possible to hand-craft
 * embedding vectors in a unit test and to sweep {@code tau}/{@code entityBoost} across a
 * tuning set (doc 12 Task 11) without touching a database.
 *
 * <h2>Leader clustering, not single-linkage</h2>
 *
 * <pre>
 * 1. Compute the pairwise similarity matrix, O(N^2) in memory
 *    sim(a,b) = cosine(a.embedding, b.embedding) + entityBoost * jaccard(a.entities, b.entities)
 * 2. Pick the ticket with the most neighbours above tau as the seed
 * 3. Gather every ticket within tau of the seed into a cluster
 * 4. Remove them; repeat from 2 until no seed has &gt;= 2 neighbours
 * 5. Return clusters sorted by size, largest first
 * </pre>
 *
 * <p><b>Why leader clustering and not single-linkage:</b> single-linkage chains — if A~B
 * and B~C but A is not~C, all three merge, and with 200 tickets in a window that reliably
 * produces one giant cluster containing three unrelated outages. Leader clustering keeps
 * every member within {@code tau} of a single seed, which is both more controllable and
 * easier to explain to the team lead deciding whether to confirm it.
 *
 * <p><b>Why O(N^2) in memory and not a pgvector query per ticket:</b> N is 20-200 in a
 * 30-minute window. 200^2 = 40,000 cosine comparisons over 768 dimensions is roughly 30 ms
 * in Java. Two hundred round trips to Postgres would be slower and far harder to test. The
 * vector index is for search over tens of thousands of chunks, not for an all-pairs
 * comparison over a couple hundred rows — the same reasoning as Phase 7's hybrid retrieval,
 * applied in the opposite direction.
 */
public class TicketClusterer {

    private final double tau;
    private final double entityBoost;

    public TicketClusterer(double tau, double entityBoost) {
        this.tau = tau;
        this.entityBoost = entityBoost;
    }

    /**
     * @param ticketId  the ticket this row describes
     * @param embedding 768-dimensional, from {@code ticket.embedding}
     * @param entities  {@code "TYPE:value"} keys — see {@code TicketClusterer.entityKey}
     * @param createdAt when the ticket was filed, for the cluster's window
     */
    public record CandidateTicket(Long ticketId, float[] embedding, Set<String> entities,
                                  Instant createdAt) {
    }

    public record Cluster(List<Long> ticketIds, Instant windowStart, Instant windowEnd) {
        public int size() {
            return ticketIds.size();
        }
    }

    public static String entityKey(String type, String value) {
        return type + ":" + value;
    }

    public List<Cluster> cluster(List<CandidateTicket> tickets) {
        int n = tickets.size();
        if (n == 0) {
            return List.of();
        }

        // sim[i][j] for i < j; read both directions via a small accessor below.
        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double s = similarity(tickets.get(i), tickets.get(j));
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        Set<Integer> removed = new HashSet<>();

        while (true) {
            int seed = -1;
            int bestNeighbourCount = -1;
            for (int i = 0; i < n; i++) {
                if (removed.contains(i)) {
                    continue;
                }
                int count = 0;
                for (int j = 0; j < n; j++) {
                    if (i != j && !removed.contains(j) && sim[i][j] >= tau) {
                        count++;
                    }
                }
                if (count > bestNeighbourCount) {
                    bestNeighbourCount = count;
                    seed = i;
                }
            }

            if (seed == -1 || bestNeighbourCount < 1) {
                break;
            }

            List<Integer> members = new ArrayList<>();
            members.add(seed);
            for (int j = 0; j < n; j++) {
                if (j != seed && !removed.contains(j) && sim[seed][j] >= tau) {
                    members.add(j);
                }
            }

            // A cluster of one (the seed with no qualifying neighbour) is not a cluster;
            // stop rather than looping forever removing singletons.
            if (members.size() < 2) {
                break;
            }

            members.forEach(removed::add);
            clusters.add(toCluster(tickets, members));
        }

        clusters.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return clusters;
    }

    private Cluster toCluster(List<CandidateTicket> tickets, List<Integer> memberIndexes) {
        List<Long> ids = new ArrayList<>();
        Instant earliest = null;
        Instant latest = null;
        for (int idx : memberIndexes) {
            CandidateTicket t = tickets.get(idx);
            ids.add(t.ticketId());
            if (earliest == null || t.createdAt().isBefore(earliest)) {
                earliest = t.createdAt();
            }
            if (latest == null || t.createdAt().isAfter(latest)) {
                latest = t.createdAt();
            }
        }
        return new Cluster(List.copyOf(ids), earliest, latest);
    }

    private double similarity(CandidateTicket a, CandidateTicket b) {
        double cosine = cosineSimilarity(a.embedding(), b.embedding());
        double jaccard = jaccard(a.entities(), b.entities());
        return cosine + entityBoost * jaccard;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        long intersection = a.stream().filter(b::contains).count();
        return union.isEmpty() ? 0.0 : (double) intersection / union.size();
    }
}
