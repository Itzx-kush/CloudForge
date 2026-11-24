package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PipelineOverviewResponse(
        UUID workspaceId,
        boolean githubAppConfigured,
        int connectedRepositoryCount,
        int repositoriesWithWorkflowsCount,
        long workflowCount,
        long recentRunCount,
        long activeRunCount,
        long failedRunCount,
        long succeededRunCount,
        Instant lastSyncedAt,
        List<PipelineRepositorySummaryDto> repositories
) {
}
