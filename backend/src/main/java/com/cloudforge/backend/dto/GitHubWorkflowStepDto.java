package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;

import java.time.Instant;

public record GitHubWorkflowStepDto(
        int number,
        String name,
        String githubStatus,
        String githubConclusion,
        WorkflowRunNormalizedStatus normalizedStatus,
        Instant startedAt,
        Instant completedAt,
        Long durationSeconds
) {
}
