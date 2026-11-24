package com.cloudforge.backend.secrets.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;

import java.time.Instant;
import java.util.UUID;

public record EnvironmentSecretSummaryDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID environmentId,
        String environmentName,
        EnvironmentType environmentType,
        String name,
        String description,
        SecretLifecycleStatus status,
        int currentVersionNumber,
        UUID currentVersionId,
        String currentKekId,
        String encryptionAlgorithm,
        int encryptionFormatVersion,
        String createdByUsername,
        String updatedByUsername,
        Instant createdAt,
        Instant updatedAt,
        Instant lastRotatedAt,
        Instant revokedAt,
        String revokedByUsername
) {
}
