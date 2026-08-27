package com.resolveai.knowledge.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.platform.tenant.TenantScope;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the committed knowledge corpus into <b>one tenant</b>.
 *
 * <p>Split out of {@link KnowledgeCorpusSeeder} so the local seeder and the demo seeder load
 * the corpus the same way. It also fixes the reason the corpus never arrived anywhere:
 * the seeder called {@link KnowledgeDocumentService#create} with no tenant bound, so every
 * insert was refused, and a blanket {@code catch (RuntimeException)} counted each refusal
 * as "already present". The startup log read {@code 0 created, 75 already present} over a
 * database that held none of the 75.
 *
 * <p>Two rules follow from that:
 * <ul>
 *   <li><b>Every write runs inside {@link TenantScope#inTenant}</b> - tenant first, then the
 *       transaction, the only order Hibernate's tenant binding accepts.</li>
 *   <li><b>Only a duplicate counts as a skip.</b> {@code create} reports a duplicate as
 *       {@code VALIDATION_ERROR}; anything else is a real failure and is logged and counted
 *       as one, so a broken seed is visible in the first line anyone reads.</li>
 * </ul>
 */
@Component
public class KnowledgeCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusLoader.class);

    /**
     * A copy of {@code seed/generated/knowledge.json} under {@code src/main/resources}.
     *
     * <p>Two copies, not one, and deliberately: {@code seed/generated/} is where the
     * generator writes its canonical output, and only {@code src/main/resources} is on the
     * runtime classpath. Regenerating the corpus means updating both.
     */
    static final String RESOURCE = "knowledge/corpus.json";

    public record Result(int created, int alreadyPresent, int failed) {
    }

    private final KnowledgeDocumentService documents;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public KnowledgeCorpusLoader(KnowledgeDocumentService documents, TenantScope tenantScope,
                                 ObjectMapper objectMapper) {
        this.documents = documents;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    public int corpusSize() {
        return readResource().size();
    }

    public Result loadInto(Long tenantId, String tenantSlug) {
        int created = 0;
        int alreadyPresent = 0;
        int failed = 0;
        for (Map<String, Object> doc : readResource()) {
            String title = (String) doc.get("title");
            try {
                // One transaction per document: a single bad document must not roll back
                // the other seventy-four.
                tenantScope.inTenant(tenantId, () -> documents.create(
                        DocumentSource.valueOf((String) doc.get("source")),
                        title, (String) doc.get("body"), null));
                created++;
            } catch (ApiException e) {
                if (e.errorCode() == ErrorCode.VALIDATION_ERROR) {
                    alreadyPresent++;
                } else {
                    failed++;
                    log.warn("Knowledge corpus: '{}' failed for tenant {}: {}",
                            title, tenantSlug, e.getMessage());
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Knowledge corpus: '{}' failed for tenant {}: {}",
                        title, tenantSlug, e.toString());
            }
        }
        Result result = new Result(created, alreadyPresent, failed);
        if (failed > 0) {
            log.error("Knowledge corpus for tenant {}: {} created, {} already present, "
                    + "{} FAILED - drafts for this tenant will be suppressed on topics the "
                    + "missing documents cover", tenantSlug, created, alreadyPresent, failed);
        } else {
            log.info("Knowledge corpus for tenant {}: {} created, {} already present",
                    tenantSlug, created, alreadyPresent);
        }
        return result;
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
