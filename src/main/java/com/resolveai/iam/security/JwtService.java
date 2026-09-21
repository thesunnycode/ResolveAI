package com.resolveai.iam.security;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies access tokens.
 *
 * <p>Three details that matter more than the code around them:
 *
 * <ol>
 *   <li><b>The verification algorithm is pinned.</b> Building the parser with
 *       {@code verifyWith(SecretKey)} makes jjwt accept only the MAC algorithm that key
 *       supports; a token arriving with {@code "alg": "none"}, or signed with RSA in the hope
 *       the server will verify it using the public key as an HMAC secret, is rejected. This
 *       is the {@code alg} confusion class of attack, and it is asserted in a test rather
 *       than assumed from the library's defaults.
 *   <li><b>Issuer and audience are verified, not merely set.</b> Without that, a token minted
 *       by any other service sharing the secret is accepted here.
 *   <li><b>A short secret fails at startup, not at first login.</b> An application that boots
 *       happily and rejects everyone an hour later is much harder to diagnose than one that
 *       refuses to start with a sentence explaining why.
 * </ol>
 */
@Service
public class JwtService {

    public static final String ISSUER = "resolveai";
    public static final String AUDIENCE = "resolveai-api";

    static final String CLAIM_TENANT_ID = "tenantId";
    static final String CLAIM_TENANT_SLUG = "tenantSlug";
    static final String CLAIM_ROLE = "role";

    /** HS256 needs a 256-bit key. Anything shorter weakens the signature to its own length. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${resolveai.auth.jwt-secret}") String secret,
                      @Value("${resolveai.auth.access-token-ttl-minutes:15}") long accessTtlMinutes) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (256 bits); got "
                    + bytes.length + ". Generate one with: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String generateAccessToken(AppUser user) {
        return generateAccessToken(user.getId(), user.getTenantId(), null, user.getRole());
    }

    public String generateAccessToken(Long userId, Long tenantId, String tenantSlug, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_TENANT_SLUG, tenantSlug)
                .claim(CLAIM_ROLE, role.name())
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, expiry, issuer and audience.
     *
     * @throws TokenExpiredException when the token was valid and is not any more - the one
     *                               failure a client can act on by refreshing
     * @throws InvalidTokenException for everything else: bad signature, tampering, wrong
     *                               issuer or audience, malformed input
     */
    public Jws<Claims> parseAndValidate(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new TokenExpiredException("Access token expired", e);
        } catch (SignatureException e) {
            throw new InvalidTokenException("Token signature does not verify", e);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token is malformed or does not meet requirements", e);
        }
    }

    /** Builds the principal from an already-verified token. */
    public ResolvePrincipal toPrincipal(Jws<Claims> jws) {
        Claims c = jws.getPayload();
        return new ResolvePrincipal(
                Long.valueOf(c.getSubject()),
                c.get(CLAIM_TENANT_ID, Number.class).longValue(),
                c.get(CLAIM_TENANT_SLUG, String.class),
                Role.valueOf(c.get(CLAIM_ROLE, String.class)),
                c.getId());
    }

    /** Signals a token that was well-formed and correctly signed but has expired. */
    public static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Signals any other verification failure. Deliberately undifferentiated to the client. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
