package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentSecretRequest(
        @NotBlank(message = "Secret name is required")
        @Size(max = 128, message = "Secret name must be 128 characters or fewer")
        String name,
        @NotBlank(message = "Secret value is required")
        String value,
        @Size(max = 500, message = "Description must be 500 characters or fewer")
        String description
) {
    @Override
    public String toString() {
        return "CreateEnvironmentSecretRequest[name=" + name + ", value=[REDACTED], description=" + description + "]";
    }
}
