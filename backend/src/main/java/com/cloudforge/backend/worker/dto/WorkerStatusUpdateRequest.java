package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.WorkerStatus;
import jakarta.validation.constraints.NotNull;

public record WorkerStatusUpdateRequest(
        @NotNull WorkerStatus status
) {
}
