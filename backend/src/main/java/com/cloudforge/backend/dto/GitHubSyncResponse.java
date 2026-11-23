package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.InstallationStatus;

import java.time.Instant;
import java.util.UUID;

public record GitHubSyncResponse(
        UUID installationId,
        InstallationStatus installationStatus,
        int syncedCount,
        int disconnectedCount,
        Instant syncedAt,
        String message
) {
}
