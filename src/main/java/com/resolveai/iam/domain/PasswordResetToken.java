package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A single-use, time-limited link that lets someone set a new password without their old one.
 *
 * <p><b>Deliberately not {@code @TenantId}-annotated</b>, for the same reason {@link Invite}
 * isn't: the reset-password page resolves a tenant from the token alone, before any tenant
 * context exists, and {@code TenantContext}'s unset state matches no row rather than every
 * row - a discriminator here would make that lookup find nothing. {@code tenantId} is an
 * ordinary column instead, read directly off this row once it's found by token.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    protected PasswordResetToken() {
        // JPA
    }

    public PasswordResetToken(Long tenantId, Long userId, String token, OffsetDateTime expiresAt) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public Long getUserId() { return userId; }
    public String getToken() { return token; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getUsedAt() { return usedAt; }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed() {
        this.usedAt = OffsetDateTime.now();
    }
}
