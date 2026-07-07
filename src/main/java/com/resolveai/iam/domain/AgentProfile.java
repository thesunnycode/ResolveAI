package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

/**
 * An agent's routing capacity and availability.
 *
 * <p><b>{@code @Version} is what makes concurrent assignment safe.</b> Two leads assigning
 * work to the same agent in the same instant would otherwise both read {@code openCount = 9},
 * both write 10, and the agent would quietly be over capacity. With a version column the
 * second write fails with an {@code OptimisticLockingFailureException}, which the Phase 3
 * advice already maps to {@code 409 VERSION_CONFLICT}.
 *
 * <p><b>{@code updatedAt} is owned by the database, not by Hibernate.</b> The schema carries
 * {@code trg_agent_profile_updated}, a BEFORE UPDATE trigger calling {@code set_updated_at()}.
 * An {@code @UpdateTimestamp} here would write a value the trigger immediately overwrites,
 * leaving the in-memory entity holding a timestamp that was never stored - so the field is
 * mapped read-only and re-read instead.
 */
@Entity
@Table(name = "agent_profile")
public class AgentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private AppUser user;

    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "max_concurrent", nullable = false)
    private int maxConcurrent = 15;

    @Column(name = "open_count", nullable = false)
    private int openCount = 0;

    @Column(name = "is_available", nullable = false)
    private boolean available = true;

    @Column(name = "shift_start")
    private LocalTime shiftStart;

    @Column(name = "shift_end")
    private LocalTime shiftEnd;

    @Version
    @Column(nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected AgentProfile() {
        // JPA
    }

    public AgentProfile(AppUser user, int maxConcurrent) {
        this.user = user;
        this.maxConcurrent = maxConcurrent;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public Long getTenantId() { return tenantId; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public int getOpenCount() { return openCount; }
    public boolean isAvailable() { return available; }
    public LocalTime getShiftStart() { return shiftStart; }
    public LocalTime getShiftEnd() { return shiftEnd; }
    public int getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public void setOpenCount(int openCount) { this.openCount = openCount; }
    public void setAvailable(boolean available) { this.available = available; }
    public void setShift(LocalTime start, LocalTime end) {
        this.shiftStart = start;
        this.shiftEnd = end;
    }

    /** True when this agent can take one more ticket right now. */
    public boolean hasCapacity() {
        return available && openCount < maxConcurrent;
    }
}
