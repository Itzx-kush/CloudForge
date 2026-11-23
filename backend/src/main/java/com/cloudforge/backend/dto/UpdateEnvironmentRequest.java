package com.cloudforge.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateEnvironmentRequest(
        @NotBlank(message = "Environment name is required")
        @Size(max = 64, message = "Environment name must be at most 64 characters")
        String name,

        @Size(max = 150, message = "Branch override must be at most 150 characters")
        String branchOverride,

        Map<String, String> configVariables,

        @Size(max = 500, message = "Notes must be at most 500 characters")
        String notes
) {
}
