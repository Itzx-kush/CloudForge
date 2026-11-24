package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;

import java.time.Instant;
import java.util.UUID;

public record GitHubWorkflowRunDto(
        UUID id,
        UUID workspaceId,
        UUID repositoryId,
        String repositoryFullName,
        UUID workflowId,
        long githubRunId,
        long githubWorkflowId,
        int runNumber,
        int runAttempt,
        String workflowName,
        String displayTitle,
        String event,
        String headBranch,
        String headSha,
        String githubStatus,
        String githubConclusion,
        WorkflowRunNormalizedStatus normalizedStatus,
        String actorLogin,
        String actorAvatarUrl,
        String htmlUrl,
        Instant githubCreatedAt,
        Instant githubUpdatedAt,
        Instant runStartedAt,
        Long durationSeconds,
        Instant lastSyncedAt
) {
}
