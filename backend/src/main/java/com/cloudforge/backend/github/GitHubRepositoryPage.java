package com.cloudforge.backend.github;

import java.util.List;

public record GitHubRepositoryPage(
        int totalCount,
        List<GitHubRepositoryMetadata> repositories
) {
}
