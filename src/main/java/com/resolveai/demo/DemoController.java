package com.resolveai.demo;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.common.security.IsTeamLeadOrAbove;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.service.AuthService;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.platform.web.InMemoryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo mode's API: the one-click logins, the outage simulator and the showcase tour.
 *
 * <p>The bean only exists when {@code resolveai.demo.enabled=true}. Otherwise every path
 * here is an ordinary 404, which is what the SPA reads as "no demo on this deployment" -
 * so a real deployment shows the plain login form and nothing else.
 *
 * <p>{@code GET /demo} and {@code POST /demo/login} are public (see {@code SecurityConfig});
 * everything else requires a session <b>in the demo tenant</b>, so an agent of a real
 * tenant on the same deployment cannot start a storm or read the demo's showcase.
 */
@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(name = "resolveai.demo.enabled", havingValue = "true")
public class DemoController {

    public record DemoInfo(boolean enabled, String tenantSlug, List<Role> roles) {
    }

    public record DemoLoginRequest(@NotNull(message = "Role is required") Role role) {
    }

    public record ShowcaseItem(Long id, String reference, String title) {
    }

    public record Showcase(ShowcaseItem pausedTicket, ShowcaseItem draftTicket,
                           ShowcaseItem incident) {
    }

    private final DemoSettings settings;
    private final DemoWorkspace workspace;
    private final DemoStormService storm;
    private final InMemoryRateLimiter limiter;
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public DemoController(DemoSettings settings, DemoWorkspace workspace, DemoStormService storm,
                          InMemoryRateLimiter limiter, AuthService auth, JdbcTemplate jdbc) {
        this.settings = settings;
        this.workspace = workspace;
        this.storm = storm;
        this.limiter = limiter;
        this.auth = auth;
        this.jdbc = jdbc;
    }

    @GetMapping
    public DemoInfo info() {
        boolean ready = workspace.tenant().isPresent();
        return new DemoInfo(ready, settings.tenantSlug(), ready ? offeredRoles() : List.of());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody DemoLoginRequest request,
                               HttpServletRequest http) {
        // 20 per 10 minutes per address: plenty for a person clicking through the roles,
        // useless to a script trying to mint sessions in bulk.
        if (!limiter.tryAcquire("login:" + http.getRemoteAddr(), 20, 600)) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many demo sign-ins from this address. Wait a few minutes and try again.");
        }
        if (!offeredRoles().contains(request.role())) {
            throw ApiException.forbidden("The demo does not offer a " + request.role()
                    + " login.");
        }
        Tenant tenant = demoTenant();
        ResolvePrincipal user = workspace.principalFor(tenant, request.role())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                        "The demo has no " + request.role() + " account yet."));
        return auth.issueDemoTokens(tenant.getId(), tenant.getSlug(), user.userId());
    }

    @GetMapping("/storm")
    @IsAgentOrAbove
    public DemoStormService.StormStatus stormStatus(
            @AuthenticationPrincipal ResolvePrincipal principal) {
        requireDemoTenant(principal);
        return storm.status();
    }

    @PostMapping("/storm")
    @IsTeamLeadOrAbove
    public ResponseEntity<Map<String, Object>> startStorm(
            @AuthenticationPrincipal ResolvePrincipal principal) {
        requireDemoTenant(principal);
        return switch (storm.start(demoTenant())) {
            case STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "STARTED",
                    "detail", "Posting " + storm.status().ticketCount() + " tickets over about "
                            + "20 seconds. The correlation sweep runs every minute."));
            case ALREADY_RUNNING -> throw new ApiException(ErrorCode.CONFLICT,
                    "An outage simulation is already running.");
            case COOLING_DOWN -> throw new ApiException(ErrorCode.RATE_LIMITED,
                    "The outage simulator is cooling down. It can run again at "
                            + storm.status().availableAt() + ".");
        };
    }

    /** Where to point a first-time visitor: one of each mechanism, if it exists. */
    @GetMapping("/showcase")
    @IsAgentOrAbove
    public Showcase showcase(@AuthenticationPrincipal ResolvePrincipal principal) {
        requireDemoTenant(principal);
        Long tenantId = principal.tenantId();
        return new Showcase(
                first("""
                        SELECT id, reference, subject FROM ticket
                         WHERE tenant_id = ? AND status = 'WAITING_ON_CUSTOMER'
                         ORDER BY (subject = 'UPI payment stuck on processing for an hour') DESC,
                                  updated_at DESC
                         LIMIT 1
                        """, tenantId),
                first("""
                        SELECT t.id, t.reference, t.subject FROM ticket t
                         WHERE t.tenant_id = ?
                           AND EXISTS (SELECT 1 FROM draft d
                                        WHERE d.ticket_id = t.id AND d.status = 'SHOWN')
                         ORDER BY (t.subject = 'Charged twice for one invoice payment') DESC,
                                  t.id DESC
                         LIMIT 1
                        """, tenantId),
                first("""
                        SELECT id, reference, title FROM incident
                         WHERE tenant_id = ? AND status IN ('PROPOSED', 'CONFIRMED', 'MITIGATED')
                         ORDER BY id DESC
                         LIMIT 1
                        """, tenantId));
    }

    private ShowcaseItem first(String sql, Long tenantId) {
        List<ShowcaseItem> rows = jdbc.query(sql, (rs, n) -> new ShowcaseItem(
                rs.getLong(1), rs.getString(2), rs.getString(3)), tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Role> offeredRoles() {
        List<Role> roles = new ArrayList<>(List.of(Role.AGENT, Role.TEAM_LEAD, Role.CUSTOMER));
        if (settings.allowAdmin()) {
            roles.add(Role.ADMIN);
        }
        return roles;
    }

    private Tenant demoTenant() {
        return workspace.tenant().orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND,
                "The demo workspace is still being set up. Try again in a minute."));
    }

    private void requireDemoTenant(ResolvePrincipal principal) {
        if (!workspace.isDemoTenant(principal.tenantId())) {
            throw ApiException.forbidden("Demo tools are only available in the demo workspace.");
        }
    }
}
