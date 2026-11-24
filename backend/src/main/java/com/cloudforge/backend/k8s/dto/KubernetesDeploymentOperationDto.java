package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KubernetesDeploymentOperationDto(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String projectName,
        UUID environmentId,
        String environmentName,
        EnvironmentType environmentType,
        UUID clusterConnectionId,
        String clusterName,
        String clusterIdentifier,
        UUID namespaceBindingId,
        String namespaceName,
        UUID deploymentConfigId,
        UUID ociArtifactId,
        UUID pipelineRunId,
        Integer pipelineRunNumber,
        UUID stageRunId,
        UUID attemptId,
        String deploymentName,
        String serviceName,
        String immutableImageReference,
        String manifestDigest,
        String sourceCommitSha,
        String manifestChecksumSha256,
        Map<String, Object> configSnapshot,
        int requestedReplicas,
        int observedReplicas,
        int updatedReplicas,
        int readyReplicas,
        int availableReplicas,
        Long observedGeneration,
        String k8sDeploymentUid,
        String k8sServiceUid,
        List<Map<String, Object>> rolloutConditions,
        List<Map<String, Object>> podDiagnostics,
        KubernetesDeploymentStatus status,
        boolean applyInitiated,
        String assignedWorkerId,
        long fencingGeneration,
        String requestedByUsername,
        String cancelledByUsername,
        Instant cancellationRequestedAt,
        String failureCategory,
        String errorSummary,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
