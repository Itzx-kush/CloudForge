package com.cloudforge.backend.worker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record WorkerHeartbeatRequest(
        @Size(max = 32)
        List<@Valid ActiveLeaseHeartbeat> activeLeases
) {
    public record ActiveLeaseHeartbeat(
            @NotNull UUID attemptId,
            @NotBlank String leaseToken,
            long fencingGeneration
    ) {
    }
}
