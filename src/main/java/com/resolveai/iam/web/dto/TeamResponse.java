package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.Team;

public record TeamResponse(Long id, String name, String[] skills, boolean isDefault) {

    public static TeamResponse from(Team team) {
        return new TeamResponse(team.getId(), team.getName(), team.getSkills(), team.isDefault());
    }
}
