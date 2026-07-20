package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AcceptInviteRequest(

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120, message = "Full name must be 2-120 characters")
        String fullName,

        @NotBlank(message = "Password is required")
        @Size(min = 10, max = 128, message = "Password must be 10-128 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain at least one letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit")
        String password) {
}
