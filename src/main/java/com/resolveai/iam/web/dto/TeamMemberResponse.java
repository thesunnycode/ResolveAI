package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;

public record TeamMemberResponse(Long id, String fullName, String email, Role role,
                                 Long teamId, String teamName) {

    public static TeamMemberResponse from(AppUser user) {
        return new TeamMemberResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole(),
                user.getTeam() == null ? null : user.getTeam().getId(),
                user.getTeam() == null ? null : user.getTeam().getName());
    }
}
