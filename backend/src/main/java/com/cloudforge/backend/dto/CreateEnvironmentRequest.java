package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateEnvironmentRequest(
        @NotNull(message = "Environment type is required")
        EnvironmentType environmentType,

        @Size(max = 64, message = "Environment name must be at most 64 characters")
        String name,

        @Size(max = 150, message = "Branch override must be at most 150 characters")
        String branchOverride,

        Map<String, String> configVariables,

        @Size(max = 500, message = "Notes must be at most 500 characters")
        String notes
) {
}
