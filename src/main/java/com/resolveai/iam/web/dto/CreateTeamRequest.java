package com.resolveai.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(

        @NotBlank(message = "Team name is required")
        @Size(min = 2, max = 80, message = "Team name must be 2-80 characters")
        String name,

        @NotEmpty(message = "At least one skill is required")
        String[] skills) {
}
