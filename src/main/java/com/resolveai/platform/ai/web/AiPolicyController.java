package com.resolveai.platform.ai.web;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.ai.TenantAiPolicy;
import com.resolveai.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant's own AI settings. {@code ADMIN} only.
 *
 * <p><b>No tenant id in the path.</b> It comes from the authenticated caller's context,
 * so an admin can only ever read or change their own tenant's policy — a
 * {@code /admin/tenants/{id}/ai-policy} shape would need an authorisation check that
 * this one cannot forget.
 */
@RestController
@RequestMapping("/api/v1/admin/ai-policy")
public class AiPolicyController {

    private final AiPolicyService policies;

    public AiPolicyController(AiPolicyService policies) {
        this.policies = policies;
    }

    /**
     * @param currentMonthSpendMicros read-only in this response. It is moved by usage,
     *                                never by an admin — an editable spend counter is a
     *                                way to make the bill say anything you like.
     */
    public record AiPolicyView(
            Long tenantId,
            boolean externalModelAllowed,
            List<String> allowedProviders,
            boolean piiRedactionRequired,
            long monthlyBudgetMicros,
            long currentMonthSpendMicros,
            long budgetRemainingMicros,
            int retentionDays) {

        static AiPolicyView of(TenantAiPolicy policy) {
            return new AiPolicyView(
                    policy.getTenantId(),
                    policy.isExternalModelAllowed(),
                    policy.getAllowedProviders(),
                    policy.isPiiRedactionRequired(),
                    policy.getMonthlyBudgetMicros(),
                    policy.getCurrentMonthSpendMicros(),
                    policy.getMonthlyBudgetMicros() - policy.getCurrentMonthSpendMicros(),
                    policy.getRetentionDays());
        }
    }

    /**
     * @param piiRedactionRequired settable, but see the note on the handler: turning it
     *                             off is the one change here with a consequence that
     *                             cannot be reversed
     */
    public record UpdateAiPolicyRequest(
            @NotNull(message = "externalModelAllowed is required")
            Boolean externalModelAllowed,

            List<String> allowedProviders,

            @NotNull(message = "piiRedactionRequired is required")
            Boolean piiRedactionRequired,

            @NotNull(message = "monthlyBudgetMicros is required")
            @Min(value = 0, message = "A budget cannot be negative")
            Long monthlyBudgetMicros,

            @NotNull(message = "retentionDays is required")
            @Min(value = 1, message = "Retention must be at least a day")
            Integer retentionDays) {
    }

    @GetMapping
    @IsAdmin
    public AiPolicyView get() {
        Long tenantId = TenantContext.getRequired();
        // ensureExists rather than 404: a tenant always has a policy conceptually, and
        // the absence of a row is a provisioning gap rather than something an admin
        // should have to understand. The defaults it creates are the restrictive ones.
        return AiPolicyView.of(policies.ensureExists(tenantId));
    }

    /**
     * Replaces the policy.
     *
     * <p>A full {@code PUT} rather than a {@code PATCH}: there are five fields, they are
     * read together before every model call, and a partial update of a safety setting is
     * a good way to change one thing and silently keep another. Sending the whole object
     * means the admin has seen all five.
     */
    @PutMapping
    @IsAdmin
    public AiPolicyView update(@Valid @RequestBody UpdateAiPolicyRequest request) {
        Long tenantId = TenantContext.getRequired();
        TenantAiPolicy updated = policies.update(tenantId,
                request.externalModelAllowed(),
                request.allowedProviders() == null ? List.of() : request.allowedProviders(),
                request.piiRedactionRequired(),
                request.monthlyBudgetMicros(),
                request.retentionDays());
        return AiPolicyView.of(updated);
    }
}
