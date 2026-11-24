package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.WorkerStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkerHeartbeatResponse(
        String workerId,
        WorkerStatus status,
        Instant serverTime,
        List<AttemptLeaseDirective> directives
) {
    public record AttemptLeaseDirective(
            UUID attemptId,
            UUID stageRunId,
            boolean leaseValid,
            Instant leaseExpiresAt,
            boolean cancelRequested,
            Instant terminationDeadlineAt,
            String message
    ) {
    }
}
