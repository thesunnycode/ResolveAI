package com.resolveai.knowledge.web;

import com.resolveai.common.security.IsAgentOrAbove;
import com.resolveai.knowledge.service.KnowledgeSearchService;
import com.resolveai.knowledge.web.dto.SearchResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /knowledge/search} — doc 05 §3.6. Hybrid retrieval, used by the agent
 * drafting panel and, with {@code explain=true}, as the retrieval debugging tool the
 * class comment on {@code KnowledgeSearchService} explains.
 */
@RestController
@RequestMapping("/api/v1/knowledge/search")
@Validated
public class KnowledgeSearchController {

    private final KnowledgeSearchService search;

    public KnowledgeSearchController(KnowledgeSearchService search) {
        this.search = search;
    }

    @GetMapping
    @IsAgentOrAbove
    public ResponseEntity<SearchResponse> search(
            @RequestParam @NotBlank @Size(min = 1, max = 500) String q,
            @RequestParam(required = false) Integer k,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "false") boolean explain) {
        int size = k == null ? 10 : Math.max(1, Math.min(50, k));
        SearchResponse response = search.search(q, size, source, explain);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }
}
