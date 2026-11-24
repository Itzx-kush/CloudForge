package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerStatus;

import java.time.Instant;
import java.util.List;

public record WorkerNodeDto(
        String workerId,
        String displayName,
        WorkerAdapterType adapterType,
        WorkerStatus status,
        List<String> supportedOperations,
        int maxConcurrency,
        int activeAssignments,
        String hostLabel,
        String agentVersion,
        Instant registeredAt,
        Instant lastHeartbeatAt,
        Instant offlineAt
) {
}
