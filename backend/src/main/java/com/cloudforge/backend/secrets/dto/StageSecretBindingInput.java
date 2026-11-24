package com.cloudforge.backend.secrets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record StageSecretBindingInput(
        @NotNull(message = "secretId is required for a stage secret binding")
        UUID secretId,

        @NotBlank(message = "deliveryAlias is required for a stage secret binding")
        @Size(max = 128, message = "deliveryAlias must be 128 characters or fewer")
        String deliveryAlias
) {
}
