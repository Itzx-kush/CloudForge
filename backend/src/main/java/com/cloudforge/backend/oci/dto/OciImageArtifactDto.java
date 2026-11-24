package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciArtifactRetentionPolicy;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;

import java.time.Instant;
import java.util.UUID;

public record OciImageArtifactDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID pipelineRunId,
        Integer runNumber,
        UUID stageRunId,
        UUID attemptId,
        UUID buildRecordId,
        UUID registryConnectionId,
        long githubRepositoryId,
        String repositoryFullName,
        String commitSha,
        String registryHost,
        String imageRepository,
        String imageTag,
        String manifestDigest,
        String immutableReference,
        String imageMediaType,
        String platform,
        Long imageSizeBytes,
        OciArtifactVerificationStatus verificationStatus,
        Instant verifiedAt,
        Instant publishedAt,
        OciArtifactRetentionPolicy retentionPolicy,
        String errorSummary,
        Instant createdAt
) {
}
