package com.resolveai.knowledge;

import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.knowledge.service.KnowledgeDocumentService;
import com.resolveai.platform.outbox.WorkerRuntime;
import com.resolveai.platform.tenant.TenantScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixtures for the knowledge and drafting tests: creating a document and driving it all
 * the way through indexing, without waiting on the scheduler.
 */
@Component
public class KnowledgeTestSupport {

    @Autowired KnowledgeDocumentService documents;
    @Autowired WorkerRuntime runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantScope tenantScope;

    /** Creates a document and indexes it synchronously (one poll of {@code IndexWorker}). */
    public Long createIndexed(Long tenantId, DocumentSource source, String title, String body) {
        Long id = tenantScope.inTenant(tenantId,
                () -> documents.create(source, title, body, null).id());
        runIndexingUntilSettled();
        return id;
    }

    /** Creates without indexing — for tests of the pending/reindex machinery itself. */
    public Long createUnindexed(Long tenantId, DocumentSource source, String title, String body) {
        return tenantScope.inTenant(tenantId,
                () -> documents.create(source, title, body, null).id());
    }

    /**
     * Runs the index worker until nothing is left to process, bounded against a runaway
     * loop.
     *
     * <p>200 iterations at {@code IndexWorker.batchSize() == 3} covers up to 600
     * documents — comfortably above the 75-document corpus the retrieval eval seeds, and
     * high enough that the bound is a safety net rather than something a normal test can
     * silently run into. Its first cut, 20, was exactly that: it capped out at 60
     * documents and quietly left the last 15 of a 75-document corpus unindexed with no
     * error, which read as four unrelated "case names no chunks" warnings until traced
     * back to this loop terminating early.
     */
    public void runIndexingUntilSettled() {
        for (int i = 0; i < 200; i++) {
            int handled = runtime.workers().stream()
                    .filter(w -> w.name().equals("IndexWorker"))
                    .mapToInt(runtime::runOnce)
                    .sum();
            if (handled == 0) {
                return;
            }
        }
    }

    public int chunkCount(Long documentId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_chunk WHERE document_id = ?",
                Integer.class, documentId);
        return count == null ? 0 : count;
    }
}
