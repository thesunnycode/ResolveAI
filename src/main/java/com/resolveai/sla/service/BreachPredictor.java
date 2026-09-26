package com.resolveai.sla.service;

import com.resolveai.sla.domain.CalendarSpec;
import com.resolveai.sla.domain.SlaKind;
import com.resolveai.sla.domain.SlaRecord;
import com.resolveai.sla.domain.SlaState;
import com.resolveai.sla.web.dto.SlaResponse;
import com.resolveai.ticketing.domain.Ticket;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Will this ticket breach?
 *
 * <h2>This is not an LLM, and that is a decision rather than a limitation</h2>
 *
 * <p>A p75 over ninety days of the same class of ticket is <b>faster, cheaper, more
 * accurate and explainable</b> than asking a model. It costs one indexed aggregate; a
 * model would cost a network round trip and some tokens per dashboard row, and would
 * produce a number nobody could audit. More to the point, the honest answer to "how long
 * do tickets like this take?" is a statistic about tickets like this — a model would be
 * guessing at the same thing with less information.
 *
 * <p>Knowing where <i>not</i> to put the model is part of the design of a system that has
 * one. The governing rule for the whole project: AI produces signals, deterministic code
 * makes decisions.
 *
 * <h2>The fallback ladder, and why {@code basis} is returned</h2>
 *
 * <p>The narrowest grouping — (category, priority, team) — is the most predictive and the
 * one most likely to have too few samples. So it falls back: drop the team, then drop the
 * category, requiring at least {@value #MIN_SAMPLE} rows at each level.
 *
 * <p>The grouping that was actually used is reported in {@code basis}, with the sample
 * size. A prediction of "180 minutes" means something quite different at n=214 for this
 * exact class than at n=22 across every category, and a number that does not say which it
 * is will be trusted equally in both cases.
 */
@Service
public class BreachPredictor {

    private static final Logger log = LoggerFactory.getLogger(BreachPredictor.class);

    /**
     * Below this, a percentile is noise dressed as a statistic.
     *
     * <p>Twenty is the figure the plan names. It is a judgement rather than a derivation:
     * at n=20 a p75 is roughly the fifth-slowest observation, which is stable enough to
     * rank a queue by and not stable enough to quote at a customer — which is exactly how
     * it is used.
     */
    static final int MIN_SAMPLE = 20;

    private static final int WINDOW_DAYS = 90;

    private final NamedParameterJdbcTemplate jdbc;
    private final SlaCalculator calculator;

    public BreachPredictor(NamedParameterJdbcTemplate jdbc, SlaCalculator calculator) {
        this.jdbc = jdbc;
        this.calculator = calculator;
    }

    /** One grouping's answer. */
    private record Percentile(double p75, int sampleSize, String grouping) {
    }

    /**
     * Percentiles already looked up during one read, keyed by (tenant, priority, category,
     * team) - the inputs of the fallback ladder.
     *
     * <p>A queue page is a handful of classes of ticket repeated many times; without this,
     * each row of the same class ran the same one-to-three aggregates again. Scoped to a
     * single request by the caller, so it never serves a stale figure.
     */
    public static final class PercentileCache {
        private final Map<List<Object>, Optional<Percentile>> byClass = new HashMap<>();
    }

    public PercentileCache newCache() {
        return new PercentileCache();
    }

    /**
     * Records a resolution. Called from the resolve transaction, with the business-minute
     * figure the real arithmetic produced.
     *
     * <p>Appended rather than recomputed later — see {@code V8__resolved_ticket_stat.sql}
     * for why there is no materialized view here.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordResolution(Ticket ticket, long businessMinutes, OffsetDateTime at) {
        if (!ticket.getPriority().isTriaged()) {
            // An untriaged ticket has no priority to group by, so it would pollute every
            // fallback level without belonging to any of them.
            return;
        }
        jdbc.update("""
                INSERT INTO resolved_ticket_stat
                    (tenant_id, ticket_id, category, priority, team_id, business_minutes,
                     resolved_at)
                VALUES (:tenantId, :ticketId, :category, :priority, :teamId, :minutes, :at)
                ON CONFLICT (ticket_id, resolved_at) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", ticket.getTenantId())
                        .addValue("ticketId", ticket.getId())
                        .addValue("category", ticket.getCategory())
                        .addValue("priority", ticket.getPriority().name())
                        .addValue("teamId", ticket.getTeam() == null ? null
                                : ticket.getTeam().getId())
                        .addValue("minutes", Math.max(0, businessMinutes))
                        .addValue("at", at));
    }

    /**
     * The prediction for a clock, or empty when there is not enough history at any level.
     *
     * <p>Empty is an honest answer. A confident-looking figure derived from four samples
     * is worse than no figure, because it will be believed.
     */
    @Transactional(readOnly = true)
    public Optional<SlaResponse.PredictionView> predict(SlaRecord record,
                                                        CalendarSpec calendar,
                                                        OffsetDateTime now) {
        if (!predictable(record)) {
            return Optional.empty();
        }
        return predictWith(record, elapsedOf(record, calendar, now), null);
    }

    /** Only the resolution clock is predictable this way. A first response is a human
     *  deciding to type, not a duration with a distribution. */
    private static boolean predictable(SlaRecord record) {
        return record.getKind() == SlaKind.RESOLUTION && record.getState() == SlaState.RUNNING;
    }

    private Optional<SlaResponse.PredictionView> predictWith(SlaRecord record, long elapsed,
                                                             PercentileCache cache) {
        if (!predictable(record)) {
            return Optional.empty();
        }
        Ticket ticket = record.getTicket();
        Optional<Percentile> found = cache == null
                ? percentileFor(ticket)
                : cache.byClass.computeIfAbsent(classOf(ticket), k -> percentileFor(ticket));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Percentile p = found.get();
        long predicted = Math.round(p.p75());
        long remaining = record.getTargetMinutes() - elapsed;

        return Optional.of(new SlaResponse.PredictionView(predicted,
                "p75 over %d days for (%s), n=%d".formatted(WINDOW_DAYS, p.grouping(),
                        p.sampleSize()),
                predicted > remaining));
    }

    /** The boolean alone, for the queue row, without building the whole view. */
    @Transactional(readOnly = true)
    public boolean isAtRisk(SlaRecord record, CalendarSpec calendar, OffsetDateTime now) {
        return predict(record, calendar, now)
                .map(SlaResponse.PredictionView::atRisk)
                .orElse(false);
    }

    /**
     * The same answer for a list page: the elapsed figure the caller already computed, and
     * percentiles shared across rows of the same class through {@code cache}.
     */
    @Transactional(readOnly = true)
    public boolean isAtRisk(SlaRecord record, long elapsedBusinessMinutes, PercentileCache cache) {
        return predictWith(record, elapsedBusinessMinutes, cache)
                .map(SlaResponse.PredictionView::atRisk)
                .orElse(false);
    }

    /**
     * Walks the fallback ladder, narrowest first.
     *
     * <p>{@code IS NOT DISTINCT FROM} rather than {@code =} for the nullable columns: a
     * ticket with no category must match the historical tickets with no category, and
     * {@code category = NULL} matches nothing at all. That is the kind of bug that makes a
     * prediction quietly unavailable for a third of the queue.
     */
    private Optional<Percentile> percentileFor(Ticket ticket) {
        Long teamId = ticket.getTeam() == null ? null : ticket.getTeam().getId();
        String category = ticket.getCategory();

        List<String[]> ladder = List.of(
                new String[] {"AND category IS NOT DISTINCT FROM :category "
                              + "AND team_id IS NOT DISTINCT FROM :teamId",
                              describe(category, teamId)},
                new String[] {"AND category IS NOT DISTINCT FROM :category",
                              describe(category, null)},
                new String[] {"", describe(null, null)});

        for (String[] level : ladder) {
            Optional<Percentile> result = query(ticket, level[0], level[1], category, teamId);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Optional<Percentile> query(Ticket ticket, String extraPredicates, String grouping,
                                       String category, Long teamId) {
        String sql = """
                SELECT percentile_cont(0.75) WITHIN GROUP (ORDER BY business_minutes) AS p75,
                       count(*) AS n
                  FROM resolved_ticket_stat
                 WHERE tenant_id = :tenantId
                   AND priority = :priority
                   AND resolved_at > NOW() - make_interval(days => :windowDays)
                   %s
                """.formatted(extraPredicates);

        var params = new MapSqlParameterSource()
                .addValue("tenantId", ticket.getTenantId())
                .addValue("priority", ticket.getPriority().name())
                .addValue("windowDays", WINDOW_DAYS)
                .addValue("category", category)
                .addValue("teamId", teamId);

        return jdbc.query(sql, params, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            int n = rs.getInt("n");
            double p75 = rs.getDouble("p75");
            if (n < MIN_SAMPLE || rs.wasNull()) {
                return Optional.empty();
            }
            log.trace("Prediction basis {} at n={}", grouping, n);
            return Optional.of(new Percentile(p75, n, grouping));
        });
    }

    /** The ladder's inputs. {@code Arrays.asList} because category and team may be null. */
    private static List<Object> classOf(Ticket ticket) {
        return Arrays.asList(ticket.getTenantId(), ticket.getPriority(), ticket.getCategory(),
                ticket.getTeam() == null ? null : ticket.getTeam().getId());
    }

    private static String describe(String category, Long teamId) {
        if (category == null && teamId == null) {
            return "priority only";
        }
        if (teamId == null) {
            return (category == null ? "no category" : category) + ", priority";
        }
        return (category == null ? "no category" : category) + ", priority, team " + teamId;
    }

    private long elapsedOf(SlaRecord record, CalendarSpec calendar, OffsetDateTime now) {
        return calculator.elapsedBusinessMinutes(record.getId(), calendar, now);
    }
}
