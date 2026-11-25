package com.cloudforge.backend.k8s.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Declarative Kubernetes Deployment + ClusterIP Service request targeting an approved
 * cluster, approved namespace binding, and verified immutable OCI image artifact.
 */
public record CreateDeploymentRequest(
        @NotNull UUID projectId,
        @NotNull UUID environmentId,
        @NotNull UUID clusterConnectionId,
        UUID namespaceBindingId,
        @Size(max = 63) String namespace,
        @NotNull UUID ociArtifactId,
        @Size(max = 63) String deploymentName,
        @Size(max = 63) String serviceName,
        @Min(1) @Max(20) Integer replicaCount,
        @Min(1) @Max(65535) Integer containerPort,
        @Min(1) @Max(65535) Integer servicePort,
        @Size(max = 32) String cpuRequest,
        @Size(max = 32) String cpuLimit,
        @Size(max = 32) String memoryRequest,
        @Size(max = 32) String memoryLimit,
        @Valid KubernetesProbeConfig readinessProbe,
        @Valid KubernetesProbeConfig livenessProbe,
        @Valid KubernetesProbeConfig startupProbe,
        @Size(max = 16) String rollingMaxSurge,
        @Size(max = 16) String rollingMaxUnavailable,
        @Min(30) @Max(1800) Integer progressDeadlineSeconds,
        @Min(30) @Max(1800) Integer deploymentTimeoutSeconds,
        Boolean readOnlyRootFilesystem,
        Boolean runAsNonRoot,
        @Min(1000) @Max(65534) Long runAsUser,
        Boolean productionApprovalConfirmed,
        @Size(max = 128) String productionApprovalJustification,
        @Size(max = 128) String idempotencyKey
) {
}
