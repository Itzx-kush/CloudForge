package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineRunDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID definitionId,
        String definitionName,
        int definitionVersion,
        UUID linkedRepositoryId,
        String repositoryFullName,
        UUID targetEnvironmentId,
        String targetEnvironmentName,
        EnvironmentType targetEnvironmentType,
        boolean hasSecretBindings,
        int runNumber,
        PipelineTriggerType triggerType,
        String targetBranch,
        String commitSha,
        PipelineRunStatus status,
        String statusReason,
        String requestedByUsername,
        String cancelledByUsername,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        Instant cancellationRequestedAt,
        Long rowVersion,
        List<PipelineStageRunDto> stages,
        Instant createdAt,
        Instant updatedAt
) {
    public PipelineRunDto(
            UUID id,
            UUID workspaceId,
            UUID projectId,
            String projectName,
            UUID definitionId,
            String definitionName,
            int definitionVersion,
            UUID linkedRepositoryId,
            String repositoryFullName,
            int runNumber,
            PipelineTriggerType triggerType,
            String targetBranch,
            String commitSha,
            PipelineRunStatus status,
            String statusReason,
            String requestedByUsername,
            String cancelledByUsername,
            Instant queuedAt,
            Instant startedAt,
            Instant completedAt,
            Instant cancelledAt,
            Instant cancellationRequestedAt,
            Long rowVersion,
            List<PipelineStageRunDto> stages,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(
                id,
                workspaceId,
                projectId,
                projectName,
                definitionId,
                definitionName,
                definitionVersion,
                linkedRepositoryId,
                repositoryFullName,
                null,
                null,
                null,
                false,
                runNumber,
                triggerType,
                targetBranch,
                commitSha,
                status,
                statusReason,
                requestedByUsername,
                cancelledByUsername,
                queuedAt,
                startedAt,
                completedAt,
                cancelledAt,
                cancellationRequestedAt,
                rowVersion,
                stages,
                createdAt,
                updatedAt
        );
    }
}
