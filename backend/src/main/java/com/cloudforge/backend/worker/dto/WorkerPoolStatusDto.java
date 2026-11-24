package com.cloudforge.backend.worker.dto;

import java.time.Instant;
import java.util.List;

/**
 * Operational summary of the isolated worker plane visible to authorized workspace members.
 * Global worker host/node details are populated strictly for workspace OWNERs;
 * credentials are never included.
 */
public record WorkerPoolStatusDto(
        boolean workerPlaneEnabled,
        boolean schedulerEnabled,
        long registeredWorkers,
        long availableWorkers,
        long busyWorkers,
        long drainingWorkers,
        long offlineWorkers,
        long activeAssignments,
        long workspaceReadyStages,
        long workspaceRunningStages,
        long workspaceFailedAttempts,
        List<String> supportedOperations,
        List<WorkerNodeDto> workers,
        Instant sampledAt
) {
}
