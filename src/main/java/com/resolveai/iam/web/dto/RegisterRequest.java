package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * <b>There is no {@code role} field, and that absence is the security control.</b>
 *
 * <p>Registration always produces a {@code CUSTOMER}. If the DTO carried a role, then
 * whether an attacker can register as an ADMIN depends on a service-layer check being
 * written and never removed. With no field to bind, mass assignment is not something to
 * defend against - it is not expressible.
 *
 * <p>Same reasoning for {@code tenantId}: the caller names a tenant by <i>slug</i>, which is
 * resolved server-side, so nobody registers into a tenant by guessing a number.
 */
public record RegisterRequest(

        @NotBlank(message = "Tenant slug is required")
        @Size(min = 2, max = 60, message = "Tenant slug must be 2-60 characters")
        String tenantSlug,

        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        /*
         * Minimum 10, not 8. NIST dropped composition rules years ago in favour of length,
         * and the common-password check in RegistrationService does more for safety than a
         * symbol requirement ever did - "P@ssw0rd!" satisfies every classic rule and is on
         * every list.
         */
        @NotBlank(message = "Password is required")
        @Size(min = 10, max = 128, message = "Password must be 10-128 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain at least one letter")
        @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120, message = "Full name must be 2-120 characters")
        String fullName) {
}
