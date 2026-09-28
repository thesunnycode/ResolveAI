package com.resolveai.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.incidents.service.CorrelationGate.GateConfig;
import com.resolveai.incidents.service.CorrelationGate.GateDecision;
import com.resolveai.incidents.service.CorrelationGate.GateInput;
import com.resolveai.incidents.service.TicketClusterer.Cluster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every boundary from both sides — off-by-one on {@code >=} versus {@code >} is the single
 * most likely bug here, and it is invisible in a demo. Plain JUnit; {@link CorrelationGate}
 * has no Spring, no repository, no I/O.
 */
class CorrelationGateTest {

    private final CorrelationGate gate = new CorrelationGate();
    private static final Instant BASE = Instant.parse("2026-01-05T14:00:00Z");

    @ParameterizedTest(name = "cluster size {0} at 10x baseline -> propose={1}")
    @CsvSource({"4,false", "5,true", "6,true"})
    void sizeBoundary(int size, boolean expectPropose) {
        Cluster cluster = clusterOf(size, 30); // window within the 30min limit throughout
        double hourlyRate = size * 2.0; // 60 / 30min window
        double baseline = hourlyRate / 10.0; // multiple fixed at 10x regardless of size
        GateDecision decision = evaluate(cluster, baseline, GateConfig.defaults(), List.of());
        assertThat(decision.propose()).isEqualTo(expectPropose);
    }

    @ParameterizedTest(name = "{0}x baseline at size 10 -> propose={1}")
    @CsvSource({"2.9,false", "3.0,false", "3.1,true"})
    void rateBoundary(double multiple, boolean expectPropose) {
        Cluster cluster = clusterOf(10, 30);
        double hourlyRate = 20.0; // 10 tickets / 30min => 20/hour
        double baseline = hourlyRate / multiple;
        GateDecision decision = evaluate(cluster, baseline, GateConfig.defaults(), List.of());
        assertThat(decision.propose()).isEqualTo(expectPropose);
    }

    @ParameterizedTest(name = "window {0}min -> propose={1}")
    @CsvSource({"29,true", "30,true", "31,false"})
    void windowBoundary(int minutes, boolean expectPropose) {
        Cluster cluster = clusterOf(10, minutes);
        GateDecision decision = evaluate(cluster, 0.1, GateConfig.defaults(), List.of());
        assertThat(decision.propose()).isEqualTo(expectPropose);
    }

    @DisplayName("baseline of zero (new tenant, FLOOR) does not divide by zero")
    @org.junit.jupiter.api.Test
    void zeroBaselineDoesNotDivideByZero() {
        Cluster cluster = clusterOf(10, 30);
        GateDecision decision = evaluate(cluster, 0.0, GateConfig.defaults(), List.of());
        assertThat(decision.arrivalRateMultiple()).isFinite();
        assertThat(decision.propose()).isTrue();
    }

    @DisplayName("60% overlap with a live incident is rejected")
    @org.junit.jupiter.api.Test
    void overlapSixtyPercentRejects() {
        Cluster cluster = clusterOf(10, 30);
        List<Long> liveTicketIds = cluster.ticketIds().subList(0, 6);
        GateDecision decision = evaluate(cluster, 0.1, GateConfig.defaults(), liveTicketIds);
        assertThat(decision.propose()).isFalse();
    }

    @DisplayName("40% overlap with a live incident still proposes")
    @org.junit.jupiter.api.Test
    void overlapFortyPercentProposes() {
        Cluster cluster = clusterOf(10, 30);
        List<Long> liveTicketIds = cluster.ticketIds().subList(0, 4);
        GateDecision decision = evaluate(cluster, 0.1, GateConfig.defaults(), liveTicketIds);
        assertThat(decision.propose()).isTrue();
    }

    @DisplayName("monotonic in cluster size: if N proposes, N+1 with the same baseline and window also proposes")
    @org.junit.jupiter.api.Test
    void monotonicInClusterSize() {
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            int n = 1 + random.nextInt(100);
            int windowMinutes = 1 + random.nextInt(120);
            double baseline = random.nextDouble() * 20;

            GateDecision atN = evaluate(clusterOf(n, windowMinutes), baseline,
                    GateConfig.defaults(), List.of());
            GateDecision atNPlus1 = evaluate(clusterOf(n + 1, windowMinutes), baseline,
                    GateConfig.defaults(), List.of());

            if (atN.propose()) {
                assertThat(atNPlus1.propose())
                        .as("n=%d proposed but n+1=%d did not, baseline=%.3f, window=%d",
                                n, n + 1, baseline, windowMinutes)
                        .isTrue();
            }
        }
    }

    private GateDecision evaluate(Cluster cluster, double baseline, GateConfig config,
                                  List<Long> existingLiveTicketIds) {
        return gate.evaluate(new GateInput(cluster, baseline, 4, "SPECIFIC", config,
                existingLiveTicketIds));
    }

    private static Cluster clusterOf(int size, int windowMinutes) {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= size; i++) {
            ids.add(i);
        }
        return new Cluster(List.copyOf(ids), BASE, BASE.plusSeconds(windowMinutes * 60L));
    }
}
