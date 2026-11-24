package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.entity.EnvironmentType;

import java.time.Instant;
import java.util.UUID;

public record KubernetesDeploymentConfigDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID environmentId,
        String environmentName,
        EnvironmentType environmentType,
        UUID clusterConnectionId,
        String clusterName,
        UUID namespaceBindingId,
        String namespaceName,
        String deploymentName,
        String serviceName,
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
        long runAsUser,
        String configChecksumSha256,
        Instant updatedAt
) {
}
