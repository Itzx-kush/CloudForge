package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRunJobsResponse(
        UUID runId,
        long githubRunId,
        UUID repositoryId,
        String repositoryFullName,
        Instant fetchedAt,
        List<GitHubWorkflowJobDto> jobs
) {
}
