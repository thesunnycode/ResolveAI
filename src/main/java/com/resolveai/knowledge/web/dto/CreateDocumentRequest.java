package com.resolveai.knowledge.web.dto;

import com.resolveai.knowledge.domain.DocumentSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(

        @NotNull(message = "source is required")
        DocumentSource source,

        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @NotBlank(message = "body is required")
        @Size(max = 50_000, message = "body must be at most 50000 characters")
        String body,

        @Size(max = 500, message = "uri must be at most 500 characters")
        String uri) {
}
