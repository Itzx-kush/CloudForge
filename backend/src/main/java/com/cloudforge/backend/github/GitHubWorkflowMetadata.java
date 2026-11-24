package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubWorkflowMetadata(
        long githubWorkflowId,
        String name,
        String path,
        String state,
        String htmlUrl,
        String badgeUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
