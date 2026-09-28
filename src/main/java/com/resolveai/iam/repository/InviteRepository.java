package com.resolveai.iam.repository;

import com.resolveai.iam.domain.Invite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link Invite} is deliberately not {@code @TenantId}-scoped (see its class javadoc), so
 * unlike every other repository here, {@code tenantId} has to be an explicit parameter -
 * Hibernate will not add that predicate for us. {@link #findByToken} is the one method that
 * must stay tenant-blind: it's how {@code acceptInvite} finds out which tenant a token even
 * belongs to.
 */
public interface InviteRepository extends JpaRepository<Invite, Long> {

    Optional<Invite> findByToken(String token);

    /**
     * {@code JOIN FETCH} because {@link com.resolveai.iam.web.dto.InviteResponse#from} reads
     * {@code team.getName()} for every row, and with {@code open-in-view: false} that lazy
     * proxy has no session left to resolve against once the derived query returns.
     */
    @Query("SELECT i FROM Invite i JOIN FETCH i.team WHERE i.tenantId = :tenantId ORDER BY i.createdAt DESC")
    List<Invite> findAllByTenantIdOrderByCreatedAtDesc(@Param("tenantId") Long tenantId);
}
