package com.resolveai.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.incidents.service.CorrelationGate.GateConfig;
import com.resolveai.incidents.service.CorrelationGate.GateDecision;
import com.resolveai.incidents.service.CorrelationGate.GateInput;
import com.resolveai.incidents.service.TicketClusterer.CandidateTicket;
import com.resolveai.incidents.service.TicketClusterer.Cluster;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Doc 12 Task 11 — measurement, not a coding task.
 *
 * <h2>Why this is a synthetic corpus, not a live one, and what that does and does not prove</h2>
 *
 * <p>{@code TicketClusterer} and {@code CorrelationGate} are pure functions over embeddings
 * and entities already loaded — the corpus below builds hand-controlled vectors with a
 * known, deliberately-noisy similarity to their storm's own centre, rather than real
 * `text-embedding-3-small` output for real ticket text. That makes the sweep entirely free
 * (no OpenAI budget spent, no network), and it is genuinely useful: it proves the
 * *mechanism* — clustering, the gate's four conditions, the interaction between {@code tau}
 * and {@code entityBoost} — behaves correctly across a real range of similarity and noise,
 * not merely at one hand-picked point. What it does not prove is that real customer
 * language about a real outage produces embeddings this well-behaved; a live sweep against
 * embeddings from actual planted-storm ticket text (doc 12 Task 21's {@code storm.json} is
 * exactly that text) is the natural follow-up once {@code demo/storm.sh} has been run
 * against a live stack. See the README's Incident Correlation section for the same note.
 *
 * <p><b>That follow-up has been done</b> - {@link CorrelationRealEmbeddingTuningTest} - and
 * it moved the shipped {@code tau} from this sweep's 0.82 to 0.68: real varied outage
 * language is only ~0.44 cosine-similar, so 0.82 detected no real storm. This test is kept
 * as the check that the looser value still rejects all three synthetic negatives.
 *
 * <h2>The corpus</h2>
 *
 * <ul>
 *   <li><b>Positive A</b> — 38 tickets, the noisiest storm (embedding mix factor 0.90,
 *       expected pairwise cosine &asymp; 0.81), 18-minute window. The one that needs either a
 *       forgiving {@code tau} or the entity boost to hold together.
 *   <li><b>Positive B</b> — 22 tickets, mix factor 0.94 (cosine &asymp; 0.88), 12-minute window.
 *   <li><b>Positive C</b> — 12 tickets, mix factor 0.97 (cosine &asymp; 0.94), 8-minute window —
 *       the easy one.
 *   <li><b>Negative 1 — Monday morning burst</b>: 30 tickets, each an independent random
 *       direction (no shared centre at all), 8-minute window. High rate, zero similarity.
 *   <li><b>Negative 2 — slow correlated trickle</b>: 8 tickets sharing a centre as tight as
 *       Positive C (mix factor 0.97), but spread over 4 hours. High similarity, but the
 *       gate's {@code windowMinutes} condition must reject it regardless of clustering.
 *   <li><b>Negative 3 — correlated but below threshold</b>: 4 tickets sharing a centre as
 *       tight as Positive C, in 5 minutes. High similarity, high rate, but
 *       {@code minClusterSize} must reject a cluster of 4.
 * </ul>
 *
 * <p>Negatives 2 and 3 are deliberately built to cluster easily — the point is that
 * {@code CorrelationGate}'s window and size conditions, not a weak {@code tau}, are what
 * reject them. That is what makes the precision number mean something: it would stay 1.0
 * even at the loosest {@code tau} in the sweep, and the sweep is there to find the
 * <i>recall</i> trade-off on the genuinely noisy positives.
 */
class CorrelationTuningTest {

    private static final int DIMENSIONS = 32;
    private static final Instant BASE = Instant.parse("2026-02-09T09:00:00Z");
    private static final GateConfig GATE_CONFIG = GateConfig.defaults();
    /** A modest background rate, consistent with BaselineService's own FLOOR default. */
    private static final double BASELINE_COUNT = 2.0;

    private record Scenario(String name, boolean expectPropose, List<CandidateTicket> tickets) {
    }

