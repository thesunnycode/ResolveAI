package com.resolveai.drafting.web.dto;

import jakarta.validation.constraints.Size;

public record RequestDraftRequest(

        @Size(max = 500, message = "instruction must be at most 500 characters")
        String instruction) {
}
