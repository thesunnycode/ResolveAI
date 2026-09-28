package com.resolveai.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A pending or accepted invitation for an Agent or Team Lead to join the tenant that created
 * it. No {@code AppUser} row exists for the invitee until {@code accepted_at} is set -
 * unlike self-registration, which writes the user immediately, an invite is a promise the
 * invitee hasn't redeemed yet, and a half-created account with no password would be a login
 * nobody could ever complete.
 *
 * <p><b>Deliberately not {@code @TenantId}-annotated</b>, for the same reason {@link Tenant}
 * isn't: {@code acceptInvite} resolves a tenant from the token alone, before any tenant
 * context exists, and {@code TenantContext}'s unset state matches no row rather than every
 * row - so a discriminator here would make the accept endpoint find nothing. {@code tenantId}
 * is instead an ordinary column, set explicitly by {@code InviteService} from the
 * authenticated admin's own tenant when an invite is created.
 */
@Entity
@Table(name = "invite")
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "invited_by_user_id", nullable = false)
    private Long invitedByUserId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    protected Invite() {
        // JPA
    }

    public Invite(Long tenantId, String email, Role role, Team team, Long invitedByUserId,
                 String token, OffsetDateTime expiresAt) {
        this.tenantId = tenantId;
        this.email = email;
        this.role = role;
        this.team = team;
        this.invitedByUserId = invitedByUserId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public Role getRole() { return role; }
    public Team getTeam() { return team; }
    public Long getInvitedByUserId() { return invitedByUserId; }
    public String getToken() { return token; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public void markAccepted() {
        this.acceptedAt = OffsetDateTime.now();
    }
}