    @Test
    @DisplayName("sweep tau and entityBoost; commit the operating point this test asserts")
    void sweepAndReport() {
        List<Scenario> corpus = buildCorpus();

        List<Double> taus = new ArrayList<>();
        for (double t = 0.70; t <= 0.92 + 1e-9; t += 0.02) {
            taus.add(round(t));
        }
        List<Double> boosts = new ArrayList<>();
        for (double b = 0.00; b <= 0.30 + 1e-9; b += 0.05) {
            boosts.add(round(b));
        }

        System.out.println();
        System.out.println("tau   entityBoost  precision  recall  falsePositives  positivesCaught");
        // results[boostIndex][tauIndex]
        Result[][] results = new Result[boosts.size()][taus.size()];
        for (int bi = 0; bi < boosts.size(); bi++) {
            for (int ti = 0; ti < taus.size(); ti++) {
                Result r = evaluate(corpus, taus.get(ti), boosts.get(bi));
                results[bi][ti] = r;
                System.out.println("%.2f  %.2f         %.3f      %.3f   %d               %d/3"
                        .formatted(taus.get(ti), boosts.get(bi), r.precision(), r.recall(),
                                r.falsePositives(), r.positivesCaught()));
            }
        }

        // Not the cliff edge — the middle of the widest run of tau where recall is
        // perfect and precision clears the floor, at each entityBoost. A point one step
        // from where recall starts collapsing (tau=0.92 with the smallest working boost,
        // in this corpus) is technically "the best recall found," but it is also exactly
        // the point a slightly noisier live corpus would push back into missing storms.
        // Preferring the plateau's centre, and the smallest entityBoost that still gets a
        // wide plateau, is the choice that survives being wrong about the corpus by a
        // little.
        int bestBoostIndex = -1;
        int bestRunStart = -1;
        int bestRunLength = -1;
        for (int bi = 0; bi < boosts.size(); bi++) {
            int runStart = -1;
            int runLength = 0;
            int i = 0;
            while (i < taus.size()) {
                Result r = results[bi][i];
                if (r.precision() >= 0.95 && r.recall() == 1.0) {
                    int start = i;
                    int len = 0;
                    while (i < taus.size() && results[bi][i].precision() >= 0.95
                            && results[bi][i].recall() == 1.0) {
                        len++;
                        i++;
                    }
                    if (len > runLength) {
                        runStart = start;
                        runLength = len;
                    }
                } else {
                    i++;
                }
            }
            if (runLength > bestRunLength) {
                bestRunLength = runLength;
                bestRunStart = runStart;
                bestBoostIndex = bi;
            }
        }

        assertThat(bestBoostIndex)
                .as("no entityBoost value produced any run of perfect recall at precision >= 0.95")
                .isNotEqualTo(-1);

        double chosenBoost = boosts.get(bestBoostIndex);
        double chosenTau = taus.get(bestRunStart + bestRunLength / 2);
        double plateauLowTau = taus.get(bestRunStart);
        double plateauHighTau = taus.get(bestRunStart + bestRunLength - 1);

        System.out.println();
        System.out.println("Widest perfect-recall plateau: entityBoost=%.2f, tau in [%.2f, %.2f]"
                .formatted(chosenBoost, plateauLowTau, plateauHighTau));
        System.out.println("Chosen operating point (plateau centre): tau=%.2f entityBoost=%.2f"
                .formatted(chosenTau, chosenBoost));

        Result chosen = evaluate(corpus, chosenTau, chosenBoost);
        assertThat(chosen.falsePositives())
                .as("zero incidents proposed for any of the three uncorrelated bursts")
                .isZero();
        assertThat(chosen.precision()).isGreaterThanOrEqualTo(0.95);
        assertThat(chosen.recall()).isEqualTo(1.0);

        // The values actually shipped in application.yml (resolveai.correlation.tau /
        // entity-boost) must themselves clear the bar on this corpus too. They are no
        // longer this sweep's plateau centre: tau was re-tuned to 0.68 on real embeddings
        // (CorrelationRealEmbeddingTuningTest), where 0.82 detected no real outage at all.
        // This synthetic corpus is still the check that the looser value does not start
        // proposing incidents for its three uncorrelated bursts.
        Result shipped = evaluate(corpus, 0.68, 0.15);
        assertThat(shipped.falsePositives())
                .as("the shipped defaults (tau=0.68, entityBoost=0.15) must not false-positive here")
                .isZero();
        assertThat(shipped.precision()).isGreaterThanOrEqualTo(0.95);
        assertThat(shipped.recall())
                .as("the shipped defaults must still catch every planted storm on this corpus")
                .isEqualTo(1.0);
    }

    // ── Evaluation ──────────────────────────────────────────────────────────

    private record Result(double precision, double recall, int falsePositives, int positivesCaught) {
    }

