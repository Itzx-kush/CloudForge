package com.cloudforge.backend.orchestration.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreatePipelineDefinitionRequest(
        @NotNull(message = "Project ID is required")
        UUID projectId,

        @NotBlank(message = "Pipeline name is required")
        @Size(min = 2, max = 120, message = "Pipeline name must be between 2 and 120 characters")
        String name,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        UUID linkedRepositoryId,

        UUID targetEnvironmentId,

        Boolean enabled,

        @NotEmpty(message = "At least one pipeline stage is required")
        @Valid
        List<StageDefinitionRequest> stages
) {
    public CreatePipelineDefinitionRequest(
            UUID projectId,
            String name,
            String description,
            UUID linkedRepositoryId,
            Boolean enabled,
            List<StageDefinitionRequest> stages
    ) {
        this(projectId, name, description, linkedRepositoryId, null, enabled, stages);
    }
}
