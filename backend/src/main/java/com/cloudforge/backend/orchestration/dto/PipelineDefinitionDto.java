package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.entity.EnvironmentType;

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
        UUID targetEnvironmentId,
        String targetEnvironmentName,
        EnvironmentType targetEnvironmentType,
        boolean hasSecretBindings,
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
    public PipelineDefinitionDto(
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
        this(
                id,
                workspaceId,
                projectId,
                projectName,
                linkedRepositoryId,
                repositoryFullName,
                null,
                null,
                null,
                false,
                name,
                slug,
                description,
                enabled,
                definitionVersion,
                rowVersion,
                createdByUsername,
                updatedByUsername,
                stages,
                createdAt,
                updatedAt
        );
    }
}