    private Result evaluate(List<Scenario> corpus, double tau, double entityBoost) {
        TicketClusterer clusterer = new TicketClusterer(tau, entityBoost);
        CorrelationGate gate = new CorrelationGate();

        int truePositives = 0;
        int falseNegatives = 0;
        int falsePositives = 0;

        for (Scenario scenario : corpus) {
            List<Cluster> clusters = clusterer.cluster(scenario.tickets());
            boolean anyProposed = clusters.stream()
                    .anyMatch(c -> gate.evaluate(new GateInput(c, BASELINE_COUNT, 4, "SPECIFIC",
                            GATE_CONFIG, List.of())).propose());

            if (scenario.expectPropose()) {
                if (anyProposed) {
                    truePositives++;
                } else {
                    falseNegatives++;
                }
            } else if (anyProposed) {
                falsePositives++;
            }
        }

        int positives = truePositives + falseNegatives;
        double precision = (truePositives + falsePositives) == 0 ? 1.0
                : (double) truePositives / (truePositives + falsePositives);
        double recall = positives == 0 ? 1.0 : (double) truePositives / positives;
        return new Result(precision, recall, falsePositives, truePositives);
    }

    // ── Corpus construction ────────────────────────────────────────────────

    private List<Scenario> buildCorpus() {
        List<Scenario> corpus = new ArrayList<>();
        corpus.add(new Scenario("Positive A (noisy, 38 tickets)", true,
                storm("A", 38, 0.90, BASE, 18,
                        Set.of(TicketClusterer.entityKey("SERVICE", "payment-service")))));
        corpus.add(new Scenario("Positive B (22 tickets)", true,
                storm("B", 22, 0.94, BASE, 12,
                        Set.of(TicketClusterer.entityKey("ERROR_CODE", "ERR_AUTH_TIMEOUT")))));
        corpus.add(new Scenario("Positive C (tight, 12 tickets)", true,
                storm("C", 12, 0.97, BASE, 8, Set.of())));
        corpus.add(new Scenario("Negative 1 (Monday morning burst, 30 unrelated)", false,
                unrelatedBurst(30, BASE, 8)));
        corpus.add(new Scenario("Negative 2 (slow correlated trickle, 4h window)", false,
                storm("T", 8, 0.97, BASE, 240, Set.of())));
        corpus.add(new Scenario("Negative 3 (correlated but below size, 4 tickets)", false,
                storm("S", 4, 0.97, BASE, 5, Set.of())));
        return corpus;
    }

    /**
     * {@code count} tickets whose embeddings sit on a noisy cone around one shared centre
     * — {@code mixFactor} close to 1 means tight (high pairwise cosine); further from 1
     * means noisy. Spread evenly across {@code windowMinutes} so the cluster's own window
     * is exactly what the scenario claims.
     */
    private static List<CandidateTicket> storm(String seedLabel, int count, double mixFactor,
                                               Instant windowStart, int windowMinutes,
                                               Set<String> sharedEntities) {
        Random random = new Random(seedLabel.hashCode());
        float[] centre = randomUnitVector(random);
        List<CandidateTicket> tickets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] v = onCone(centre, mixFactor, random);
            long id = (long) (seedLabel.hashCode() & 0xFFFF) * 1000 + i;
            Instant createdAt = count <= 1 ? windowStart
                    : windowStart.plusSeconds((long) i * windowMinutes * 60L / (count - 1));
            tickets.add(new CandidateTicket(id, v, sharedEntities, createdAt));
        }
        return tickets;
    }

    /** No shared centre at all — every ticket points in its own random direction. */
    private static List<CandidateTicket> unrelatedBurst(int count, Instant windowStart,
                                                         int windowMinutes) {
        Random random = new Random("unrelated".hashCode());
        List<CandidateTicket> tickets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] v = randomUnitVector(random);
            Instant createdAt = windowStart.plusSeconds((long) i * windowMinutes * 60L / count);
            tickets.add(new CandidateTicket(90_000L + i, v, Set.of(), createdAt));
        }
        return tickets;
    }

    private static float[] onCone(float[] centre, double mixFactor, Random random) {
        float[] noise = randomUnitVector(random);
        double noiseWeight = Math.sqrt(Math.max(0, 1 - mixFactor * mixFactor));
        float[] v = new float[centre.length];
        for (int d = 0; d < v.length; d++) {
            v[d] = (float) (centre[d] * mixFactor + noise[d] * noiseWeight);
        }
        return normalise(v);
    }

    private static float[] randomUnitVector(Random random) {
        float[] v = new float[DIMENSIONS];
        for (int d = 0; d < v.length; d++) {
            v[d] = (float) random.nextGaussian();
        }
        return normalise(v);
    }

    private static float[] normalise(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int d = 0; d < v.length; d++) {
            out[d] = (float) (v[d] / norm);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
