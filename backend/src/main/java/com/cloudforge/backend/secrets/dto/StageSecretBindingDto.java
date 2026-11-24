package com.cloudforge.backend.secrets.dto;

import java.util.UUID;

/**
 * Non-sensitive secret reference metadata attached to pipeline definitions and runs.
 * Contains zero secret values, ciphertexts, wrapped DEKs, or nonces.
 */
public record StageSecretBindingDto(
        UUID secretId,
        String secretName,
        String deliveryAlias,
        UUID environmentId,
        Integer pinnedVersionNumber
) {
}
