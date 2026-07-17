package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.PasswordResetToken;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.PasswordResetTokenRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.web.dto.RequestPasswordResetRequest;
import com.resolveai.iam.web.dto.ResetPasswordRequest;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.platform.tenant.TenantScope;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Self-service "forgot password" - the reset link is a {@link PasswordResetToken} rather than
 * an admin action, so no one is blocked on their admin being available.
 *
 * <p><b>{@link #requestReset} never reveals whether the tenant or the email exists.</b> The
 * response is identical either way, for the same reason {@code AuthService.login} runs a
 * dummy BCrypt comparison for an unknown address: a "no such account" answer here would turn
 * this endpoint into a tenant/email enumeration oracle.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final CommonPasswordChecker commonPasswords;
    private final TenantScope tenantScope;
    private final MailService mailService;
    private final MailSettings mailSettings;
    private final AuthService authService;

    public PasswordResetService(TenantRepository tenants, AppUserRepository users,
                                PasswordResetTokenRepository tokens, PasswordEncoder passwordEncoder,
                                CommonPasswordChecker commonPasswords, TenantScope tenantScope,
                                MailService mailService, MailSettings mailSettings,
                                AuthService authService) {
        this.tenants = tenants;
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.commonPasswords = commonPasswords;
        this.tenantScope = tenantScope;
        this.mailService = mailService;
        this.mailSettings = mailSettings;
        this.authService = authService;
    }

    public void requestReset(RequestPasswordResetRequest request) {
        Optional<Tenant> tenantOpt = tenants.findBySlugAndActiveTrue(request.tenantSlug());
        if (tenantOpt.isEmpty()) {
            return;
        }
        Tenant tenant = tenantOpt.get();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        tenantScope.inTenant(tenant.getId(), () -> {
            users.findByEmailIgnoreCase(email).ifPresent(user -> {
                String token = SecureTokenGenerator.generate();
                PasswordResetToken resetToken = new PasswordResetToken(tenant.getId(), user.getId(),
                        token, OffsetDateTime.now().plusHours(1));
                tokens.saveAndFlush(resetToken);

                String link = mailSettings.frontendBaseUrl() + "/reset-password?token=" + token;
                mailService.sendPasswordReset(user.getEmail(), tenant.getName(), link);
                log.info("Password reset requested for user {} in tenant {}", user.getId(),
                        tenant.getSlug());
            });
        });
    }

    public TokenResponse reset(String token, ResetPasswordRequest request) {
        PasswordResetToken resetToken = tokens.findByToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
                        "This reset link is not valid."));
        if (resetToken.isUsed()) {
            throw new ApiException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID,
                    "This reset link has already been used. Request a new one.");
        }
        if (resetToken.isExpired()) {
            throw new ApiException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED,
                    "This reset link has expired. Request a new one.");
        }
        if (commonPasswords.isCommon(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "That password appears on a list of commonly used passwords. Choose another.");
        }

        Long tenantId = resetToken.getTenantId();
        Long userId = resetToken.getUserId();
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found."));

        return tenantScope.inTenant(tenantId, () -> {
            AppUser user = users.findById(userId)
                    .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                            "The account for this reset link no longer exists."));
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            users.saveAndFlush(user);

            resetToken.markUsed();
            tokens.saveAndFlush(resetToken);

            log.info("Password reset for user {} in tenant {}", userId, tenant.getSlug());
            return authService.issueTokens(tenantId, tenant.getSlug(), userId);
        });
    }
}
