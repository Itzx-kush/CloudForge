package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.RepositoryConnectionStatus;

import java.time.Instant;
import java.util.UUID;

public record LinkedRepositoryDto(
        UUID id,
        UUID workspaceId,
        UUID installationId,
        Long githubInstallationId,
        Long githubRepoId,
        String ownerLogin,
        String name,
        String fullName,
        String defaultBranch,
        String visibility,
        String htmlUrl,
        String description,
        boolean archived,
        RepositoryConnectionStatus connectionStatus,
        Instant githubUpdatedAt,
        Instant lastSyncedAt
) {
}
