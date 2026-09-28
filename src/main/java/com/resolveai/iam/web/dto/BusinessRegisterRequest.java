package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Creates a brand new tenant and its first (and, by design, only self-registered) user: the
 * founding {@code ADMIN}. No slug field - {@link com.resolveai.iam.service.BusinessRegistrationService}
 * derives one from {@code businessName}, because a caller who has never heard the word
 * "slug" should never have to invent one, which is exactly the confusion that made
 * {@link RegisterRequest}'s free-text tenant slug a dead end for anyone without one already.
 */
public record BusinessRegisterRequest(

        @NotBlank(message = "Business name is required")
        @Size(min = 2, max = 150, message = "Business name must be 2-150 characters")
        String businessName,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120, message = "Full name must be 2-120 characters")
        String adminFullName,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String adminEmail,

        @NotBlank(message = "Password is required")
        @Size(min = 10, max = 128, message = "Password must be 10-128 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain at least one letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit")
        String password) {
}
