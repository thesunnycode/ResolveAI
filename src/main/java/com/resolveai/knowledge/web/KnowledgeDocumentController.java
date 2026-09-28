package com.resolveai.knowledge.web;

import com.resolveai.common.pagination.PageRequests;
import com.resolveai.common.security.IsAdmin;
import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.knowledge.service.KnowledgeBulkImportService;
import com.resolveai.knowledge.service.KnowledgeDocumentService;
import com.resolveai.knowledge.web.dto.BulkImportResponse;
import com.resolveai.knowledge.web.dto.CreateDocumentRequest;
import com.resolveai.knowledge.web.dto.DocumentDetailResponse;
import com.resolveai.knowledge.web.dto.DocumentSummaryResponse;
import com.resolveai.knowledge.web.dto.ReindexRequest;
import com.resolveai.knowledge.web.dto.ReindexResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The document management endpoints from doc 05 §3.6. {@code GET /knowledge/search} is
 * on {@link com.resolveai.knowledge.web.KnowledgeSearchController} — a different reason
 * to hit the knowledge base and a different controller for the same reason
 * {@code TriageController} is split from {@code TicketController}: different concerns,
 * same base path.
 *
 * <h2>Offset pagination here, cursor pagination on tickets</h2>
 *
 * <p>Doc 05 §4 draws the line deliberately: cursor pagination is for live queues where
 * rows leave the filtered set while an agent is paging through it. A document list is
 * small, admin-facing and slow-changing — nobody is resolving documents out from under a
 * page read — so a page count is genuinely useful here and {@code OFFSET} costs nothing
 * at this scale.
 */
@RestController
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documents;
    private final KnowledgeBulkImportService bulkImport;

    public KnowledgeDocumentController(KnowledgeDocumentService documents,
                                       KnowledgeBulkImportService bulkImport) {
        this.documents = documents;
        this.bulkImport = bulkImport;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @IsAdmin
    public DocumentSummaryResponse create(@Valid @RequestBody CreateDocumentRequest request) {
        return documents.create(request.source(), request.title(), request.body(),
                request.uri());
    }

    /**
     * CSV rows and/or whole PDFs, migrated in one batch. {@code source} only applies to
     * PDFs — a CSV row always names its own source per column.
     */
    @PostMapping("/bulk")
    @IsAdmin
    public BulkImportResponse bulkImport(@RequestParam("files") MultipartFile[] files,
                                         @RequestParam(defaultValue = "ARTICLE") DocumentSource source) {
        return bulkImport.importBatch(files, source);
    }

    @GetMapping
    @IsAgentOrAbove
    public Map<String, Object> list(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<DocumentSummaryResponse> result = documents.list(source,
                page == null ? 0 : Math.max(0, page), PageRequests.clampSize(size));
        return offsetPage(result);
    }

    @GetMapping("/{id}")
    @IsAgentOrAbove
    public DocumentDetailResponse detail(@PathVariable Long id) {
        return documents.detail(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @IsAdmin
    public void delete(@PathVariable Long id) {
        documents.delete(id);
    }


    /**
     * The offset-pagination envelope doc 05 §4 specifies for admin lists: {@code page},
     * {@code size}, {@code totalElements}, {@code totalPages}, {@code hasNext},
     * {@code hasPrevious} — deliberately not the cursor envelope
     * {@code TicketController} uses, because a page count is exactly the thing a cursor
     * cannot offer and exactly the thing this kind of list benefits from.
     */
    private static Map<String, Object> offsetPage(Page<?> page) {
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page.getNumber());
        pagination.put("size", page.getSize());
        pagination.put("totalElements", page.getTotalElements());
        pagination.put("totalPages", page.getTotalPages());
        pagination.put("hasNext", page.hasNext());
        pagination.put("hasPrevious", page.hasPrevious());
        return Map.of("data", List.copyOf(page.getContent()), "pagination", pagination);
    }
}
