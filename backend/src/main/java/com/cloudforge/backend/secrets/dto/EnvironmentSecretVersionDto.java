package com.cloudforge.backend.secrets.dto;

import com.cloudforge.backend.secrets.entity.SecretVersionStatus;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentSecretVersionDto(
        UUID id,
        UUID secretId,
        int versionNumber,
        SecretVersionStatus status,
        String kekId,
        String encryptionAlgorithm,
        int encryptionFormatVersion,
        String createdByUsername,
        Instant createdAt,
        Instant revokedAt,
        String revokedByUsername,
        boolean current
) {
}
