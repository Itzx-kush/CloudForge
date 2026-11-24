package com.cloudforge.backend.orchestration.dto;

import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePipelineRunRequest(
        @NotNull(message = "Pipeline definition ID is required")
        UUID definitionId,

        @Size(max = 150, message = "Target branch must be at most 150 characters")
        String targetBranch,

        @Size(max = 64, message = "Commit SHA must be at most 64 characters")
        String commitSha,

        PipelineTriggerType triggerType,

        @Size(max = 128, message = "Idempotency key must be at most 128 characters")
        String idempotencyKey
) {
}
