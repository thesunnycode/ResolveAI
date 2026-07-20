package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RequestPasswordResetRequest(

        @NotBlank(message = "Workspace is required")
        String tenantSlug,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        String email) {
}
