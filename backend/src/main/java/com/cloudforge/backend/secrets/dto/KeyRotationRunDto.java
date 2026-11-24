package com.cloudforge.backend.secrets.dto;

import com.cloudforge.backend.secrets.entity.KeyRotationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record KeyRotationRunDto(
        UUID id,
        String targetKekId,
        KeyRotationRunStatus status,
        int totalCandidates,
        int processedCount,
        int rewrappedCount,
        int skippedCount,
        int failedCount,
        UUID lastProcessedVersionId,
        String initiatedBy,
        String errorSummary,
        boolean dryRun,
        Instant startedAt,
        Instant completedAt
) {
}
