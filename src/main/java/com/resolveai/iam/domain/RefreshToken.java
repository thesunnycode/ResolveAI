package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One issued refresh token.
 *
 * <p><b>Only the SHA-256 of the token is stored.</b> The raw value is returned to the client
 * once and never persisted, so a database dump does not hand the reader a set of working
 * credentials. SHA-256 rather than BCrypt is correct here and the distinction is worth being
 * able to explain: a refresh token is 256 bits of {@code SecureRandom} output, not a
 * human-chosen password, so there is no dictionary to slow down - and every refresh would
 * otherwise pay for a deliberately expensive hash.
 *
 * <p><b>{@code familyId} is what turns rotation into a defence rather than hygiene.</b> Every
 * token descended from one login shares it. Seeing a token presented twice means it leaked;
 * the system cannot tell which of the two presenters is the attacker, so it revokes the whole
 * family and forces a fresh login.
 *
 * <p>No {@code @TenantId}: the table has no {@code tenant_id} column. A refresh token is
 * reachable only through its user, and that user is tenant-scoped.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /**
     * Exactly 64 characters: SHA-256 rendered as lowercase hex.
     *
     * <p>The column is {@code char(64)}, not {@code varchar}, and Hibernate has to be told:
     * {@code ddl-auto: validate} rejects the mapping otherwise with "found [bpchar], but
     * expecting [varchar(64)]". Fixed-width is right here - every value is the same length
     * by construction, so there is nothing for a variable-length type to save - but it means
     * the JDBC type must be declared rather than inferred.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** Set the first time this token is exchanged. A second exchange is reuse. */
    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(AppUser user, String tokenHash, UUID familyId, OffsetDateTime expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public String getTokenHash() { return tokenHash; }
    public UUID getFamilyId() { return familyId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getUsedAt() { return usedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void markUsed(OffsetDateTime at) { this.usedAt = at; }
    public void revoke(OffsetDateTime at) { this.revokedAt = at; }

    public boolean isExpired(OffsetDateTime now) { return expiresAt.isBefore(now); }
    public boolean isRevoked() { return revokedAt != null; }
    public boolean isUsed() { return usedAt != null; }
}
