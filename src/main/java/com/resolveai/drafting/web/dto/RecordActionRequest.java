package com.resolveai.drafting.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param action    SENT_AS_IS | EDITED | DISCARDED
 * @param finalText required when action is EDITED -- what was actually sent. Ignored for
 *                  the other two actions rather than rejected if present, since a client
 *                  sending it harmlessly alongside DISCARDED is not worth a 400 over.
 */
public record RecordActionRequest(

        @NotBlank(message = "action is required")
        @Pattern(regexp = "SENT_AS_IS|EDITED|DISCARDED",
                message = "action must be one of SENT_AS_IS, EDITED, DISCARDED")
        String action,

        String finalText) {
}
