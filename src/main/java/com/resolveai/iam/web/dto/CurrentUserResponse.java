package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Role;
import java.util.List;

/**
 * {@code GET /auth/me}.
 *
 * @param permissions  for hiding controls the user cannot use. <b>The server remains the
 *                     only authority</b>; forging one of these strings gains a visible
 *                     button whose endpoint still returns 403
 * @param agentProfile present only for users who have one, so a customer's response is
 *                     strictly smaller rather than full of nulls
 */
public record CurrentUserResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        Long teamId,
        String teamName,
        Long tenantId,
        String tenantSlug,
        PlanTier planTier,
        List<String> permissions,
        AgentProfileSummary agentProfile) {

    public record AgentProfileSummary(
            int maxConcurrent,
            int openCount,
            boolean isAvailable,
            String shiftStart,
            String shiftEnd) {
    }
}
