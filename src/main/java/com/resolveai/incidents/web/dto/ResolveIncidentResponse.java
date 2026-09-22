package com.resolveai.incidents.web.dto;

import java.util.List;

public record ResolveIncidentResponse(Long id, String status, int resolvedTicketCount,
                                      List<Long> skipped, String etag) {
}
