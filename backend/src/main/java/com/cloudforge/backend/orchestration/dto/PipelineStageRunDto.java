package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.secrets.dto.StageSecretBindingDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineStageRunDto(
        UUID id,
        String stageKey,
        String displayName,
        PipelineStageType stageType,
        int executionOrder,
        List<String> dependsOn,
        int timeoutSeconds,
        int maxRetries,
        int attemptNumber,
        boolean allowFailure,
        PipelineStageStatus status,
        String statusReason,
        String allowedOperation,
        boolean executionSupported,
        String capabilityMessage,
        boolean hasSecretBindings,
        List<StageSecretBindingDto> secretBindings,
        String assignedWorkerId,
        Instant leaseExpiresAt,
        Instant nextRetryAt,
        Instant cancellationRequestedAt,
        Instant readyAt,
        Instant startedAt,
        Instant completedAt
) {
    public PipelineStageRunDto(
            UUID id,
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            int executionOrder,
            List<String> dependsOn,
            int timeoutSeconds,
            int maxRetries,
            int attemptNumber,
            boolean allowFailure,
            PipelineStageStatus status,
            String statusReason,
            String allowedOperation,
            boolean executionSupported,
            String capabilityMessage,
            String assignedWorkerId,
            Instant leaseExpiresAt,
            Instant nextRetryAt,
            Instant cancellationRequestedAt,
            Instant readyAt,
            Instant startedAt,
            Instant completedAt
    ) {
        this(
                id,
                stageKey,
                displayName,
                stageType,
                executionOrder,
                dependsOn,
                timeoutSeconds,
                maxRetries,
                attemptNumber,
                allowFailure,
                status,
                statusReason,
                allowedOperation,
                executionSupported,
                capabilityMessage,
                false,
                List.of(),
                assignedWorkerId,
                leaseExpiresAt,
                nextRetryAt,
                cancellationRequestedAt,
                readyAt,
                startedAt,
                completedAt
        );
    }
}
