package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.web.dto.RegisterRequest;
import com.resolveai.iam.web.dto.UserResponse;
import com.resolveai.platform.tenant.TenantScope;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Self-service customer registration.
 *
 * <p><b>The role is hard-coded, not read from the request.</b> {@link RegisterRequest} has
 * no role field at all, and this is the second half of that decision: even if someone adds
 * one later, the service ignores it. Agents, leads and admins are created by an admin.
 *
 * <p><b>The 409 does disclose that an email is registered with a tenant.</b> Accepted
 * deliberately: a portal that silently swallows a duplicate registration produces a worse
 * outcome - the person cannot log in and has no idea why - and the mitigation is the
 * aggressive per-IP rate limit rather than a confusing response.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CommonPasswordChecker commonPasswords;
    private final TenantScope tenantScope;

    public RegistrationService(TenantRepository tenants, AppUserRepository users,
                               PasswordEncoder passwordEncoder,
                               CommonPasswordChecker commonPasswords,
                               TenantScope tenantScope) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.commonPasswords = commonPasswords;
        this.tenantScope = tenantScope;
    }

    /**
     * <b>Not {@code @Transactional}.</b> The tenant has to be resolved before the
     * transaction opens, because Hibernate binds the tenant when the session starts - see
     * {@link TenantScope}. The write runs inside {@code tenantScope.inTenant}.
     */
    public UserResponse register(RegisterRequest request) {
        Tenant tenant = tenants.findBySlugAndActiveTrue(request.tenantSlug())
                .orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND,
                        // Shown verbatim on the sign-up form, to someone who has never
                        // heard the words "tenant" or "slug".
                        "We couldn't find a workspace called '" + request.tenantSlug()
                                + "'. Check the name with the team that sent you here."));

        if (commonPasswords.isCommon(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "That password appears on a list of commonly used passwords. Choose another.");
        }

        // Normalise before both the uniqueness check and the insert. uq_user_tenant_email is
        // on the raw column, so it only enforces uniqueness as long as nothing ever stores a
        // mixed-case address - two places, one rule, and they must not diverge.
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        return tenantScope.inTenant(tenant.getId(), () -> {
            if (users.existsByEmailIgnoreCase(email)) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
                        "An account with that email already exists for this tenant.");
            }

            AppUser user = new AppUser(
                    email,
                    passwordEncoder.encode(request.password()),
                    request.fullName().trim(),
                    Role.CUSTOMER);

            try {
                AppUser saved = users.saveAndFlush(user);
                log.info("Registered customer {} in tenant {}", saved.getId(), tenant.getSlug());
                return UserResponse.from(saved, tenant.getSlug());
            } catch (DataIntegrityViolationException e) {
                // Two simultaneous registrations for the same address: the exists-check above
                // passed for both, and the unique index rejected the loser. Mapping it here
                // rather than letting the generic handler answer keeps the response identical
                // to the sequential case.
                throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
                        "An account with that email already exists for this tenant.", e);
            }
        });
    }

    /** Used by the admin user-management endpoints in a later phase. */
    public List<String> unused() {
        return List.of();
    }
}
