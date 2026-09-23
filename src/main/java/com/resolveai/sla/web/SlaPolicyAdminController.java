package com.resolveai.sla.web;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.sla.service.SlaPolicyAdminService;
import com.resolveai.sla.service.SlaPolicyAdminService.SlaPolicyRow;
import com.resolveai.ticketing.domain.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLA targets for the caller's own tenant, at its own plan tier. {@code ADMIN} only.
 *
 * <p>A tenant is one plan tier at a time ({@link com.resolveai.iam.domain.Tenant#getPlanTier()}),
 * so this exposes exactly the four {@code (priority, planTier)} rows that tenant's tickets can
 * ever resolve against - not the {@code sla_policy} table's full plan-tier matrix, which exists
 * because the table is shared across tenants on different plans, not because one tenant edits
 * three plans' worth of targets at once.
 */
@RestController
@RequestMapping("/api/v1/admin/sla-policies")
public class SlaPolicyAdminController {

    private final SlaPolicyAdminService service;

    public SlaPolicyAdminController(SlaPolicyAdminService service) {
        this.service = service;
    }

    public record SlaPolicyView(
            String priority,
            boolean configured,
            Integer firstResponseMinutes,
            Integer resolutionMinutes,
            List<Integer> escalationRungs,
            String versionLabel,
            String effectiveFrom) {

        static SlaPolicyView of(SlaPolicyRow row) {
            return new SlaPolicyView(
                    row.priority().name(),
                    row.configured(),
                    row.firstResponseMinutes(),
                    row.resolutionMinutes(),
                    row.escalationRungs(),
                    row.versionLabel(),
                    row.effectiveFrom() == null ? null : row.effectiveFrom().toString());
        }
    }

    public record UpdateSlaPolicyRequest(
            @NotNull(message = "firstResponseMinutes is required")
            @Min(value = 1, message = "First response target must be at least 1 minute")
            Integer firstResponseMinutes,

            @NotNull(message = "resolutionMinutes is required")
            @Min(value = 1, message = "Resolution target must be at least 1 minute")
            Integer resolutionMinutes) {
    }

    /** The tenant's own plan tier, one row per priority - {@code configured: false} where none exists yet. */
    @GetMapping
    @IsAdmin
    public List<SlaPolicyView> list() {
        return service.currentForTenant().stream().map(SlaPolicyView::of).toList();
    }

    /**
     * Supersedes the live policy for this priority, if any, and starts a new one.
     *
     * <p>Not a {@code PATCH} of the existing row: {@link com.resolveai.sla.domain.SlaPolicy}
     * is effective-dated on purpose, so tightening a target must not retroactively breach a
     * ticket that was promised the old one.
     */
    @PutMapping("/{priority}")
    @IsAdmin
    public SlaPolicyView update(@PathVariable String priority,
                                @Valid @RequestBody UpdateSlaPolicyRequest request) {
        Priority parsed = parsePriority(priority);
        SlaPolicyRow row = service.supersede(parsed, request.firstResponseMinutes(),
                request.resolutionMinutes());
        return SlaPolicyView.of(row);
    }

    private static Priority parsePriority(String raw) {
        try {
            Priority priority = Priority.valueOf(raw);
            if (!priority.isTriaged()) {
                throw new IllegalArgumentException();
            }
            return priority;
        } catch (IllegalArgumentException e) {
            throw new com.resolveai.common.error.ApiException(
                    com.resolveai.common.error.ErrorCode.VALIDATION_ERROR,
                    "priority must be one of P1, P2, P3, P4.", e);
        }
    }
}
