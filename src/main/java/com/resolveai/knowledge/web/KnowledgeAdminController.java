package com.resolveai.knowledge.web;

import com.resolveai.common.security.IsAdmin;
import com.resolveai.knowledge.service.KnowledgeDocumentService;
import com.resolveai.knowledge.web.dto.ReindexRequest;
import com.resolveai.knowledge.web.dto.ReindexResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /knowledge/reindex} — a sibling of the document collection, not nested
 * under it, so it lives in its own controller rather than being forced under
 * {@code /documents/{id}}'s path space.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeDocumentService documents;

    public KnowledgeAdminController(KnowledgeDocumentService documents) {
        this.documents = documents;
    }

    @PostMapping("/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @IsAdmin
    public ReindexResponse reindex(@RequestBody(required = false) ReindexRequest request) {
        return documents.reindex(request != null && request.forced());
    }
}
