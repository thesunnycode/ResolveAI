package com.resolveai.knowledge.web.dto;

import java.time.OffsetDateTime;

public record DocumentSummaryResponse(
        Long id,
        String source,
        String title,
        String uri,
        boolean indexed,
        long chunkCount,
        long kbVersion,
        OffsetDateTime createdAt) {
}
