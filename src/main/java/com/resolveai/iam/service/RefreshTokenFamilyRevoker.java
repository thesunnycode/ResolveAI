package com.resolveai.iam.service;

import com.resolveai.iam.repository.RefreshTokenRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a refresh-token family in its own transaction.
 *
 * <p><b>This exists because of a bug that testing found and reading would not have.</b>
 * Reuse detection originally revoked the family and then threw {@code TOKEN_REUSE_DETECTED}
 * from the same transaction — and the exception rolled the revocation back. The response
 * said the family had been revoked as a precaution; the database said otherwise, and the
 * successor token kept working. The defence reported itself as having fired while doing
 * nothing at all, which is the worst possible failure mode for a security control.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction and commits this one on its own,
 * so the revocation survives the exception that reports it. A separate bean rather than a
 * self-invoked method because Spring's proxy does not intercept {@code this.method()} calls,
 * and a {@code @Transactional} annotation that silently does nothing is exactly the kind of
 * thing that produced the original bug.
 */
@Service
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository repository;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeNow(UUID familyId, OffsetDateTime at) {
        return repository.revokeFamily(familyId, at);
    }
}
