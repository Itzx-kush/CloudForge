package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciPublishAttemptStatus;

import java.time.Instant;
import java.util.UUID;

public record OciPublishAttemptDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID pipelineRunId,
        UUID stageRunId,
        UUID attemptId,
        UUID buildRecordId,
        UUID registryConnectionId,
        UUID artifactId,
        String workerId,
        String registryHost,
        String imageRepository,
        String imageTag,
        String expectedDigest,
        String reportedDigest,
        String verifiedDigest,
        OciPublishAttemptStatus status,
        String failureCategory,
        String errorSummary,
        Instant startedAt,
        Instant completedAt
) {
}
