package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.Invite;
import com.resolveai.iam.domain.Role;
import java.time.OffsetDateTime;

public record InviteResponse(
        Long id,
        String email,
        Role role,
        Long teamId,
        String teamName,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime acceptedAt) {

    public static InviteResponse from(Invite invite) {
        return new InviteResponse(
                invite.getId(),
                invite.getEmail(),
                invite.getRole(),
                invite.getTeam().getId(),
                invite.getTeam().getName(),
                invite.getCreatedAt(),
                invite.getExpiresAt(),
                invite.getAcceptedAt());
    }
}
