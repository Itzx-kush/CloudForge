package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StageExecutionAttemptDto(
        UUID id,
        UUID stageRunId,
        String workerId,
        int attemptNumber,
        long fencingGeneration,
        String allowedOperation,
        AttemptStatus status,
        AttemptFailureCategory failureCategory,
        Integer exitCode,
        String summary,
        Map<String, Object> resourceUsage,
        Instant leaseStartedAt,
        Instant leaseExpiresAt,
        Instant lastHeartbeatAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancellationRequestedAt,
        boolean terminationConfirmed,
        Instant recoveredAt
) {
}
