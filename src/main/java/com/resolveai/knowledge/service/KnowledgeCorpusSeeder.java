package com.resolveai.knowledge.service;

import com.resolveai.knowledge.domain.DocumentSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads {@code seed/CORPUS.md}'s 75 documents into the knowledge base at startup, locally.
 *
 * <p>Guarded the same three ways as {@code IamSeeder} and {@code EvalCaseSeeder} — profile,
 * property, and idempotence — for the same reason: a seeder that runs somewhere it should
 * not is only noticed once it has done something. {@link KnowledgeDocumentService#create}
 * is naturally idempotent here too, on {@code uq_kb_content}: re-running this seeder
 * against a database that already has the corpus creates nothing new, it just logs a
 * validation error per document that {@code create} turns into a no-op from this caller's
 * point of view.
 *
 * <p>Each document is queued for indexing through the ordinary outbox path — this seeder
 * does not chunk or embed anything itself. In a test that needs the corpus actually
 * searchable, {@code runtime.runAllOnce()} (or several calls, batched at 3 per
 * {@code IndexWorker} poll) has to run afterwards.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "resolveai.seed.enabled", havingValue = "true",
        matchIfMissing = true)
public class KnowledgeCorpusSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusSeeder.class);
    /**
     * A copy of {@code seed/generated/knowledge.json} under {@code src/main/resources}.
     *
     * <p>Two copies, not one, and deliberately: {@code seed/generated/} is where the
     * generator writes its canonical output (matching {@code tickets-starter.json}'s
     * location from Phase 1), and only {@code src/main/resources} is on the runtime
     * classpath — {@code eval/classification-cases.json} follows the identical pattern
     * for the same reason. Regenerating the corpus means updating both; there is no
     * build step that copies one to the other automatically, so a change to one without
     * the other is a stale-seed bug waiting to happen, not a currently-enforced
     * invariant.
     */
    private static final String RESOURCE = "knowledge/corpus.json";

    private final KnowledgeDocumentService documents;
    private final ObjectMapper objectMapper;

    @Autowired
    public KnowledgeCorpusSeeder(KnowledgeDocumentService documents, ObjectMapper objectMapper) {
        this.documents = documents;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        List<Map<String, Object>> docs = readResource();
        int created = 0;
        int skipped = 0;
        for (Map<String, Object> doc : docs) {
            try {
                documents.create(
                        DocumentSource.valueOf((String) doc.get("source")),
                        (String) doc.get("title"), (String) doc.get("body"), null);
                created++;
            } catch (RuntimeException e) {
                // ApiException.VALIDATION_ERROR on "content already exists" — the
                // ordinary idempotent-rerun case, not a real failure.
                skipped++;
            }
        }
        log.info("Knowledge corpus seed: {} created, {} already present", created, skipped);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readResource() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(in,
                    new TypeReference<Map<String, Object>>() { });
            return (List<Map<String, Object>>) root.get("documents");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
