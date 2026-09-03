package com.resolveai.drafting.web.dto;

import java.util.Map;

public record DraftAcceptedResponse(Long draftId, String status, Map<String, String> links,
                                    int estimatedSeconds) {
}
