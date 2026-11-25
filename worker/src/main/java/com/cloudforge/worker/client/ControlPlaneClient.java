package com.cloudforge.worker.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticated internal control-plane client interface implemented over HTTP
 * ({@link HttpControlPlaneClient}) and mockable in unit tests.
 */
public interface ControlPlaneClient {

    void register(RegistrationPayload payload);

    HeartbeatResult heartbeat(String workerId, List<ActiveLease> activeLeases);

    Optional<ClaimedStageLease> claimNextStage(String workerId);

    void acknowledgeRunning(String workerId, UUID attemptId, String leaseToken, long fencingGeneration, Instant startedAt);

    default SecretDeliveryResult fetchAttemptSecrets(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("Worker secret delivery is not supported by this client");
    }

    default URI fetchSourceArchive(String workerId, UUID attemptId, String leaseToken, long fencingGeneration) {
        throw new UnsupportedOperationException("GitHub source acquisition is unavailable");
    }

    default HandoffUploadResult uploadSourceHandoff(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path zipFile,
            String contentSha256,
            int fileCount,
            long extractedBytes
    ) {
        throw new UnsupportedOperationException("Source handoff upload is unavailable");
    }

    default OciExecutionDescriptor fetchOciDescriptor(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("OCI execution descriptor fetch is unavailable");
    }

    default DownloadedHandoff downloadBuildContext(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path destinationFile
    ) {
        throw new UnsupportedOperationException("OCI build context download is unavailable");
    }

    default HandoffUploadResult uploadOciLayout(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path ociTarFile,
            String contentSha256,
            long imageSizeBytes
    ) {
        throw new UnsupportedOperationException("OCI layout upload is unavailable");
    }

    default DownloadedHandoff downloadOciLayout(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            Path destinationFile
    ) {
        throw new UnsupportedOperationException("OCI layout download is unavailable");
    }

    default void reportOciBuildResult(String workerId, UUID attemptId, OciBuildResultPayload payload) {
        throw new UnsupportedOperationException("OCI build result reporting is unavailable");
    }

    default RegistryCredentialResult fetchRegistryCredentials(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("OCI registry credential delivery is unavailable");
    }

    default void reportOciPublishResult(String workerId, UUID attemptId, OciPublishResultPayload payload) {
        throw new UnsupportedOperationException("OCI publish result reporting is unavailable");
    }

    default Optional<ClusterValidationTask> claimClusterValidation(String workerId) {
        return Optional.empty();
    }

    default ClusterCredentialsResult fetchClusterValidationCredentials(
            String workerId,
            UUID clusterId,
            long validationNonce
    ) {
        throw new UnsupportedOperationException("Kubernetes cluster validation credential delivery is unavailable");
    }

    default void completeClusterValidation(
            String workerId,
            UUID clusterId,
            ClusterValidationResultPayload payload
    ) {
        throw new UnsupportedOperationException("Kubernetes cluster validation completion is unavailable");
    }

    default KubernetesExecutionDescriptor fetchKubernetesDescriptor(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("Kubernetes deployment descriptor fetch is unavailable");
    }

    default ClusterCredentialsResult fetchKubernetesDeploymentCredentials(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("Kubernetes deployment credential delivery is unavailable");
    }

    default void reportKubernetesStatus(
            String workerId,
            UUID attemptId,
            KubernetesStatusUpdatePayload payload
    ) {
        throw new UnsupportedOperationException("Kubernetes deployment status update is unavailable");
    }

    default void reportKubernetesResult(
            String workerId,
            UUID attemptId,
            KubernetesResultPayload payload
    ) {
        throw new UnsupportedOperationException("Kubernetes deployment result reporting is unavailable");
    }

    void appendLogs(String workerId, UUID attemptId, String leaseToken, long fencingGeneration, List<LogEntry> entries);

    void completeAttempt(String workerId, UUID attemptId, CompletionPayload payload);

    void updateStatus(String workerId, String status);

    record RegistrationPayload(
            String workerId,
            String displayName,
            String adapterType,
            List<String> supportedOperations,
            int maxConcurrency,
            String hostLabel,
            String agentVersion
    ) {
    }

    record ActiveLease(UUID attemptId, String leaseToken, long fencingGeneration) {
    }

    record HeartbeatResult(String workerId, String status, Instant serverTime, List<LeaseDirective> directives) {
    }

    record LeaseDirective(
            UUID attemptId,
            UUID stageRunId,
            boolean leaseValid,
            Instant leaseExpiresAt,
            boolean cancelRequested,
            Instant terminationDeadlineAt,
            String message
    ) {
    }

    record StageInfo(
            UUID workspaceId,
            UUID projectId,
            UUID pipelineRunId,
            UUID stageRunId,
            String stageKey,
            String displayName,
            String stageType,
            int executionOrder,
            int definitionVersion,
            String repositoryFullName,
            String targetBranch,
            String commitSha,
            int timeoutSeconds,
            int maxRetries,
            int attemptNumber
    ) {
    }

