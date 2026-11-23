package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubInstallationDetails(
        long installationId,
        long accountId,
        String accountLogin,
        String accountType,
        String accountAvatarUrl,
        String repositorySelection,
        boolean suspended,
        Instant suspendedAt
) {
}
