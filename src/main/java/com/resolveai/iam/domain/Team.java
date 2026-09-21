package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A routing destination. {@code skills} drives which categories the team can take.
 *
 * <p><b>{@code skills} is a real {@code text[]}, not a comma-separated string.</b> There is a
 * GIN index on it ({@code idx_team_skills}), so "which teams handle PAYMENT" is an index scan
 * rather than a {@code LIKE '%PAYMENT%'} over every row - and a substring match would also
 * match a category that merely contains another category's name.
 *
 * <p><b>Soft delete.</b> {@code @SQLDelete} turns {@code delete()} into an update and
 * {@code @SQLRestriction} hides deleted rows from every query. Tickets reference teams, and
 * a hard delete would either fail on the foreign key or orphan history that the audit trail
 * depends on.
 */
@Entity
@Table(name = "team")
@SQLDelete(sql = "UPDATE team SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Written and filtered by Hibernate itself. Not settable through a constructor or a
     * setter on purpose: the only way a row gets a tenant is from the resolver, so there is
     * no code path that can put a row in the wrong one.
     */
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 80)
    private String name;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] skills = new String[0];

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Team() {
        // JPA
    }

    public Team(String name, String[] skills, boolean isDefault) {
        this.name = name;
        this.skills = skills == null ? new String[0] : skills.clone();
        this.isDefault = isDefault;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String[] getSkills() { return skills.clone(); }
    public boolean isDefault() { return isDefault; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setSkills(String[] skills) { this.skills = skills == null ? new String[0] : skills.clone(); }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
}
