package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record RevokeEnvironmentSecretRequest(
        @Min(value = 1, message = "Version number must be >= 1")
        Integer versionNumber,
        @Size(max = 300, message = "Reason must be 300 characters or fewer")
        String reason
) {
}
