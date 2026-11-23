package com.cloudforge.backend.dto;

import java.time.Instant;

public record GitHubInstallUrlResponse(
        String installUrl,
        Instant expiresAt
) {
}
