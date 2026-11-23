package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubRepositoryMetadata(
        long githubRepoId,
        String ownerLogin,
        String name,
        String fullName,
        String defaultBranch,
        String visibility,
        String htmlUrl,
        String description,
        boolean archived,
        Instant updatedAt
) {
}
