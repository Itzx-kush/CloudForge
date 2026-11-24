package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RotateEnvironmentSecretRequest(
        @NotBlank(message = "New secret value is required")
        String value,
        @Size(max = 500, message = "Description must be 500 characters or fewer")
        String description
) {
    @Override
    public String toString() {
        return "RotateEnvironmentSecretRequest[value=[REDACTED], description=" + description + "]";
    }
}
