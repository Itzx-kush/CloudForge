package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KubernetesDeploymentStatusDto(
        UUID deploymentId,
        UUID workspaceId,
        String clusterName,
        String namespaceName,
        String deploymentName,
        String serviceName,
        String immutableImageReference,
        String manifestDigest,
        KubernetesDeploymentStatus status,
        boolean applyInitiated,
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
        String failureCategory,
        String errorSummary,
        Instant cancellationRequestedAt,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
) {
}
