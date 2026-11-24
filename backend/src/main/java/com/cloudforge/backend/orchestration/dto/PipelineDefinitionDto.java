package com.cloudforge.backend.orchestration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineDefinitionDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID linkedRepositoryId,
        String repositoryFullName,
        String name,
        String slug,
        String description,
        boolean enabled,
        int definitionVersion,
        Long rowVersion,
        String createdByUsername,
        String updatedByUsername,
        List<PipelineStageDefinitionDto> stages,
        Instant createdAt,
        Instant updatedAt
) {
}
