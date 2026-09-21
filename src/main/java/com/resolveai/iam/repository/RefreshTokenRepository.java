package com.resolveai.iam.repository;

import com.resolveai.iam.domain.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The same lookup, holding a row lock until the transaction ends.
     *
     * <p><b>Without this, single use is not actually enforced.</b> Checking
     * {@code used_at IS NULL} and then setting it is a read-modify-write, and eight
     * concurrent refreshes of one token all read {@code null} and all rotate. Measured:
     * three of eight succeeded. Reuse detection is built entirely on a token being
     * exchangeable exactly once, so without the lock the defence rests on a race.
     *
     * <p>{@code SELECT ... FOR UPDATE} makes the losers block and then see {@code used_at}
     * already set - which is the reuse path, and the correct answer for them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    /**
     * The tenant that owns a refresh token, found without needing a tenant context.
     *
     * <p>A chicken-and-egg problem, and one of the few places a <b>native</b> query is the
     * right answer. Refreshing a token is a public endpoint: there is no principal, so no
     * tenant, so {@code AppUser} - which is {@code @TenantId}-filtered - cannot be loaded.
     * But the tenant is exactly what we are trying to find out.
     *
     * <p>Native SQL bypasses the discriminator, which is normally the hazard this design
     * warns about. It is safe here precisely because the lookup key is a SHA-256 of 256 bits
     * of {@code SecureRandom}: possessing it is the authorisation. Nothing is enumerable and
     * nothing is guessable, so there is no cross-tenant read to protect against.
     */
    @Query(value = """
            SELECT u.tenant_id
              FROM refresh_token rt
              JOIN app_user u ON u.id = rt.user_id
             WHERE rt.token_hash = :hash
            """, nativeQuery = true)
    Optional<Long> findTenantIdByTokenHash(@Param("hash") String hash);

    /**
     * Revokes every live token descended from one login.
     *
     * <p>Written as a bulk update rather than a load-and-loop on purpose: it is called on
     * the reuse-detection path, where the whole point is to close the window as fast as
     * possible, and a family can contain a week's worth of rotations.
     *
     * <p>{@code clearAutomatically} matters here. A bulk JPQL update bypasses the persistence
     * context, so a {@code RefreshToken} already loaded in this transaction would still
     * report {@code revokedAt == null} afterwards - and the very next check in
     * {@code rotate()} would read that stale entity and conclude the token is fine.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :at
             WHERE t.familyId = :familyId
               AND t.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") OffsetDateTime at);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") OffsetDateTime before);

    long countByFamilyIdAndRevokedAtIsNull(UUID familyId);
}
