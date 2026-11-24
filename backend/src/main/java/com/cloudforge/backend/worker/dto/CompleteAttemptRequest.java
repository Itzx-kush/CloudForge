package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CompleteAttemptRequest(
        @NotNull UUID pipelineRunId,
        @NotNull UUID stageRunId,
        @Min(1) int attemptNumber,
        @NotBlank String leaseToken,
        @Min(1) long fencingGeneration,
        @NotNull AttemptStatus resultStatus,
        AttemptFailureCategory failureCategory,
        Integer exitCode,
        @Size(max = 500) String summary,
        Map<String, Object> resourceUsage,
        Instant startedAt,
        Instant completedAt,
        boolean terminationConfirmed
) {
}
