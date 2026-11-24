package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WorkerSecretDeliveryRequest(
        @NotBlank(message = "leaseToken is required")
        String leaseToken,
        @NotNull(message = "fencingGeneration is required")
        Long fencingGeneration
) {
}
