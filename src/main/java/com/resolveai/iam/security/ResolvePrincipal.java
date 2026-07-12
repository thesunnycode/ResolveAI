package com.resolveai.iam.security;

import com.resolveai.iam.domain.Role;

/**
 * Who is making this request, built entirely from verified token claims.
 *
 * <p><b>No database lookup happens to produce this.</b> The token is signed, so its claims
 * are trustworthy, and adding a user lookup to every request would put a query in front of
 * every endpoint to learn something the token already states. The cost is that a role change
 * takes effect only at the next token refresh - at most 15 minutes - which is the same
 * trade-off that makes the access token stateless in the first place.
 *
 * @param userId     {@code sub}
 * @param tenantId   {@code tenantId} - <b>the only source of tenancy.</b> Never a header,
 *                   never a path variable, never a request body
 * @param tenantSlug {@code tenantSlug}, carried for logging and responses
 * @param role       {@code role}
 * @param tokenId    {@code jti}, so a specific token can be named in an audit log
 */
public record ResolvePrincipal(Long userId, Long tenantId, String tenantSlug, Role role,
                               String tokenId) {

    public boolean isAtLeast(Role other) {
        return role.atLeast(other);
    }

    @Override
    public String toString() {
        // Appears in logs. The jti is deliberately absent: it identifies a live credential.
        return "user=" + userId + " tenant=" + tenantSlug + " role=" + role;
    }
}
