package com.resolveai.incidents.service;

import com.resolveai.incidents.service.TicketClusterer.Cluster;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * The component the entire feature's credibility rests on.
 *
 * <p><b>No Spring, no repository, no I/O.</b> A cluster and a baseline go in; a verdict and
 * every input that produced it come out. That is deliberate: this is the one place in the
 * whole project where the sentence "no model has a vote" has to be checkable by reading a
 * single method with zero dependencies, not taken on trust.
 *
 * <pre>
 * propose only when ALL hold:
 *    cluster.size &gt;= config.minClusterSize            (default 5)
 *    AND arrivalRate &gt; config.minRateMultiple * baseline.count   (default 3.0)
 *    AND cluster.windowMinutes &lt;= config.maxWindowMinutes        (default 30)
 *    AND no live incident already covers &gt; 50% of these tickets
 * </pre>
 *
 * <p>{@code arrivalRate} is the cluster's ticket count over its own window, normalised to
 * an hourly rate so it is comparable with {@code baseline.count} (itself an average count
 * per hour-of-week slot).
 *
 * <p><b>The fourth condition</b> stops a storm that is already being handled from proposing
 * a duplicate incident on every sweep after it keeps receiving new tickets. Without it, a
 * confirmed incident would spawn a lookalike every 60 seconds for as long as the outage
 * continues.
 */
public class CorrelationGate {

    public record GateConfig(int minClusterSize, double minRateMultiple, int maxWindowMinutes,
                             double maxLiveOverlapFraction) {
        public static GateConfig defaults() {
            return new GateConfig(5, 3.0, 30, 0.50);
        }
    }

    /**
     * @param existingLiveTicketIds ids of tickets already linked, live, to some existing
     *                              incident — used only to compute the overlap condition
     */
    public record GateInput(Cluster cluster, double baselineCount, int baselineSampleWeeks,
                            String baselineSource, GateConfig config,
                            List<Long> existingLiveTicketIds) {
    }

    public record GateDecision(boolean propose, int clusterSize, double arrivalRateMultiple,
                               int windowMinutes, double overlapFraction, GateConfig config,
                               String reason) {
    }

    public GateDecision evaluate(GateInput input) {
        Cluster cluster = input.cluster();
        GateConfig config = input.config();

        int windowMinutes = windowMinutesOf(cluster);
        double hourlyRate = hourlyRateOf(cluster.size(), windowMinutes);
        // baselineCount of 0 (a FLOOR of literally zero, or an empty division) would make
        // any burst infinitely "above baseline" — floored to a small positive number so
        // the multiple is always finite and meaningful.
        double effectiveBaseline = Math.max(input.baselineCount(), 0.01);
        double rateMultiple = hourlyRate / effectiveBaseline;

        double overlap = overlapFraction(cluster.ticketIds(), input.existingLiveTicketIds());

        boolean sizeOk = cluster.size() >= config.minClusterSize();
        boolean rateOk = rateMultiple > config.minRateMultiple();
        boolean windowOk = windowMinutes <= config.maxWindowMinutes();
        boolean overlapOk = overlap <= config.maxLiveOverlapFraction();

        boolean propose = sizeOk && rateOk && windowOk && overlapOk;

        String reason = "size %s%d>=%d, rate %s%.2fx>%.1fx, window %s%dmin<=%dmin, overlap %s%.0f%%<=%.0f%%"
                .formatted(sizeOk ? "PASS " : "FAIL ", cluster.size(), config.minClusterSize(),
                        rateOk ? "PASS " : "FAIL ", rateMultiple, config.minRateMultiple(),
                        windowOk ? "PASS " : "FAIL ", windowMinutes, config.maxWindowMinutes(),
                        overlapOk ? "PASS " : "FAIL ", overlap * 100, config.maxLiveOverlapFraction() * 100);

        return new GateDecision(propose, cluster.size(), round(rateMultiple), windowMinutes,
                overlap, config, reason);
    }

    private static int windowMinutesOf(Cluster cluster) {
        if (cluster.windowStart() == null || cluster.windowEnd() == null) {
            return 0;
        }
        return (int) Duration.between(cluster.windowStart(), cluster.windowEnd()).toMinutes();
    }

    /** Guards against a zero-width window (every ticket in the same instant) dividing by zero. */
    private static double hourlyRateOf(int clusterSize, int windowMinutes) {
        double minutes = Math.max(windowMinutes, 1);
        return clusterSize * (60.0 / minutes);
    }

    private static double overlapFraction(List<Long> clusterTicketIds,
                                          List<Long> existingLiveTicketIds) {
        if (clusterTicketIds.isEmpty() || existingLiveTicketIds == null
                || existingLiveTicketIds.isEmpty()) {
            return 0.0;
        }
        long overlapping = clusterTicketIds.stream().filter(existingLiveTicketIds::contains).count();
        return (double) overlapping / clusterTicketIds.size();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
