package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AgentProfile;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Invite;
import com.resolveai.iam.domain.Role;
import com.resolveai.iam.domain.Team;
import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.AgentProfileRepository;
import com.resolveai.iam.repository.AppUserRepository;
import com.resolveai.iam.repository.InviteRepository;
import com.resolveai.iam.repository.TeamRepository;
import com.resolveai.iam.repository.TenantRepository;
import com.resolveai.iam.security.ResolvePrincipal;
import com.resolveai.iam.web.dto.AcceptInviteRequest;
import com.resolveai.iam.web.dto.CreateInviteRequest;
import com.resolveai.iam.web.dto.InvitePreviewResponse;
import com.resolveai.iam.web.dto.InviteResponse;
import com.resolveai.iam.web.dto.TokenResponse;
import com.resolveai.platform.tenant.TenantScope;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Brings an Agent or Team Lead into a tenant by email - the only way to create either role
 * outside local dev seeding. See {@link Invite}'s class javadoc for why the entity opts out
 * of {@code @TenantId} and what that means for every method here: {@link #createInvite} and
 * {@link #listInvites} take {@code tenantId} explicitly from the caller's own token, and
 * {@link #acceptInvite} resolves it from the token row itself before any tenant context
 * exists at all.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int DEFAULT_AGENT_CAPACITY = 15;

    private final InviteRepository invites;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final AgentProfileRepository agentProfiles;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final CommonPasswordChecker commonPasswords;
    private final TenantScope tenantScope;
    private final MailService mailService;
    private final MailSettings mailSettings;
    private final AuthService authService;

    public InviteService(InviteRepository invites, TeamRepository teams, AppUserRepository users,
                         AgentProfileRepository agentProfiles, TenantRepository tenants,
                         PasswordEncoder passwordEncoder, CommonPasswordChecker commonPasswords,
                         TenantScope tenantScope, MailService mailService, MailSettings mailSettings,
                         AuthService authService) {
        this.invites = invites;
        this.teams = teams;
        this.users = users;
        this.agentProfiles = agentProfiles;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.commonPasswords = commonPasswords;
        this.tenantScope = tenantScope;
        this.mailService = mailService;
        this.mailSettings = mailSettings;
        this.authService = authService;
    }

    /** {@code @IsAdmin}, enforced by the controller. Only AGENT/TEAM_LEAD may be invited. */
    public InviteResponse createInvite(ResolvePrincipal principal, CreateInviteRequest request) {
        if (request.role() != Role.AGENT && request.role() != Role.TEAM_LEAD) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Only Agent or Team Lead accounts can be invited.");
        }
        Team team = teams.findById(request.teamId())
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "That team does not exist."));

        Tenant tenant = tenants.findById(principal.tenantId())
                .orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found."));

        String token = generateToken();
        Invite invite = new Invite(principal.tenantId(), request.email().trim().toLowerCase(Locale.ROOT),
                request.role(), team, principal.userId(), token, OffsetDateTime.now().plusDays(7));
        Invite saved = invites.saveAndFlush(invite);

        String link = mailSettings.frontendBaseUrl() + "/invite/accept?token=" + token;
        mailService.sendInvite(invite.getEmail(), tenant.getName(), invite.getRole(), link);
        log.info("Invite {} created for {} ({}) in tenant {}", saved.getId(), invite.getEmail(),
                invite.getRole(), principal.tenantSlug());
        return InviteResponse.from(saved);
    }

    /** {@code @IsAdmin}, enforced by the controller. */
    public List<InviteResponse> listInvites(ResolvePrincipal principal) {
        return invites.findAllByTenantIdOrderByCreatedAtDesc(principal.tenantId()).stream()
                .map(InviteResponse::from)
                .toList();
    }

    /** Public: what the accept page shows before anyone has typed anything. */
    public InvitePreviewResponse previewInvite(String token) {
        Invite invite = findValidInvite(token);
        Tenant tenant = tenants.findById(invite.getTenantId())
                .orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found."));
        return new InvitePreviewResponse(tenant.getName(), invite.getEmail(), invite.getRole());
    }

    /** Public: creates the account and signs it straight in, same as business self-signup. */
    public TokenResponse acceptInvite(String token, AcceptInviteRequest request) {
        Invite invite = findValidInvite(token);
        if (commonPasswords.isCommon(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "That password appears on a list of commonly used passwords. Choose another.");
        }

        Long tenantId = invite.getTenantId();
        Long teamId = invite.getTeam().getId();
        Role role = invite.getRole();
        String email = invite.getEmail();
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found."));

        Long userId = tenantScope.inTenant(tenantId, () -> {
            if (users.existsByEmailIgnoreCase(email)) {
                throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS,
                        "An account with that email already exists for this tenant.");
            }
            Team team = teams.findById(teamId)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                            "This invite's team no longer exists. Ask an admin to re-invite you."));

            AppUser user = new AppUser(email, passwordEncoder.encode(request.password()),
                    request.fullName().trim(), role);
            user.setTeam(team);
            AppUser saved = users.saveAndFlush(user);

            if (role == Role.AGENT) {
                AgentProfile profile = new AgentProfile(saved, DEFAULT_AGENT_CAPACITY);
                profile.setShift(LocalTime.of(9, 0), LocalTime.of(18, 0));
                agentProfiles.save(profile);
            }

            invite.markAccepted();
            invites.saveAndFlush(invite);
            log.info("Invite {} accepted: user {} ({}) in tenant {}", invite.getId(), saved.getId(),
                    role, tenantId);
            return saved.getId();
        });

        return authService.issueTokens(tenantId, tenant.getSlug(), userId);
    }

    private Invite findValidInvite(String token) {
        Invite invite = invites.findByToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_NOT_FOUND, "This invite link is not valid."));
        if (invite.isAccepted()) {
            throw new ApiException(ErrorCode.INVITE_ALREADY_ACCEPTED,
                    "This invite has already been used. Sign in instead.");
        }
        if (invite.isExpired()) {
            throw new ApiException(ErrorCode.INVITE_EXPIRED,
                    "This invite has expired. Ask an admin to send a new one.");
        }
        return invite;
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
