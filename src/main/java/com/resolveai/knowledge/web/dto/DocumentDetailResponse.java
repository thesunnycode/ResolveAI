package com.resolveai.knowledge.web.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record DocumentDetailResponse(
        Long id,
        String source,
        String title,
        String body,
        String uri,
        long kbVersion,
        boolean indexed,
        List<ChunkView> chunks,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record ChunkView(Long id, int ordinal, String text, int tokenCount,
                            int charStart, int charEnd) {
    }
}
