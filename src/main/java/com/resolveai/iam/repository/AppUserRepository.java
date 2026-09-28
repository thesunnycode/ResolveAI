package com.resolveai.iam.repository;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Email lookups are case-insensitive on read and normalised to lowercase on write, so the
 * two can never disagree. The unique index {@code uq_user_tenant_email} is on the raw
 * column, which only holds because nothing ever writes a mixed-case address.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByRole(Role role);

    long countByRole(Role role);

    /**
     * The Settings "Team" tab's member list: everyone but the tenant's customers.
     *
     * <p>{@code LEFT JOIN FETCH} rather than the derived {@code findByRoleInOrderByFullNameAsc}
     * it replaces: {@link com.resolveai.iam.web.dto.TeamMemberResponse#from} reads
     * {@code team.getName()} for every row, and with {@code open-in-view: false} the session
     * that loaded these users is gone by the time a lazy proxy would try to resolve it -
     * {@code LazyInitializationException}, not an N+1, since nothing forces per-row fetching
     * without this.
     */
    @Query("SELECT u FROM AppUser u LEFT JOIN FETCH u.team WHERE u.role IN :roles ORDER BY u.fullName")
    List<AppUser> findByRoleInOrderByFullNameAsc(@Param("roles") Collection<Role> roles);
}
