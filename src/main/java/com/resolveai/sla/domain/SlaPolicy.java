package com.resolveai.sla.domain;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.ticketing.domain.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * The targets for one (priority, plan tier) combination, valid over a period.
 *
 * <h2>Effective dating, and why it is not a mutable row</h2>
 *
 * <p>Policies change. Tightening P1 first response from four hours to two in October must
 * not retroactively breach a September ticket that took three - the customer was promised
 * four. So a change supersedes rather than edits: the old row gets an {@code effective_to}
 * and a new row starts.
 *
 * <p>{@code uq_sla_policy_live}, a partial unique index on
 * {@code (tenant, priority, plan) WHERE effective_to IS NULL}, makes overlapping live
 * policies impossible at the database level rather than by convention.
 *
 * <p>The record that uses a policy also <b>snapshots</b> its targets and version label, so
 * even the effective-dated lookup is not on the critical path for explaining a historical
 * breach. See {@code SlaRecord}.
 */
@Entity
@Table(name = "sla_policy")
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", nullable = false, length = 20)
    private PlanTier planTier;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    /**
     * The percentages of the target at which somebody is told.
     *
     * <p>Per policy rather than global: an enterprise P1 wants warning at 25%, and a free
     * P4 does not need four notifications on its way to a breach that nobody is paid to
     * prevent.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "escalation_rungs", nullable = false, columnDefinition = "smallint[]")
    private Short[] escalationRungs = {50, 75, 90, 100};

    @Column(name = "effective_from", nullable = false)
    private OffsetDateTime effectiveFrom;

    /** Null means currently in force. */
    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "version_label", nullable = false, length = 30)
    private String versionLabel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SlaPolicy() {
        // JPA
    }

    public SlaPolicy(Priority priority, PlanTier planTier, int firstResponseMinutes,
                     int resolutionMinutes, OffsetDateTime effectiveFrom, String versionLabel) {
        this.priority = priority;
        this.planTier = planTier;
        this.firstResponseMinutes = firstResponseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.effectiveFrom = effectiveFrom;
        this.versionLabel = versionLabel;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Priority getPriority() { return priority; }
    public PlanTier getPlanTier() { return planTier; }
    public int getFirstResponseMinutes() { return firstResponseMinutes; }
    public int getResolutionMinutes() { return resolutionMinutes; }
    public Short[] getEscalationRungs() { return escalationRungs.clone(); }
    public OffsetDateTime getEffectiveFrom() { return effectiveFrom; }
    public OffsetDateTime getEffectiveTo() { return effectiveTo; }
    public String getVersionLabel() { return versionLabel; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setEscalationRungs(Short[] rungs) { this.escalationRungs = rungs.clone(); }
    public void supersedeAt(OffsetDateTime at) { this.effectiveTo = at; }

    /** The target for one kind of clock. */
    public int targetMinutesFor(SlaKind kind) {
        return kind == SlaKind.FIRST_RESPONSE ? firstResponseMinutes : resolutionMinutes;
    }

    /** The rungs in ascending order, as ints. */
    public List<Short> rungs() {
        return Arrays.stream(escalationRungs).sorted().toList();
    }
}
