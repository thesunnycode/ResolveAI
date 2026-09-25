package com.resolveai.incidents.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.incidents.service.TicketClusterer.CandidateTicket;
import com.resolveai.incidents.service.TicketClusterer.Cluster;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Doc 12 Task 11, done against <b>real</b> embeddings - the follow-up that
 * {@link CorrelationTuningTest}'s class comment and the README both named.
 *
 * <h2>The corpus</h2>
 *
 * <p>{@code correlation/real-embeddings.json}: the live triage pipeline's own
 * {@code text-embedding-3-small} vectors (768-d, redacted text, exactly what the sweep reads)
 * and extracted entities for
 * <ul>
 *   <li>{@code storm} - the 38 tickets of {@code demo/storm.json}: one payment outage
 *       described 38 different ways;</li>
 *   <li>{@code no_storm} - the 30 unrelated tickets of {@code demo/no-storm.json}: the
 *       "Monday-morning burst" negative control;</li>
 *   <li>{@code starter} - the 200 tickets of {@code seed/generated/tickets-starter.json},
 *       the realistic background, including 49 unrelated payment tickets.</li>
 * </ul>
 *
 * <h2>What it found, and why tau moved from 0.82 to 0.68</h2>
 *
 * <p>Real language is far less tidy than the synthetic cones: the storm's own tickets are
 * only 0.44 cosine-similar on median (max 0.70), while two <i>unrelated</i> starter tickets
 * can reach 0.86. At the synthetic plateau's 0.82 the storm never formed a cluster of five,
 * so no real outage phrased like this would ever have been detected - which is what the
 * live {@code storm.sh} run showed. There is no global threshold that separates the storm
 * from <i>everything</i> (a tau low enough to hold all 38 together merges hundreds of
 * unrelated tickets), which is why the sweep scores the question the gate actually asks,
 * inside one 30-minute window:
 * <ul>
 *   <li><b>Detection</b> - storm plus 10 random background tickets: does a cluster of at
 *       least {@code minClusterSize} storm tickets form?</li>
 *   <li><b>False alarm</b> - the 30-ticket unrelated burst plus 10 background tickets:
 *       does <i>any</i> cluster of five form? (The burst also passes the rate gate, so a
 *       cluster here would be a real false incident.)</li>
 * </ul>
 *
 * <p>With entityBoost 0.15 the real plateau (detection 100%, false alarms under 1%) is
 * tau 0.66-0.72, collapsing to zero detection from 0.74. 0.68 is its robust point: a median
 * of seven storm tickets link against a minimum of five, where 0.70-0.72 sit exactly on it.
 *
 * <p><b>What this does not prove.</b> One outage, one embedding model, one background
 * corpus that is itself synthetic. The incident links the tight core of the storm (about
 * seven tickets), not all 38; the rest stay individual tickets for an agent to link. Change
 * the embedding model and this test is the one to re-run.
 */
class CorrelationRealEmbeddingTuningTest {

    /** The values shipped in application.yml (resolveai.correlation.*). */
    private static final double SHIPPED_TAU = 0.68;
    private static final double SHIPPED_BOOST = 0.15;
    private static final int MIN_CLUSTER_SIZE = 5;
    private static final int DRAWS = 200;
    private static final int BACKGROUND = 10;

    private record Labelled(String label, CandidateTicket ticket) {
    }

