package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.security.JwtService;
import com.resolveai.iam.security.PermissionResolver;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.security.ResolveUserDetails;
import com.resolveai.iam.security.TenantAwareUserDetailsService;
import com.resolveai.iam.web.dto.CurrentUserResponse;
import com.resolveai.iam.web.dto.LoginRequest;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.iam.web.dto.UserResponse;
import com.resolveai.platform.tenant.TenantScope;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, logout, and the current-identity endpoint.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a value nobody knows, compared against when the user does not
     * exist.
     *
     * <p><b>This is the timing side channel closed.</b> Without it, a request for an unknown
     * address returns as soon as the lookup misses - a few milliseconds - while a request
     * for a known address pays for a strength-12 BCrypt comparison, around 250ms. That
     * difference is trivially measurable over the network and turns login into a user
     * enumeration oracle, regardless of the response bodies being identical.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.iVDJGhJmpDJX1Kd6M3hvJvZWxKP7d2S";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final AgentProfileRepository agentProfiles;
    private final TenantAwareUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final PermissionResolver permissions;
    private final TenantScope tenantScope;

    public AuthService(TenantRepository tenants, AppUserRepository users,
                       AgentProfileRepository agentProfiles,
                       TenantAwareUserDetailsService userDetailsService,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenService refreshTokens, PermissionResolver permissions,
                       TenantScope tenantScope) {
        this.tenants = tenants;
        this.users = users;
        this.agentProfiles = agentProfiles;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.permissions = permissions;
        this.tenantScope = tenantScope;
    }

    /**
     * Exchanges credentials for a token pair.
     *
     * <p><b>One 401 covers three different failures</b> - unknown tenant, unknown email,
     * wrong password - and the BCrypt comparison runs in all of them. Distinguishing any of
     * the three, in the status code, the error code, the message or the response time, tells
     * an attacker which addresses are worth a password list.
     */
    public TokenResponse login(LoginRequest request) {
        Optional<ResolveUserDetails> loaded =
                userDetailsService.loadUser(request.tenantSlug(), request.email());

        String hash = loaded.map(ResolveUserDetails::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (loaded.isEmpty() || !passwordMatches) {
            log.debug("Failed login for {}@{}", request.email(), request.tenantSlug());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    "Email or password is incorrect.");
        }

        ResolveUserDetails details = loaded.get();

        // The one case that is legitimately distinguishable: the credentials were right.
        // Telling a person with a correct password that their account is disabled leaks
        // nothing they did not already prove they knew.
        if (!details.isEnabled()) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED,
                    "This account has been deactivated. Contact your administrator.");
        }

        return issueTokens(details.getTenantId(), details.getTenantSlug(), details.getUserId());
    }

    /**
     * Signs a user in <b>without a password</b> - used only by the demo module, for the
     * one-click "Explore as ..." buttons on a demo tenant whose accounts are public by
     * design. Same token pair, same refresh family, same audit line as a real login, so a
     * demo session is indistinguishable downstream; the caller is responsible for making
     * sure the user belongs to the demo tenant.
     */
    public TokenResponse issueDemoTokens(Long tenantId, String tenantSlug, Long userId) {
        log.info("Demo login for user {} in tenant {}", userId, tenantSlug);
        return issueTokens(tenantId, tenantSlug, userId);
    }

    /**
     * Package-visible for {@link BusinessRegistrationService} and {@link InviteService}: both
     * create an {@code AppUser} outside of login and need the same token pair immediately
     * afterwards, so the new account is signed in rather than sent back to a login form it
     * has no password history with.
     */
    TokenResponse issueTokens(Long tenantId, String tenantSlug, Long userId) {
        return tenantScope.inTenant(tenantId, () -> {
            AppUser user = users.findById(userId).orElseThrow(
                    () -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                            "Email or password is incorrect."));
            user.setLastLoginAt(OffsetDateTime.now());

            var refresh = refreshTokens.issueNewFamily(user);
            String access = jwtService.generateAccessToken(
                    user.getId(), user.getTenantId(), tenantSlug, user.getRole());

            log.info("Login: user {} ({}) in tenant {}", user.getId(), user.getRole(), tenantSlug);

            return TokenResponse.of(access, refresh.rawToken(),
                    jwtService.accessTtl().toSeconds(),
                    UserResponse.from(user, tenantSlug));
        });
    }

    /**
     * Rotation. Same response shape as login, deliberately, so a client has one parser.
     *
     * <p>The tenant is looked up from the token hash <i>before</i> the transaction opens,
     * because rotating loads the token's {@code AppUser} and that entity is tenant-filtered.
     * A refresh arrives with no principal, so there is nothing else to derive it from.
     */
    public TokenResponse refresh(String rawRefreshToken) {
        Long tenantId = refreshTokens.tenantIdFor(rawRefreshToken)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN,
                        "Refresh token is not recognised. Sign in again."));

        var outcome = tenantScope.inTenant(tenantId, () -> {
            var result = refreshTokens.rotate(rawRefreshToken);
            if (result instanceof RefreshTokenService.RotationResult.Rotated rotated) {
                AppUser user = rotated.user();
                String slug = tenants.findById(user.getTenantId())
                        .map(Tenant::getSlug).orElse(null);
                String access = jwtService.generateAccessToken(
                        user.getId(), user.getTenantId(), slug, user.getRole());
                return (Object) TokenResponse.of(access, rotated.rawToken(),
                        jwtService.accessTtl().toSeconds(), UserResponse.from(user, slug));
            }
            return (Object) result;
        });

        // Thrown out here, after the transaction has committed. Throwing inside it would
        // roll back the family revocation that the reuse branch just performed.
        if (outcome instanceof RefreshTokenService.RotationResult.Rejected rejected) {
            throw new ApiException(rejected.errorCode(), rejected.detail());
        }
        return (TokenResponse) outcome;
    }

    /**
     * Logout.
     *
     * <p><b>This does not invalidate the access token</b>, which is stateless and stays valid
     * until it expires - at most 15 minutes. The fix is a Redis deny-list keyed on {@code jti},
     * and it is deliberately deferred: it puts a Redis read in front of every single request
     * to close a window that is already short. The trade-off is stated in the README because
     * "JWTs cannot be revoked instantly" is a question worth being able to answer rather than
     * a gap worth hiding.
     */
    public void logout(String rawRefreshToken) {
        // No tenant needed: the family revocation touches only refresh_token, which has no
        // tenant_id column and is keyed by an unguessable hash.
        int revoked = refreshTokens.revokeFamilyOf(rawRefreshToken);
        log.debug("Logout revoked {} refresh tokens", revoked);
    }

    public CurrentUserResponse currentUser(ResolvePrincipal principal) {
        return tenantScope.inTenantReadOnly(principal.tenantId(), () -> {
            AppUser user = users.findById(principal.userId())
                    .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND,
                            "The authenticated user no longer exists."));

            Tenant tenant = tenants.findById(user.getTenantId()).orElseThrow(
                    () -> new ApiException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found."));

            var profile = agentProfiles.findByUserId(user.getId())
                    .map(AuthService::toSummary)
                    .orElse(null);

            return new CurrentUserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getRole(),
                    user.getTeam() == null ? null : user.getTeam().getId(),
                    user.getTeam() == null ? null : user.getTeam().getName(),
                    tenant.getId(),
                    tenant.getSlug(),
                    tenant.getPlanTier(),
                    permissions.permissionsFor(user.getRole()),
                    profile);
        });
    }

    private static CurrentUserResponse.AgentProfileSummary toSummary(AgentProfile p) {
        return new CurrentUserResponse.AgentProfileSummary(
                p.getMaxConcurrent(),
                p.getOpenCount(),
                p.isAvailable(),
                p.getShiftStart() == null ? null : p.getShiftStart().toString(),
                p.getShiftEnd() == null ? null : p.getShiftEnd().toString());
    }
}
