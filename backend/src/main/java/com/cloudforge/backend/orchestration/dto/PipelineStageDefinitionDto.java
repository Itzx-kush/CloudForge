package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.secrets.dto.StageSecretBindingDto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PipelineStageDefinitionDto(
        UUID id,
        String stageKey,
        String displayName,
        PipelineStageType stageType,
        int executionOrder,
        List<String> dependsOn,
        int timeoutSeconds,
        int maxRetries,
        boolean allowFailure,
        Map<String, String> metadata,
        List<StageSecretBindingDto> secretBindings
) {
    public PipelineStageDefinitionDto(
            UUID id,
            String stageKey,
            String displayName,
            PipelineStageType stageType,
            int executionOrder,
            List<String> dependsOn,
            int timeoutSeconds,
            int maxRetries,
            boolean allowFailure,
            Map<String, String> metadata
    ) {
        this(id, stageKey, displayName, stageType, executionOrder, dependsOn, timeoutSeconds, maxRetries, allowFailure, metadata, List.of());
    }
}
