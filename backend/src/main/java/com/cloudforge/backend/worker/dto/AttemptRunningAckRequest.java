package com.cloudforge.backend.worker.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record AttemptRunningAckRequest(
        @NotBlank String leaseToken,
        long fencingGeneration,
        Instant startedAt
) {
}
