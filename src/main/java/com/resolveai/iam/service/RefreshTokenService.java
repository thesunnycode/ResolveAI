package com.resolveai.iam.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.RefreshToken;
import com.resolveai.iam.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p><b>Rotation without reuse detection is half a defence</b>, and the detection is the
 * part worth explaining. Every token issued from one login shares a {@code familyId}. A
 * refresh token is single-use: exchanging it sets {@code used_at} and mints a successor in
 * the same family. Presenting a token whose {@code used_at} is already set means the value
 * leaked - either an attacker replayed a stolen token, or the legitimate client replayed its
 * own. <b>The system cannot tell which of the two is the attacker</b>, so it revokes the
 * entire family and forces a fresh login. Logging both out is the only choice that is safe
 * in both readings.
 *
 * <p><b>Only {@code SHA-256(raw)} is stored.</b> A database dump therefore contains no
 * usable credentials. SHA-256 rather than BCrypt is correct and the distinction matters: the
 * token is 256 bits of {@code SecureRandom}, not a human-chosen password, so there is no
 * dictionary for a slow hash to defend against - and every refresh would otherwise pay for
 * deliberate slowness.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits. Enough that guessing is not a threat model. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final SecureRandom random = new SecureRandom();
    private final int refreshTtlDays;

    public RefreshTokenService(RefreshTokenRepository repository,
                               RefreshTokenFamilyRevoker familyRevoker,
                               @Value("${resolveai.auth.refresh-token-ttl-days:7}") int refreshTtlDays) {
        this.repository = repository;
        this.familyRevoker = familyRevoker;
        this.refreshTtlDays = refreshTtlDays;
    }

    /** The raw token and the family it belongs to. The raw value is never persisted. */
    public record IssuedToken(String rawToken, UUID familyId) {
    }

    /** Result of a successful rotation. */
    public record RotatedToken(AppUser user, String rawToken, UUID familyId) {
    }

    @Transactional
    public IssuedToken issue(AppUser user, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken token = new RefreshToken(
                user, sha256Hex(raw), familyId,
                OffsetDateTime.now().plusDays(refreshTtlDays));
        repository.save(token);

        return new IssuedToken(raw, familyId);
    }

    /**
     * The tenant that owns a refresh token, resolvable without a tenant context.
     *
     * <p>Called before the rotation transaction opens: rotating touches {@code AppUser},
     * which is tenant-filtered, and a refresh request carries no principal to derive the
     * tenant from.
     */
    public java.util.Optional<Long> tenantIdFor(String rawToken) {
        return repository.findTenantIdByTokenHash(sha256Hex(rawToken));
    }

    /** A brand-new family. Called on login, never on rotation. */
    @Transactional
    public IssuedToken issueNewFamily(AppUser user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for a successor.
     *
     * <p><b>{@code REQUIRES_NEW} with {@code SERIALIZABLE}-like intent, achieved by taking a
     * row lock:</b> the whole method runs in one transaction so that two concurrent refreshes
     * of the same token cannot both pass the {@code used_at IS NULL} check. Without that, a
     * double-click would mint two valid successors and the single-use property - on which
     * reuse detection entirely depends - would be a suggestion rather than a fact.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RotatedToken rotate(String rawToken) {
        String hash = sha256Hex(rawToken);
        OffsetDateTime now = OffsetDateTime.now();

        RefreshToken token = repository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN,
                        "Refresh token is not recognised. Sign in again."));

        if (token.isRevoked()) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN,
                    "This refresh token has been revoked. Sign in again.");
        }

        if (token.isExpired(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN,
                    "This refresh token has expired. Sign in again.");
        }

        // ── the reuse case ───────────────────────────────────────────────────
        if (token.isUsed()) {
            // In its own transaction, so the revocation commits before the exception below
            // rolls this one back. See RefreshTokenFamilyRevoker.
            int revoked = familyRevoker.revokeNow(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {} — revoked {} tokens in family {}",
                    token.getUser().getId(), revoked, token.getFamilyId());
            throw new ApiException(ErrorCode.TOKEN_REUSE_DETECTED,
                    "This refresh token was already used. Every session from that sign-in has "
                    + "been revoked as a precaution. Sign in again.");
        }

        token.markUsed(now);
        repository.saveAndFlush(token);

        IssuedToken successor = issue(token.getUser(), token.getFamilyId());
        return new RotatedToken(token.getUser(), successor.rawToken(), successor.familyId());
    }

    /** Logout. Revokes every token descended from the same sign-in. */
    @Transactional
    public int revokeFamilyOf(String rawToken) {
        return repository.findByTokenHash(sha256Hex(rawToken))
                .map(t -> familyRevoker.revokeNow(t.getFamilyId(), OffsetDateTime.now()))
                // Revoking an unknown token is a no-op, not an error: logout must be
                // idempotent, and telling a caller that a token does not exist is free
                // information about which tokens do.
                .orElse(0);
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
