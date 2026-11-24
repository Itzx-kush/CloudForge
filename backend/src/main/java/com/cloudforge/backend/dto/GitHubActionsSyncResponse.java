package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.UUID;

public record GitHubActionsSyncResponse(
        UUID repositoryId,
        String repositoryFullName,
        int workflowsSynced,
        int runsSynced,
        int runsCreated,
        int runsUpdated,
        Instant syncedAt,
        String message
) {
}
