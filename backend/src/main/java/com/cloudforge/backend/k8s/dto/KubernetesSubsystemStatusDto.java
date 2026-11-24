package com.cloudforge.backend.k8s.dto;

import java.util.List;

public record KubernetesSubsystemStatusDto(
        boolean kubernetesEnabled,
        boolean allowLocalDevClusters,
        boolean allowProductionDeployments,
        List<String> allowedApiHosts,
        int maxReplicas,
        int maxDeploymentTimeoutSeconds,
        long configuredClusters,
        long validatedClusters,
        long activeNamespaceBindings,
        long totalDeployments,
        long runningDeployments,
        long succeededDeployments,
        long failedDeployments,
        long activeDeployWorkers
) {
}
