package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.InstallationStatus;

import java.time.Instant;
import java.util.UUID;

public record GitHubInstallationDto(
        UUID id,
        UUID workspaceId,
        Long githubInstallationId,
        String accountLogin,
        String accountType,
        String accountAvatarUrl,
        String repositorySelection,
        InstallationStatus status,
        Instant lastSyncedAt,
        Instant createdAt
) {
}