    record ClaimedStageLease(
            StageInfo stage,
            UUID attemptId,
            String workerId,
            String leaseToken,
            long fencingGeneration,
            String allowedOperation,
            boolean idempotentOperation,
            Instant leaseStartedAt,
            Instant leaseExpiresAt,
            int heartbeatIntervalSeconds,
            int maxLogLines,
            int maxLogBytes,
            boolean hasSecretBindings,
            List<String> secretAliases,
            boolean sourceHandoffRequired
    ) {
        public ClaimedStageLease(
                StageInfo stage,
                UUID attemptId,
                String workerId,
                String leaseToken,
                long fencingGeneration,
                String allowedOperation,
                boolean idempotentOperation,
                Instant leaseStartedAt,
                Instant leaseExpiresAt,
                int heartbeatIntervalSeconds,
                int maxLogLines,
                int maxLogBytes,
                boolean hasSecretBindings,
                List<String> secretAliases
        ) {
            this(
                    stage, attemptId, workerId, leaseToken, fencingGeneration, allowedOperation,
                    idempotentOperation, leaseStartedAt, leaseExpiresAt, heartbeatIntervalSeconds,
                    maxLogLines, maxLogBytes, hasSecretBindings, secretAliases, false
            );
        }

        public ClaimedStageLease(
                StageInfo stage,
                UUID attemptId,
                String workerId,
                String leaseToken,
                long fencingGeneration,
                String allowedOperation,
                boolean idempotentOperation,
                Instant leaseStartedAt,
                Instant leaseExpiresAt,
                int heartbeatIntervalSeconds,
                int maxLogLines,
                int maxLogBytes
        ) {
            this(
                    stage, attemptId, workerId, leaseToken, fencingGeneration, allowedOperation,
                    idempotentOperation, leaseStartedAt, leaseExpiresAt, heartbeatIntervalSeconds,
                    maxLogLines, maxLogBytes, false, List.of(), false
            );
        }
    }

    record DeliveredSecret(
            String alias,
            String secretName,
            UUID secretId,
            UUID secretVersionId,
            int versionNumber,
            String value
    ) {
        @Override
        public String toString() {
            return "DeliveredSecret[alias=" + alias + ", secretId=" + secretId
                    + ", versionNumber=" + versionNumber + ", value=[REDACTED]]";
        }
    }

    record SecretDeliveryResult(
            UUID attemptId,
            UUID pipelineRunId,
            UUID stageRunId,
            long fencingGeneration,
            Instant deliveredAt,
            List<DeliveredSecret> secrets
    ) {
        @Override
        public String toString() {
            return "SecretDeliveryResult[attemptId=" + attemptId
                    + ", count=" + (secrets == null ? 0 : secrets.size()) + ", secrets=[REDACTED]]";
        }
    }

    record HandoffUploadResult(
            UUID handoffId,
            String handoffType,
            String contentChecksumSha256,
            long archiveSizeBytes,
            long extractedBytes,
            int fileCount,
            Instant expiresAt
    ) {
    }

    record DownloadedHandoff(
            UUID handoffId,
            String contentChecksumSha256,
            String commitSha,
            Path file
    ) {
    }

