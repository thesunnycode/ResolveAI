package com.resolveai.ticketing.web.dto;

import com.resolveai.iam.domain.Team;

public record TeamRef(Long id, String name) {

    public static TeamRef of(Team team) {
        return team == null ? null : new TeamRef(team.getId(), team.getName());
    }
}
