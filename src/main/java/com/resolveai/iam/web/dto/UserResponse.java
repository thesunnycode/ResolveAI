package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import java.time.OffsetDateTime;

/**
 * The registration response and the {@code user} block inside a token response.
 *
 * <p><b>No password field exists on this type.</b> Not "is set to null" - does not exist.
 * A response DTO that has somewhere to put a hash is a response DTO that will eventually
 * contain one.
 */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        Long teamId,
        Long tenantId,
        String tenantSlug,
        OffsetDateTime createdAt) {

    public static UserResponse from(AppUser user, String tenantSlug) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getTeam() == null ? null : user.getTeam().getId(),
                user.getTenantId(),
                tenantSlug,
                user.getCreatedAt());
    }
}
