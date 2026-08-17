package com.resolveai.platform.ai.web;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.iam.security.ResolvePrincipal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /admin/ai/usage}: what the AI features cost this month, and against what
 * budget.
 *
 * <p>The Evaluation page's "Token spend" card called this endpoint from the start and got
 * a 404, so it said "No usage reported yet" beside a queue full of triaged tickets. The
 * numbers already exist - every triage ({@code ai_analysis}) and every draft
 * ({@code draft}, including its entailment calls) records tokens, cost and latency - so
 * this is aggregation, not new instrumentation.
 *
 * <p>Month to date, grouped by prompt version and model, per tenant. Native SQL with an
 * explicit {@code tenant_id}, because native queries bypass the Hibernate tenant filter.
 * The richer {@code from}/{@code to}/{@code groupBy} parameters in the OpenAPI example are
 * not implemented; month-to-date is what the budget is measured against.
 */
@RestController
@RequestMapping("/api/v1/admin/ai/usage")
public class AiUsageController {

    public record Budget(long monthlyMicros, long spentMicros, double remainingPct,
                         String status) {
    }

    public record Totals(long calls, long tokensIn, long tokensOut, long costMicros) {
    }

    public record Row(String feature, String promptVersion, String model, long calls,
                      long tokensIn, long tokensOut, long costMicros, long avgLatencyMs,
                      Double suppressionRate) {
    }

    public record Usage(Map<String, String> period, Budget budget, Totals totals,
                        List<Row> breakdown) {
    }

    private final JdbcTemplate jdbc;

    public AiUsageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @IsAdmin
    public Usage usage(@AuthenticationPrincipal ResolvePrincipal principal) {
        Long tenantId = principal.tenantId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Row> rows = jdbc.query("""
                SELECT 'Triage' AS feature, p.name || '@' || p.version AS prompt, a.model_id,
                       count(*) AS calls, sum(a.tokens_in) AS tin, sum(a.tokens_out) AS tout,
                       sum(a.cost_micros) AS cost, avg(a.latency_ms) AS latency,
                       NULL::float8 AS suppression
                  FROM ai_analysis a
                  JOIN prompt_version p ON p.id = a.prompt_version_id
                 WHERE a.tenant_id = ? AND a.created_at >= date_trunc('month', now())
                 GROUP BY 1, 2, 3
                UNION ALL
                SELECT 'Drafting', p.name || '@' || p.version, d.model_id,
                       count(*), sum(d.tokens_in), sum(d.tokens_out), sum(d.cost_micros),
                       avg(d.latency_ms),
                       avg(CASE WHEN d.status LIKE 'SUPPRESSED%' THEN 1.0 ELSE 0.0 END)
                  FROM draft d
                  JOIN prompt_version p ON p.id = d.prompt_version_id
                 WHERE d.tenant_id = ? AND d.created_at >= date_trunc('month', now())
                   AND d.status <> 'PENDING'
                 GROUP BY 1, 2, 3
                 ORDER BY 7 DESC
                """,
                (rs, n) -> new Row(rs.getString("feature"), rs.getString("prompt"),
                        rs.getString("model_id"), rs.getLong("calls"), rs.getLong("tin"),
                        rs.getLong("tout"), rs.getLong("cost"), Math.round(rs.getDouble("latency")),
                        (Double) rs.getObject("suppression")),
                tenantId, tenantId);

        Totals totals = new Totals(
                rows.stream().mapToLong(Row::calls).sum(),
                rows.stream().mapToLong(Row::tokensIn).sum(),
                rows.stream().mapToLong(Row::tokensOut).sum(),
                rows.stream().mapToLong(Row::costMicros).sum());

        List<Budget> budgets = jdbc.query("""
                SELECT monthly_budget_micros, current_month_spend_micros
                  FROM tenant_ai_policy WHERE tenant_id = ?
                """, (rs, n) -> budgetOf(rs.getLong(1), rs.getLong(2)), tenantId);

        return new Usage(
                Map.of("from", today.withDayOfMonth(1).toString(), "to", today.toString()),
                budgets.isEmpty() ? null : budgets.get(0), totals, rows);
    }

    private static Budget budgetOf(long monthly, long spent) {
        double remaining = monthly <= 0 ? 0 : Math.max(0, 100.0 * (monthly - spent) / monthly);
        String status = monthly <= 0 || spent >= monthly ? "EXHAUSTED"
                : remaining < 20 ? "LOW" : "OK";
        return new Budget(monthly, spent, Math.round(remaining * 10) / 10.0, status);
    }
}
