package com.resolveai.iam.repository;

import com.resolveai.iam.domain.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link PasswordResetToken} is deliberately not {@code @TenantId}-scoped (see its class
 * javadoc), so {@code findByToken} is tenant-blind on purpose - it's how the reset-password
 * page finds out which tenant a token even belongs to.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);
}
