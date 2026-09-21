package com.resolveai.iam.repository;

import com.resolveai.iam.domain.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note what is absent: no method takes a {@code tenantId}. Hibernate's {@code @TenantId}
 * discriminator adds the predicate to every query, so a {@code findByTenantId} would be
 * both redundant and misleading - it would suggest the filtering is the caller's job.
 */
public interface TeamRepository extends JpaRepository<Team, Long> {

    Optional<Team> findByIsDefaultTrue();

    Optional<Team> findByName(String name);

    List<Team> findAllByOrderByNameAsc();
}
