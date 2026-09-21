package com.resolveai.platform.ai.prompt;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Prompts, by name and version.
 *
 * <p><b>Not tenant-scoped.</b> One catalogue for the whole system: an eval run comparing
 * {@code triage@1} with {@code triage@2} has to mean the same thing across tenants, and a
 * per-tenant prompt would make "our accuracy is 84%" a statement about nothing.
 */
public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    Optional<PromptVersion> findByNameAndActiveTrue(String name);

    Optional<PromptVersion> findByNameAndVersion(String name, int version);
}
