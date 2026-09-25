package com.resolveai.platform.analytics;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.platform.web.InMemoryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * First-party product analytics: the onboarding funnel, measured.
 *
 * <p>The onboarding audit could not answer its own open questions - which demo role
 * visitors choose, how many reach a shown draft, whether suppressed drafts lose them -
 * because nothing recorded them. This records a small, fixed vocabulary of events.
 *
 * <h2>What it will not accept</h2>
 * <ul>
 *   <li><b>Unknown event names.</b> An allow-list, so the table stays a funnel and does not
 *       become a log of whatever the client felt like sending.</li>
 *   <li><b>Free text.</b> Property values are numbers, booleans or short strings (codes,
 *       statuses, route names) - no ticket bodies, no emails. Longer strings are cut.</li>
 *   <li><b>Identity from the client.</b> Tenant, user and role come from the verified token
 *       when there is one and are simply absent when there is not; a body cannot claim to be
 *       somebody.</li>
 * </ul>
 *
 * <p>Public, because the top of the funnel - the login page - is before sign-in. Rate
 * limited per address for the same reason.
 */
@RestController
public class ProductEventController {

    static final Set<String> EVENTS = Set.of(
            "auth_page_viewed", "demo_login_clicked", "auth_failed", "login_succeeded",
            "register_submitted", "register_failed", "first_page_loaded", "ticket_opened",
            "draft_requested", "draft_result", "draft_used", "reply_sent_from_draft",
            "incident_viewed", "storm_started", "customer_ticket_created",
            "tour_item_completed", "tour_dismissed", "eval_run_started");

    private static final int MAX_PROPS = 12;
    private static final int MAX_STRING = 80;

    public record EventIn(@NotNull @Size(max = 64) String name,
                          @Size(max = 200) String path,
                          @Size(max = 64) String sessionId,
                          Map<String, Object> props) {
    }

    public record Batch(@NotNull @Size(max = 25) List<@Valid EventIn> events) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final InMemoryRateLimiter limiter;

    public ProductEventController(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                  InMemoryRateLimiter limiter) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.limiter = limiter;
    }

    /** Always {@code 202}, even for rejected events: analytics must never break the app. */
    @PostMapping("/api/v1/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void record(@Valid @RequestBody Batch batch,
                       @AuthenticationPrincipal ResolvePrincipal principal,
                       HttpServletRequest http) {
        if (!limiter.tryAcquire("events:" + http.getRemoteAddr(), 240, 60)) {
            return;
        }
        for (EventIn e : batch.events()) {
            if (!EVENTS.contains(e.name())) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO product_event (name, tenant_id, user_id, role, session_id, path, props)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, e.name(),
                    principal == null ? null : principal.tenantId(),
                    principal == null ? null : principal.userId(),
                    principal == null ? null : principal.role().name(),
                    e.sessionId(), e.path(), objectMapper.writeValueAsString(clean(e.props())));
        }
    }

    /**
     * The funnel, last 30 days: sessions reaching each step. Anonymous (pre-login) events
     * have no tenant, so they are counted alongside the caller's own tenant's.
     */
    @GetMapping("/api/v1/admin/analytics/funnel")
    @IsAdmin
    public Map<String, Object> funnel(@AuthenticationPrincipal ResolvePrincipal principal) {
        Map<String, Object> steps = new LinkedHashMap<>();
        for (String step : List.of("auth_page_viewed", "login_succeeded", "first_page_loaded",
                "ticket_opened", "draft_requested", "draft_used", "incident_viewed",
                "customer_ticket_created")) {
            Long n = jdbc.queryForObject("""
                    SELECT count(DISTINCT coalesce(session_id, id::text)) FROM product_event
                     WHERE name = ? AND occurred_at > now() - interval '30 days'
                       AND (tenant_id = ? OR tenant_id IS NULL)
                    """, Long.class, step, principal.tenantId());
            steps.put(step, n == null ? 0 : n);
        }
        List<Map<String, Object>> demoRoles = jdbc.queryForList("""
                SELECT props->>'role' AS role, count(*) AS clicks FROM product_event
                 WHERE name = 'demo_login_clicked' AND occurred_at > now() - interval '30 days'
                 GROUP BY 1 ORDER BY 2 DESC
                """);
        List<Map<String, Object>> draftResults = jdbc.queryForList("""
                SELECT props->>'status' AS status, count(*) AS n FROM product_event
                 WHERE name = 'draft_result' AND occurred_at > now() - interval '30 days'
                   AND tenant_id = ?
                 GROUP BY 1 ORDER BY 2 DESC
                """, principal.tenantId());
        return Map.of("windowDays", 30, "sessionsReaching", steps, "demoLoginsByRole", demoRoles,
                "draftResults", draftResults);
    }

    private static Map<String, Object> clean(Map<String, Object> props) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (props == null) {
            return out;
        }
        for (Map.Entry<String, Object> p : props.entrySet()) {
            if (out.size() >= MAX_PROPS || p.getKey() == null || p.getKey().length() > 40) {
                continue;
            }
            Object v = p.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                out.put(p.getKey(), v);
            } else if (v instanceof String s) {
                out.put(p.getKey(), s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s);
            }
        }
        return out;
    }
}
