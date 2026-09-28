package com.resolveai.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.incidents.service.TicketClusterer.CandidateTicket;
import com.resolveai.incidents.service.TicketClusterer.Cluster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plain JUnit — {@link TicketClusterer} is a pure function over pre-loaded candidates. */
class TicketClustererTest {

    private static final Instant BASE = Instant.parse("2026-01-05T14:00:00Z");

    @Test
    @DisplayName("three tight clusters plus five outliers produces exactly three clusters, outliers unclustered")
    void threeClustersPlusOutliers() {
        TicketClusterer clusterer = new TicketClusterer(0.85, 0.0);
        List<CandidateTicket> tickets = new ArrayList<>();

        // Eight axes in a shared space: 0-2 are the three group directions, 3-7 are one
        // per outlier. Every axis is orthogonal to every other, so any two tickets on
        // different axes have cosine 0 regardless of the noise added within a group.
        addGroup(tickets, "A", axis(8, 0), 6);
        addGroup(tickets, "B", axis(8, 1), 8);
        addGroup(tickets, "C", axis(8, 2), 5);

        // Five outliers, each on its own orthogonal axis - no two alike, and none aligned
        // with a group.
        long outlierId = 9000;
        for (int i = 0; i < 5; i++) {
            tickets.add(new CandidateTicket(outlierId++, axis(8, 3 + i), Set.of(), BASE));
        }

        List<Cluster> clusters = clusterer.cluster(tickets);

        assertThat(clusters).hasSize(3);
        assertThat(clusters).extracting(Cluster::size).containsExactly(8, 6, 5); // sorted, largest first

        List<Long> clustered = clusters.stream().flatMap(c -> c.ticketIds().stream()).toList();
        assertThat(clustered).hasSize(19);
        assertThat(clustered).doesNotContain(9000L, 9001L, 9002L, 9003L, 9004L);
    }

    @Test
    @DisplayName("runs in under 100ms for N=200")
    void performanceAtTwoHundred() {
        TicketClusterer clusterer = new TicketClusterer(0.85, 0.1);
        Random random = new Random(7);
        List<CandidateTicket> tickets = new ArrayList<>();
        for (long i = 0; i < 200; i++) {
            float[] v = new float[16];
            for (int d = 0; d < v.length; d++) {
                v[d] = random.nextFloat();
            }
            tickets.add(new CandidateTicket(i, v, Set.of(), BASE));
        }

        long start = System.nanoTime();
        clusterer.cluster(tickets);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(100);
    }

    @Test
    @DisplayName("shared entities alone can pull two low-cosine tickets together")
    void entityBoostMatters() {
        // Orthogonal embeddings (cosine 0) but identical entity sets: with a high enough
        // boost the pair clusters; with boost 0 it does not.
        Set<String> sharedEntities = Set.of("SERVICE:payment-service", "ERROR_CODE:ERR_PAY_TIMEOUT");
        CandidateTicket a = new CandidateTicket(1L, new float[] {1f, 0f}, sharedEntities, BASE);
        CandidateTicket b = new CandidateTicket(2L, new float[] {0f, 1f}, sharedEntities, BASE);

        assertThat(new TicketClusterer(0.85, 0.0).cluster(List.of(a, b))).isEmpty();
        assertThat(new TicketClusterer(0.85, 1.0).cluster(List.of(a, b))).hasSize(1);
    }

    private static float[] axis(int dimensions, int index) {
        float[] v = new float[dimensions];
        v[index] = 1f;
        return v;
    }

    private void addGroup(List<CandidateTicket> tickets, String label, float[] direction,
                          int count) {
        Random random = new Random(label.hashCode());
        for (int i = 0; i < count; i++) {
            float[] v = direction.clone();
            for (int d = 0; d < v.length; d++) {
                if (v[d] == 0f) {
                    v[d] = (random.nextFloat() - 0.5f) * 0.01f;
                } else {
                    v[d] += (random.nextFloat() - 0.5f) * 0.01f;
                }
            }
            long id = (label.charAt(0) - 'A' + 1) * 1000L + i;
            tickets.add(new CandidateTicket(id, v, Set.of(), BASE.plusSeconds(i * 30L)));
        }
    }
}
