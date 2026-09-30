package com.resolveai.iam.web;

import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.service.AuthService;
import com.resolveai.iam.service.PasswordResetService;
import com.resolveai.iam.service.RegistrationService;
import com.resolveai.iam.web.dto.CurrentUserResponse;
import com.resolveai.iam.web.dto.LoginRequest;
import com.resolveai.iam.web.dto.RefreshRequest;
import com.resolveai.iam.web.dto.RegisterRequest;
import com.resolveai.iam.web.dto.RequestPasswordResetRequest;
import com.resolveai.iam.web.dto.ResetPasswordRequest;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.iam.web.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six auth endpoints from doc 05 section 3.1, plus self-service password reset
 * (forgot-password / reset-password) - added later, same thin-controller convention.
 *
 * <p>Thin on purpose: validation is declarative on the DTOs, authorisation is on the service
 * methods, and error mapping belongs to the Phase 3 advice. A controller that contains
 * business logic is a controller whose rules do not apply to the background workers that
 * call the same services in Phases 6 to 8.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(RegistrationService registrationService, AuthService authService,
                          PasswordResetService passwordResetService) {
        this.registrationService = registrationService;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * Always {@code 202}, always the same body, regardless of whether the workspace or email
     * exists - see {@link PasswordResetService#requestReset} for why.
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody RequestPasswordResetRequest request) {
        passwordResetService.requestReset(request);
    }

    @PostMapping("/reset-password/{token}")
    public TokenResponse resetPassword(@PathVariable String token,
                                       @Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.reset(token, request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code @AuthenticationPrincipal} resolves to the record the JWT filter placed in the
     * security context, so this needs no lookup to know who is asking - only to fill in the
     * team and profile detail the token does not carry.
     */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal ResolvePrincipal principal) {
        return authService.currentUser(principal);
    }
}