    record OciExecutionDescriptor(
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
            String buildMode,
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

    record RegistryCredentialResult(
            UUID attemptId,
            UUID registryConnectionId,
            String registryHost,
            String scheme,
            String username,
            String password,
            boolean anonymous,
            Instant deliveredAt
    ) {
        @Override
        public String toString() {
            return "RegistryCredentialResult[attemptId=" + attemptId
                    + ", registryHost=" + registryHost + ", credentials=[REDACTED]]";
        }
    }

    record OciBuildResultPayload(
            String leaseToken,
            long fencingGeneration,
            String status,
            String builtManifestDigest,
            String imageMediaType,
            Long imageSizeBytes,
            UUID ociHandoffId,
            String failureCategory,
            String errorSummary,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    record OciPublishResultPayload(
            String leaseToken,
            long fencingGeneration,
            String status,
            String reportedDigest,
            String verifiedDigest,
            String imageMediaType,
            String platform,
            Long imageSizeBytes,
            boolean verified,
            String failureCategory,
            String errorSummary,
            Instant completedAt
    ) {
    }

    record LogEntry(String streamType, String message, Instant occurredAt) {
    }

    record CompletionPayload(
            UUID pipelineRunId,
            UUID stageRunId,
            int attemptNumber,
            String leaseToken,
            long fencingGeneration,
            String resultStatus,
            String failureCategory,
            Integer exitCode,
            String summary,
            Map<String, Object> resourceUsage,
            Instant startedAt,
            Instant completedAt,
            boolean terminationConfirmed
    ) {
    }

    record KubernetesProbeSpec(
            String type,
            String path,
            Integer port,
            Integer initialDelaySeconds,
            Integer periodSeconds,
            Integer timeoutSeconds,
            Integer failureThreshold,
            Integer successThreshold
    ) {
    }

    record ClusterValidationTask(
            UUID clusterId,
            UUID workspaceId,
            String clusterIdentifier,
            String clusterName,
            String clusterType,
            String apiServerUrl,
            String caCertificateFingerprintSha256,
            String serviceAccountName,
            boolean allowLocalLoopback,
            long validationNonce,
            List<String> expectedNamespaces
    ) {
    }

    record ClusterCredentialsResult(
            UUID clusterId,
            String apiServerUrl,
            String caCertificatePem,
            String bearerToken,
            String serviceAccountName,
            boolean allowLocalLoopback,
            Instant deliveredAt
    ) {
        @Override
        public String toString() {
            return "ClusterCredentialsResult[clusterId=" + clusterId
                    + ", apiServerUrl=" + apiServerUrl + ", bearerToken=[REDACTED]]";
        }
    }

    record ClusterValidationResultPayload(
            long validationNonce,
            boolean valid,
            String kubernetesVersion,
            Map<String, Object> supportedCapabilities,
            List<String> verifiedNamespaces,
            String errorSummary,
            Instant validatedAt
    ) {
    }

    record KubernetesExecutionDescriptor(
            UUID deploymentOperationId,
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            String environmentType,
            UUID clusterConnectionId,
            String clusterIdentifier,
            String apiServerUrl,
            boolean allowLocalLoopback,
            UUID namespaceBindingId,
            String namespaceName,
            String workloadServiceAccountName,
            String imagePullSecretName,
            UUID deploymentConfigId,
            UUID ociArtifactId,
            String deploymentName,
            String serviceName,
            String immutableImageReference,
            String manifestDigest,
            String sourceCommitSha,
            String manifestChecksumSha256,
            int replicaCount,
            int containerPort,
            int servicePort,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            KubernetesProbeSpec readinessProbe,
            KubernetesProbeSpec livenessProbe,
            KubernetesProbeSpec startupProbe,
            String rollingMaxSurge,
            String rollingMaxUnavailable,
            int progressDeadlineSeconds,
            int deploymentTimeoutSeconds,
            boolean readOnlyRootFilesystem,
            boolean runAsNonRoot,
            long runAsUser,
            KubernetesReleaseExecution releaseExecution
    ) {
        public KubernetesExecutionDescriptor(
                UUID deploymentOperationId, UUID workspaceId, UUID projectId, UUID environmentId, String
                environmentType, UUID clusterConnectionId, String clusterIdentifier, String apiServerUrl, boolean
                allowLocalLoopback, UUID namespaceBindingId, String namespaceName, String workloadServiceAccountName,
                String imagePullSecretName, UUID deploymentConfigId, UUID ociArtifactId, String deploymentName, String
                serviceName, String immutableImageReference, String manifestDigest, String sourceCommitSha, String
                manifestChecksumSha256, int replicaCount, int containerPort, int servicePort, String cpuRequest, String
                cpuLimit, String memoryRequest, String memoryLimit, KubernetesProbeSpec readinessProbe,
                KubernetesProbeSpec livenessProbe, KubernetesProbeSpec startupProbe, String rollingMaxSurge, String
                rollingMaxUnavailable, int progressDeadlineSeconds, int deploymentTimeoutSeconds, boolean
                readOnlyRootFilesystem, boolean runAsNonRoot, long runAsUser
        ) {
            this(
                    deploymentOperationId, workspaceId, projectId, environmentId, environmentType, clusterConnectionId,
                    clusterIdentifier, apiServerUrl, allowLocalLoopback, namespaceBindingId, namespaceName,
                    workloadServiceAccountName, imagePullSecretName, deploymentConfigId, ociArtifactId, deploymentName,
                    serviceName, immutableImageReference, manifestDigest, sourceCommitSha, manifestChecksumSha256,
                    replicaCount, containerPort, servicePort, cpuRequest, cpuLimit, memoryRequest, memoryLimit,
                    readinessProbe, livenessProbe, startupProbe, rollingMaxSurge, rollingMaxUnavailable,
                    progressDeadlineSeconds, deploymentTimeoutSeconds, readOnlyRootFilesystem, runAsNonRoot, runAsUser,
                    null
            );
        }
    }

    record KubernetesReleaseExecution(String kind, String expectedImage, String expectedDeploymentUid,
                                      String expectedServiceUid, Long expectedGeneration) {
    }

    record KubernetesStatusUpdatePayload(
            String leaseToken,
            long fencingGeneration,
            String status,
            boolean applyInitiated,
            Integer observedReplicas,
            Integer updatedReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            Long observedGeneration,
            String k8sDeploymentUid,
            String k8sServiceUid,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics,
            String message
    ) {
    }

    record KubernetesResultPayload(
            String leaseToken,
            long fencingGeneration,
            String status,
            boolean applyInitiated,
            Integer observedReplicas,
            Integer updatedReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            Long observedGeneration,
            String k8sDeploymentUid,
            String k8sServiceUid,
            String verifiedImageDigest,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics,
            String failureCategory,
            String errorSummary,
            Instant completedAt
    ) {
    }
}
