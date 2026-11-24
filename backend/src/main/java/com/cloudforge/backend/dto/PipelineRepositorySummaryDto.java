package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.InstallationStatus;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;

import java.time.Instant;
import java.util.UUID;

public record PipelineRepositorySummaryDto(
        UUID repositoryId,
        String fullName,
        String name,
        String ownerLogin,
        String defaultBranch,
        String visibility,
        RepositoryConnectionStatus connectionStatus,
        InstallationStatus installationStatus,
        String htmlUrl,
        long workflowCount,
        long runCount,
        long activeRunCount,
        long failedRunCount,
        Instant lastWorkflowSyncAt,
        WorkflowRunNormalizedStatus latestRunStatus,
        Instant latestRunAt
) {
}
