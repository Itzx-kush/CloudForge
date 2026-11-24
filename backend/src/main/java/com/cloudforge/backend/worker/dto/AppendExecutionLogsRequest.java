package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.LogStreamType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record AppendExecutionLogsRequest(
        @NotBlank String leaseToken,
        long fencingGeneration,
        @NotEmpty
        @Size(max = 100)
        List<@Valid LogEntryInput> entries
) {
    public record LogEntryInput(
            @NotNull LogStreamType streamType,
            @NotNull String message,
            Instant occurredAt
    ) {
    }
}
