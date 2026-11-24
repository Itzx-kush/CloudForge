package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.LogStreamType;

import java.time.Instant;
import java.util.UUID;

public record StageExecutionLogDto(
        UUID id,
        UUID attemptId,
        int attemptNumber,
        String workerId,
        int sequenceNumber,
        LogStreamType streamType,
        String message,
        boolean truncated,
        Instant occurredAt
) {
}
