package com.resolveai.iam.web.dto;

import com.resolveai.iam.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code role} is validated against {@code AGENT}/{@code TEAM_LEAD} only in
 * {@link com.resolveai.iam.service.InviteService}, not here: which roles an admin may grant
 * is a business rule, not a shape constraint, and belongs where the rest of the invite
 * invariants live.
 */
public record CreateInviteRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotNull(message = "Role is required")
        Role role,

        @NotNull(message = "Team is required")
        Long teamId) {
}
