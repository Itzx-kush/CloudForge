package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciBuildMode;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable OCI build/publish execution descriptor derived strictly from validated
 * database state. Never includes plaintext registry credentials or GitHub tokens.
 */
public record WorkerOciBuildDescriptorResponse(
        UUID buildRecordId,
        UUID publishAttemptId,
        UUID workspaceId,
        UUID projectId,
        UUID pipelineRunId,
        UUID stageRunId,
        UUID attemptId,
        String operation,
        long githubRepositoryId,
        String repositoryFullName,
        String commitSha,
        String dockerfilePath,
        String buildContextPath,
        String targetImageName,
        String targetTag,
        String targetPlatform,
        OciBuildMode buildMode,
        Map<String, String> buildArgs,
        String cpuLimit,
        String memoryLimit,
        int timeoutSeconds,
        String configChecksumSha256,
        UUID sourceHandoffId,
        String expectedSourceSha256,
        UUID ociHandoffId,
        String expectedOciLayoutSha256,
        String expectedManifestDigest,
        UUID registryConnectionId,
        String registryHost,
        String registryScheme,
        String allowedNamespace,
        boolean allowInsecureLocalRegistry,
        boolean hasRegistryCredentials
) {
}