    private static List<Labelled> corpus;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = CorrelationRealEmbeddingTuningTest.class
                .getResourceAsStream("/correlation/real-embeddings.json")) {
            Map<String, Object> root = new ObjectMapper().readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) root.get("tickets");
            List<Labelled> loaded = new ArrayList<>();
            Instant t0 = Instant.parse("2026-09-25T12:00:00Z");
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                @SuppressWarnings("unchecked")
                List<String> entities = (List<String>) row.get("entities");
                // All inside one window: the gate's window check is not what is under test.
                loaded.add(new Labelled((String) row.get("label"), new CandidateTicket(
                        (long) i, decodeFloat16((String) row.get("f16")),
                        new HashSet<>(entities), t0.plusSeconds(i % 600))));
            }
            corpus = List.copyOf(loaded);
        }
    }

    @Test
    @DisplayName("the corpus is what the fixture says it is")
    void corpusShape() {
        assertThat(ofLabel("storm")).hasSize(38);
        assertThat(ofLabel("no_storm")).hasSize(30);
        assertThat(ofLabel("starter")).hasSize(200);
        assertThat(corpus.getFirst().ticket().embedding()).hasSize(768);
    }

    @Test
    @DisplayName("shipped tau/entityBoost detect the real varied storm in every window")
    void shippedValuesDetectTheStorm() {
        Rates r = rates(SHIPPED_TAU, SHIPPED_BOOST, 42);
        System.out.printf("tau=%.2f boost=%.2f: detection %.3f, false alarm %.3f, median linked %d%n",
                SHIPPED_TAU, SHIPPED_BOOST, r.detection(), r.falseAlarm(), r.medianLinked());
        assertThat(r.detection()).as("storm detected (>= 5 storm tickets clustered)").isGreaterThanOrEqualTo(0.99);
        assertThat(r.medianLinked()).as("margin above minClusterSize").isGreaterThan(MIN_CLUSTER_SIZE);
    }

    @Test
    @DisplayName("shipped tau/entityBoost do not turn an unrelated burst into an incident")
    void shippedValuesDoNotFalseAlarm() {
        Rates r = rates(SHIPPED_TAU, SHIPPED_BOOST, 43);
        assertThat(r.falseAlarm()).as("unrelated 30-ticket burst clustered into >= 5").isLessThanOrEqualTo(0.01);
    }

    @Test
    @DisplayName("the previous tau=0.82 never detected the real storm - why it changed")
    void previousTauMissedEveryRealStorm() {
        Rates r = rates(0.82, SHIPPED_BOOST, 44);
        assertThat(r.detection()).isZero();
    }

    // ── Measurement ─────────────────────────────────────────────────────────

    private record Rates(double detection, double falseAlarm, int medianLinked) {
    }

    private static Rates rates(double tau, double boost, long seed) {
        TicketClusterer clusterer = new TicketClusterer(tau, boost);
        List<Labelled> storm = ofLabel("storm");
        List<Labelled> noStorm = ofLabel("no_storm");
        List<Labelled> starter = ofLabel("starter");
        Random random = new Random(seed);
        int detected = 0;
        int falseAlarms = 0;
        List<Integer> linked = new ArrayList<>();
        for (int d = 0; d < DRAWS; d++) {
            List<Labelled> outage = new ArrayList<>(storm);
            outage.addAll(sample(starter, BACKGROUND, random));
            int stormInBest = bestStormCluster(clusterer, outage);
            linked.add(stormInBest);
            if (stormInBest >= MIN_CLUSTER_SIZE) {
                detected++;
            }

            List<Labelled> burst = new ArrayList<>(noStorm);
            burst.addAll(sample(starter, BACKGROUND, random));
            boolean any = clusterer.cluster(tickets(burst)).stream()
                    .anyMatch(c -> c.size() >= MIN_CLUSTER_SIZE);
            if (any) {
                falseAlarms++;
            }
        }
        Collections.sort(linked);
        return new Rates((double) detected / DRAWS, (double) falseAlarms / DRAWS,
                linked.get(linked.size() / 2));
    }

    private static int bestStormCluster(TicketClusterer clusterer, List<Labelled> window) {
        Set<Long> stormIds = new HashSet<>();
        window.stream().filter(l -> l.label().equals("storm"))
                .forEach(l -> stormIds.add(l.ticket().ticketId()));
        int best = 0;
        for (Cluster c : clusterer.cluster(tickets(window))) {
            int n = (int) c.ticketIds().stream().filter(stormIds::contains).count();
            best = Math.max(best, n);
        }
        return best;
    }

    private static List<CandidateTicket> tickets(List<Labelled> ls) {
        return ls.stream().map(Labelled::ticket).toList();
    }

    private static List<Labelled> sample(List<Labelled> from, int n, Random random) {
        List<Labelled> copy = new ArrayList<>(from);
        Collections.shuffle(copy, random);
        return copy.subList(0, n);
    }

    private static List<Labelled> ofLabel(String label) {
        return corpus.stream().filter(l -> l.label().equals(label)).toList();
    }

    private static float[] decodeFloat16(String base64) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[buf.remaining() / 2];
        for (int i = 0; i < v.length; i++) {
            v[i] = Float.float16ToFloat(buf.getShort());
        }
        return v;
    }
}
