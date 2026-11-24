package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciBuildMode;
import com.cloudforge.backend.oci.entity.OciBuildStatus;

import java.time.Instant;
import java.util.UUID;

public record OciBuildRecordDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID pipelineRunId,
        Integer runNumber,
        UUID stageRunId,
        UUID attemptId,
        long githubRepositoryId,
        String repositoryFullName,
        String commitSha,
        String sourceChecksumSha256,
        UUID registryConnectionId,
        String registryHost,
        String dockerfilePath,
        String buildContextPath,
        String targetImageName,
        String targetTag,
        String targetPlatform,
        OciBuildMode buildMode,
        String configChecksumSha256,
        OciBuildStatus status,
        String builtManifestDigest,
        String imageMediaType,
        Long imageSizeBytes,
        String workerId,
        String failureCategory,
        String errorSummary,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
}
