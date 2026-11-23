package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubInstallationToken(
        String token,
        Instant expiresAt
) {
}
