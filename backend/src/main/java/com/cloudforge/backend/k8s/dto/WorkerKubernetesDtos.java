package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal trusted-worker DTOs for Phase 3.6 Kubernetes cluster validation and deployment execution.
 * Credentials responses redact secrets in {@code toString()} and are served strictly over
 * authenticated worker endpoints with {@code Cache-Control: no-store}.
 */
public final class WorkerKubernetesDtos {

    private WorkerKubernetesDtos() {
    }

    public record LeaseRequest(
            @NotBlank @Size(max = 128) String leaseToken,
            @Min(1) long fencingGeneration
    ) {
    }

    public record ClusterValidationTask(
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

    public record ClusterValidationNonceRequest(
            @Min(1) long validationNonce
    ) {
    }

    public record ClusterCredentialsResponse(
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
            return "ClusterCredentialsResponse[clusterId=" + clusterId
                    + ", apiServerUrl=" + apiServerUrl + ", bearerToken=[REDACTED]]";
        }
    }

    public record ClusterValidationResultRequest(
            @Min(1) long validationNonce,
            boolean valid,
            @Size(max = 64) String kubernetesVersion,
            Map<String, Object> supportedCapabilities,
            List<String> verifiedNamespaces,
            @Size(max = 500) String errorSummary,
            Instant validatedAt
    ) {
    }

    public record DeploymentExecutionDescriptor(
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
            KubernetesProbeConfig readinessProbe,
            KubernetesProbeConfig livenessProbe,
            KubernetesProbeConfig startupProbe,
            String rollingMaxSurge,
            String rollingMaxUnavailable,
            int progressDeadlineSeconds,
            int deploymentTimeoutSeconds,
            boolean readOnlyRootFilesystem,
            boolean runAsNonRoot,
            long runAsUser
    ) {
    }

    public record DeploymentStatusUpdateRequest(
            @NotBlank @Size(max = 128) String leaseToken,
            @Min(1) long fencingGeneration,
            @NotNull KubernetesDeploymentStatus status,
            boolean applyInitiated,
            Integer observedReplicas,
            Integer updatedReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            Long observedGeneration,
            @Size(max = 128) String k8sDeploymentUid,
            @Size(max = 128) String k8sServiceUid,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics,
            @Size(max = 500) String message
    ) {
    }

    public record DeploymentResultReportRequest(
            @NotBlank @Size(max = 128) String leaseToken,
            @Min(1) long fencingGeneration,
            @NotNull KubernetesDeploymentStatus status,
            boolean applyInitiated,
            Integer observedReplicas,
            Integer updatedReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            Long observedGeneration,
            @Size(max = 128) String k8sDeploymentUid,
            @Size(max = 128) String k8sServiceUid,
            @Size(max = 128) String verifiedImageDigest,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics,
            @Size(max = 40) String failureCategory,
            @Size(max = 500) String errorSummary,
            Instant completedAt
    ) {
    }
}
