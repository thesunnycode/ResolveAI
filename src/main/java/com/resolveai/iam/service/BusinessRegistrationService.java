package com.resolveai.iam.service;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.PlanTier;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.web.dto.BusinessRegisterRequest;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.platform.ai.AiPolicyService;
import com.resolveai.platform.seed.TenantBootstrapper;
import com.resolveai.platform.tenant.TenantScope;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates a brand new tenant and its founding {@code ADMIN} - the "create a business account"
 * path that replaces guessing a workspace slug at someone else's company.
 *
 * <p>A slug is derived from the business name and de-duplicated automatically
 * ({@code acme}, {@code acme-2}, ...): the caller only ever types a name, never a slug, which
 * closes off the exact {@code TENANT_NOT_FOUND} dead end {@link RegistrationService} produces
 * for a name nobody has provisioned yet.
 *
 * <p>Provisioning mirrors what {@link TenantBootstrapper} already knows a tenant needs to not
 * be silently broken (AI policy, a calendar, SLA policies) - just one default team and one
 * Admin instead of a full demo roster.
 */
@Service
public class BusinessRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(BusinessRegistrationService.class);
    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private final TenantRepository tenants;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CommonPasswordChecker commonPasswords;
    private final TenantScope tenantScope;
    private final AiPolicyService aiPolicies;
    private final TenantBootstrapper bootstrapper;
    private final AuthService authService;

    public BusinessRegistrationService(TenantRepository tenants, TeamRepository teams,
                                       AppUserRepository users, PasswordEncoder passwordEncoder,
                                       CommonPasswordChecker commonPasswords, TenantScope tenantScope,
                                       AiPolicyService aiPolicies, TenantBootstrapper bootstrapper,
                                       AuthService authService) {
        this.tenants = tenants;
        this.teams = teams;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.commonPasswords = commonPasswords;
        this.tenantScope = tenantScope;
        this.aiPolicies = aiPolicies;
        this.bootstrapper = bootstrapper;
        this.authService = authService;
    }

    public TokenResponse registerBusiness(BusinessRegisterRequest request) {
        if (commonPasswords.isCommon(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "That password appears on a list of commonly used passwords. Choose another.");
        }

        String slug = uniqueSlug(request.businessName());
        String adminEmail = request.adminEmail().trim().toLowerCase(Locale.ROOT);

        Tenant tenant = tenants.saveAndFlush(new Tenant(request.businessName().trim(), slug, PlanTier.FREE));
        aiPolicies.ensureExists(tenant.getId());

        Long userId = tenantScope.inTenant(tenant.getId(), () -> {
            Team defaultTeam = teams.saveAndFlush(new Team("General", new String[]{"OTHER"}, true));
            bootstrapper.ensureCalendar(tenant.getId(), TenantBootstrapper.CalendarSpec.officeHours());
            bootstrapper.ensureSlaPolicies(tenant.getId(), tenant.getPlanTier());

            AppUser admin = new AppUser(adminEmail, passwordEncoder.encode(request.password()),
                    request.adminFullName().trim(), Role.ADMIN);
            admin.setTeam(defaultTeam);
            AppUser saved = users.saveAndFlush(admin);
            log.info("Registered business {} ({}), admin user {}", tenant.getSlug(), tenant.getId(),
                    saved.getId());
            return saved.getId();
        });

        return authService.issueTokens(tenant.getId(), tenant.getSlug(), userId);
    }

    private String uniqueSlug(String businessName) {
        String base = NON_SLUG_CHARS.matcher(businessName.trim().toLowerCase(Locale.ROOT))
                .replaceAll("-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) {
            base = "business";
        }
        if (base.length() > 50) {
            base = base.substring(0, 50);
        }
        String candidate = base;
        int suffix = 2;
        while (tenants.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
