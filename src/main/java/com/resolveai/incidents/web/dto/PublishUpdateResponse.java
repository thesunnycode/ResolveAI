package com.resolveai.incidents.web.dto;

import java.time.OffsetDateTime;
import java.util.Map;

public record PublishUpdateResponse(Long updateId, Long incidentId, String visibility,
                                    Fanout fanout, Map<String, String> links,
                                    OffsetDateTime publishedAt) {

    public record Fanout(int total, String status) {
    }
}
