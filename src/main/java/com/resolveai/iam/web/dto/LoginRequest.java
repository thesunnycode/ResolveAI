package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * No {@code @Email} or {@code @Size} here, unlike registration.
 *
 * <p>Validating the shape of a login attempt would answer a question the attacker asked:
 * a 400 for "not an email address" and a 401 for "wrong credentials" are distinguishable,
 * and the difference is free information. Everything that is not a correct credential pair
 * gets the same 401.
 */
public record LoginRequest(
        @NotBlank(message = "Tenant slug is required") String tenantSlug,
        @NotBlank(message = "Email is required") String email,
        @NotBlank(message = "Password is required") String password) {
}
