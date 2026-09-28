package com.resolveai.iam.repository;

import com.resolveai.iam.domain.Tenant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The one repository that is not tenant-filtered, because it is what resolves a tenant in
 * the first place. Login looks a tenant up by slug before any tenant context exists.
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlugAndActiveTrue(String slug);

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** The public "pick your business" list on the login/register pages. */
    List<Tenant> findAllByActiveTrueOrderByNameAsc();
}
