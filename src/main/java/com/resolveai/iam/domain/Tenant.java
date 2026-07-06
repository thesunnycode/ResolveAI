package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A customer organisation. The root of every tenant-scoped query.
 *
 * <p><b>This entity is deliberately not {@code @TenantId}-annotated.</b> It is the table the
 * discriminator points at; filtering it by itself would make it impossible to resolve a
 * tenant by slug during login, which happens before any tenant context exists.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 60, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", nullable = false, length = 20)
    private PlanTier planTier = PlanTier.FREE;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Tenant() {
        // JPA
    }

    public Tenant(String name, String slug, PlanTier planTier) {
        this.name = name;
        this.slug = slug;
        this.planTier = planTier;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public PlanTier getPlanTier() { return planTier; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setPlanTier(PlanTier planTier) { this.planTier = planTier; }
    public void setActive(boolean active) { this.active = active; }
}
