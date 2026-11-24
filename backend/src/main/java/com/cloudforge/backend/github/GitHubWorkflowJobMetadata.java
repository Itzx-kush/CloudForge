package com.cloudforge.backend.github;

import java.time.Instant;
import java.util.List;

public record GitHubWorkflowJobMetadata(
        long githubJobId,
        long githubRunId,
        int runAttempt,
        String name,
        String status,
        String conclusion,
        Instant startedAt,
        Instant completedAt,
        String htmlUrl,
        String runnerName,
        List<GitHubWorkflowStepMetadata> steps
) {
}
