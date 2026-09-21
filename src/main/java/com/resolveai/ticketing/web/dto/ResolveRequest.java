package com.resolveai.ticketing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param resolution what fixed it. Stored as a final {@code PUBLIC} message rather than a
 *                   column on the ticket: it is something an agent wrote to a customer, it
 *                   belongs in the thread they will read, and Phase 7 indexes the thread for
 *                   the knowledge base. A separate column would have to be indexed
 *                   separately and displayed separately for no gain.
 */
public record ResolveRequest(

        @NotBlank(message = "A resolution is required")
        @Size(max = 20_000, message = "Resolution must be 1-20000 characters")
        String resolution) {
}
