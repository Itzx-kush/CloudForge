package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;

import java.time.Instant;
import java.util.List;

public record GitHubWorkflowJobDto(
        long githubJobId,
        long githubRunId,
        int runAttempt,
        String name,
        String githubStatus,
        String githubConclusion,
        WorkflowRunNormalizedStatus normalizedStatus,
        Instant startedAt,
        Instant completedAt,
        Long durationSeconds,
        String htmlUrl,
        String runnerName,
        List<GitHubWorkflowStepDto> steps
) {
}
