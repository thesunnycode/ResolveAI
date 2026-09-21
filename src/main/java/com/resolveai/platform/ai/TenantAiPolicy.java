package com.resolveai.platform.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * What a tenant permits their data to be used for, and how much they will pay for it.
 *
 * <h2>The tenant id is the primary key</h2>
 *
 * <p>Not a surrogate id with a unique constraint on {@code tenant_id}. A tenant has
 * exactly one policy, the relationship is the identity, and a separate key would make
 * "two policies for one tenant" representable — which is precisely the state where
 * <i>which</i> one governs an LLM call becomes a question nobody can answer from the
 * schema.
 *
 * <h2>Why a policy exists at all</h2>
 *
 * <p>Because "can we send this customer's message to OpenAI?" is a question with
 * different answers per tenant, and the answer has to live somewhere a call site cannot
 * skip. A bank on the ENTERPRISE plan may forbid external models entirely; a startup on
 * FREE may not care. Encoding that as configuration would make it one deploy away from
 * being wrong for everybody; encoding it per tenant, read before every call, makes it a
 * property of the customer relationship.
 */
@Entity
@Table(name = "tenant_ai_policy")
public class TenantAiPolicy {

    /**
     * The tenant. Assigned, never generated — this row exists <i>because</i> the tenant
     * does.
     */
    @Id
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "external_model_allowed", nullable = false)
    private boolean externalModelAllowed = true;

    /**
     * Provider names this tenant permits, as an allow-list.
     *
     * <p>Empty means "no provider named", which with {@code externalModelAllowed} true
     * means any configured provider. Named explicitly so a tenant can say "OpenAI yes,
     * anyone else no" without turning external models off entirely.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_providers", nullable = false, columnDefinition = "text[]")
    private String[] allowedProviders = new String[0];

    @Column(name = "pii_redaction_required", nullable = false)
    private boolean piiRedactionRequired = true;

    /**
     * Micros, not a decimal. Money in a {@code double} accumulates rounding error over a
     * month of per-call increments, and the direction of the error is not predictable.
     */
    @Column(name = "monthly_budget_micros", nullable = false)
    private long monthlyBudgetMicros = 5_000_000L;

    @Column(name = "current_month_spend_micros", nullable = false)
    private long currentMonthSpendMicros;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays = 365;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected TenantAiPolicy() {
    }

    public TenantAiPolicy(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public boolean isExternalModelAllowed() {
        return externalModelAllowed;
    }

    public void setExternalModelAllowed(boolean externalModelAllowed) {
        this.externalModelAllowed = externalModelAllowed;
    }

    public List<String> getAllowedProviders() {
        return allowedProviders == null ? List.of() : List.of(allowedProviders);
    }

    public void setAllowedProviders(List<String> providers) {
        this.allowedProviders = providers == null ? new String[0] : providers.toArray(String[]::new);
    }

    public boolean isPiiRedactionRequired() {
        return piiRedactionRequired;
    }

    public void setPiiRedactionRequired(boolean piiRedactionRequired) {
        this.piiRedactionRequired = piiRedactionRequired;
    }

    public long getMonthlyBudgetMicros() {
        return monthlyBudgetMicros;
    }

    public void setMonthlyBudgetMicros(long monthlyBudgetMicros) {
        this.monthlyBudgetMicros = monthlyBudgetMicros;
    }

    public long getCurrentMonthSpendMicros() {
        return currentMonthSpendMicros;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
