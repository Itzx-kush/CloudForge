package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubWorkflowRunMetadata(
        long githubRunId,
        long githubWorkflowId,
        int runNumber,
        int runAttempt,
        String workflowName,
        String displayTitle,
        String event,
        String headBranch,
        String headSha,
        String status,
        String conclusion,
        String actorLogin,
        String actorAvatarUrl,
        String htmlUrl,
        Instant createdAt,
        Instant updatedAt,
        Instant runStartedAt
) {
}
