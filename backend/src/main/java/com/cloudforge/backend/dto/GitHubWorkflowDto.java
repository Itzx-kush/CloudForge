package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;

import java.time.Instant;
import java.util.UUID;

public record GitHubWorkflowDto(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        String repositoryFullName,
        long githubWorkflowId,
        String name,
        String path,
        String state,
        String htmlUrl,
        Instant githubCreatedAt,
        Instant githubUpdatedAt,
        Instant lastSyncedAt,
        UUID latestRunId,
        Integer latestRunNumber,
        WorkflowRunNormalizedStatus latestRunStatus,
        String latestRunGithubStatus,
        String latestRunConclusion,
        Instant latestRunAt
) {
}
