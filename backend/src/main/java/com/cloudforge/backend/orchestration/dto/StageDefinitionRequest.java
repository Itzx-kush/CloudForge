package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.secrets.dto.StageSecretBindingInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record StageDefinitionRequest(
        @NotBlank(message = "Stage key is required")
        @Size(min = 2, max = 64, message = "Stage key must be between 2 and 64 characters")
        String stageKey,

        @NotBlank(message = "Stage display name is required")
        @Size(min = 2, max = 120, message = "Stage display name must be between 2 and 120 characters")
        String displayName,

        @NotNull(message = "Stage type is required")
        PipelineStageType stageType,

        List<String> dependsOn,

        @Min(value = 30, message = "Timeout must be at least 30 seconds")
        @Max(value = 7200, message = "Timeout must be at most 7200 seconds")
        Integer timeoutSeconds,

        @Min(value = 0, message = "Max retries cannot be negative")
        @Max(value = 5, message = "Max retries cannot exceed 5")
        Integer maxRetries,

        Boolean allowFailure,

        Map<String, String> metadata,

        @Valid
        List<StageSecretBindingInput> secretBindings
) {
    public StageDefinitionRequest(
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            List<String> dependsOn,
            Integer timeoutSeconds,
            Integer maxRetries,
            Boolean allowFailure,
            Map<String, String> metadata
    ) {
        this(stageKey, displayName, stageType, dependsOn, timeoutSeconds, maxRetries, allowFailure, metadata, List.of());
    }
}
