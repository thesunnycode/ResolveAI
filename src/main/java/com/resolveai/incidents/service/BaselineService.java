package com.resolveai.incidents.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * "Is this rate normal for this tenant, at this hour?" — reads
 * {@code ticket_arrival_baseline}, doc 12 Task 5.
 *
 * <h2>Cold start, handled explicitly rather than silently</h2>
 *
 * <p>A gate that falls back to a made-up baseline for a new tenant fires arbitrarily on
 * their first busy Monday, and nobody could tell why from the incident alone. Returning
 * the {@link Baseline.Source} lets {@code CorrelationGate}'s evidence say exactly which
 * case applied — {@code SPECIFIC}, {@code GLOBAL_HOURLY} or {@code FLOOR} — on every
 * incident it proposes.
 */
@Service
public class BaselineService {

    /** Used when a tenant has no history at all — a deliberately low, configurable floor. */
    private static final BigDecimal DEFAULT_FLOOR = BigDecimal.valueOf(2);

    private final NamedParameterJdbcTemplate jdbc;
    private final BigDecimal floor;

    public BaselineService(NamedParameterJdbcTemplate jdbc,
                           org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        double configured = env.getProperty(
                "resolveai.correlation.baseline-floor", Double.class, 2.0);
        this.floor = BigDecimal.valueOf(configured);
    }

    public enum Source {
        /** Three or more weeks of history for this exact (day-of-week, hour). */
        SPECIFIC,
        /** One or two weeks — not enough for the specific slot, so the tenant's overall hourly mean is used. */
        GLOBAL_HOURLY,
        /** No history for this tenant yet. */
        FLOOR
    }

    public record Baseline(BigDecimal count, int sampleWeeks, Source source) {
    }

    public Baseline baselineFor(Long tenantId, Instant at) {
        ZonedDateTime zoned = at.atZone(ZoneOffset.UTC);
        int dow = zoned.getDayOfWeek().getValue() % 7; // Postgres EXTRACT(DOW): Sunday = 0
        int hod = zoned.getHour();

        Row specific = findRow(tenantId, dow, hod);
        if (specific != null && specific.sampleWeeks() >= 3) {
            return new Baseline(specific.baselineCount(), specific.sampleWeeks(), Source.SPECIFIC);
        }

        Row globalHourly = globalHourlyMean(tenantId);
        if (globalHourly != null && globalHourly.sampleWeeks() >= 1) {
            return new Baseline(globalHourly.baselineCount(), globalHourly.sampleWeeks(),
                    Source.GLOBAL_HOURLY);
        }

        return new Baseline(floor, 0, Source.FLOOR);
    }

    private record Row(BigDecimal baselineCount, int sampleWeeks) {
    }

    private Row findRow(Long tenantId, int dow, int hod) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("dow", dow).addValue("hod", hod);
        var rows = jdbc.query("""
                SELECT baseline_count, sample_weeks FROM ticket_arrival_baseline
                 WHERE tenant_id = :tenantId AND dow = :dow AND hod = :hod
                """, params, (rs, i) -> new Row(rs.getBigDecimal("baseline_count"),
                rs.getInt("sample_weeks")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The tenant's overall mean tickets-per-hour across every slot it has any history for
     * — the fallback for a tenant with 1-2 weeks of data, where any single hour's own
     * sample is too thin to trust on its own.
     */
    private Row globalHourlyMean(Long tenantId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        var rows = jdbc.query("""
                SELECT AVG(baseline_count) AS avg_count, MAX(sample_weeks) AS max_weeks
                  FROM ticket_arrival_baseline WHERE tenant_id = :tenantId
                """, params, (rs, i) -> {
            BigDecimal avg = rs.getBigDecimal("avg_count");
            int maxWeeks = rs.getInt("max_weeks");
            return avg == null ? null : new Row(avg, maxWeeks);
        });
        return rows.isEmpty() ? null : rows.get(0);
    }
}
