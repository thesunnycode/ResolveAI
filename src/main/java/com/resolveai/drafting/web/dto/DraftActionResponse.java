package com.resolveai.drafting.web.dto;

import java.time.OffsetDateTime;

public record DraftActionResponse(Long draftId, String action, Integer editDistance,
                                  OffsetDateTime createdAt) {
}
