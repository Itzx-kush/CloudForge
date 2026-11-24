package com.cloudforge.backend.github;

import java.time.Instant;

public record GitHubWorkflowStepMetadata(
        int number,
        String name,
        String status,
        String conclusion,
        Instant startedAt,
        Instant completedAt
) {
}
