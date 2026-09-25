package com.resolveai.knowledge.service;

import com.resolveai.iam.domain.Tenant;
import com.resolveai.iam.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads {@code seed/CORPUS.md}'s 75 documents into every active tenant's knowledge base at
 * startup, locally.
 *
 * <p>Guarded the same three ways as {@code IamSeeder} and {@code EvalCaseSeeder} — profile,
 * property, and idempotence — for the same reason: a seeder that runs somewhere it should
 * not is only noticed once it has done something. Idempotence comes from
 * {@code uq_kb_content}: re-running against a tenant that already has the corpus creates
 * nothing and reports every document as already present.
 *
 * <p><b>{@code @Order(20)}, after {@code IamSeeder}'s 10.</b> The knowledge base is
 * tenant-scoped, so on an empty database there is nothing to load into until the tenants
 * exist. The loading itself — including the tenant binding this class used to get wrong —
 * is {@link KnowledgeCorpusLoader}'s.
 *
 * <p>Each document is queued for indexing through the ordinary outbox path — nothing here
 * chunks or embeds. In a test that needs the corpus actually searchable,
 * {@code runtime.runAllOnce()} (or several calls, batched at 3 per {@code IndexWorker}
 * poll) has to run afterwards.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "resolveai.seed.enabled", havingValue = "true",
        matchIfMissing = true)
@Order(20)
public class KnowledgeCorpusSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusSeeder.class);

    private final KnowledgeCorpusLoader loader;
    private final TenantRepository tenants;

    public KnowledgeCorpusSeeder(KnowledgeCorpusLoader loader, TenantRepository tenants) {
        this.loader = loader;
        this.tenants = tenants;
    }

    @Override
    public void run(String... args) {
        int failed = 0;
        for (Tenant tenant : tenants.findAll()) {
            if (tenant.isActive()) {
                failed += loader.loadInto(tenant.getId(), tenant.getSlug()).failed();
            }
        }
        if (failed > 0) {
            log.error("Knowledge corpus seed finished with {} failures — see the warnings above",
                    failed);
        }
    }
}
