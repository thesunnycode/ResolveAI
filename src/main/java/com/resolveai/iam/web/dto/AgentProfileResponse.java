package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.AgentProfile;

public record AgentProfileResponse(
        Long userId,
        int maxConcurrent,
        int openCount,
        boolean isAvailable,
        String shiftStart,
        String shiftEnd,
        String warning) {

    public static AgentProfileResponse from(AgentProfile p, String warning) {
        return new AgentProfileResponse(
                p.getUser().getId(),
                p.getMaxConcurrent(),
                p.getOpenCount(),
                p.isAvailable(),
                p.getShiftStart() == null ? null : p.getShiftStart().toString(),
                p.getShiftEnd() == null ? null : p.getShiftEnd().toString(),
                warning);
    }
}
