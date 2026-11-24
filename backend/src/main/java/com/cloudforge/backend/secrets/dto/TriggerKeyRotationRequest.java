package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record TriggerKeyRotationRequest(
        @Min(value = 1, message = "batchSize must be >= 1")
        @Max(value = 200, message = "batchSize must be <= 200")
        Integer batchSize,
        Boolean dryRun,
        @Size(max = 100, message = "initiatedBy must be 100 characters or fewer")
        String initiatedBy
) {
}
