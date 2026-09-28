package com.resolveai.knowledge.web.dto;

import java.util.List;

public record BulkImportResponse(int imported, List<SkippedItemResponse> skipped) {
}
