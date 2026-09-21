package com.resolveai.iam.repository;

import com.resolveai.iam.domain.AppUser;
import com.resolveai.iam.domain.Role;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
