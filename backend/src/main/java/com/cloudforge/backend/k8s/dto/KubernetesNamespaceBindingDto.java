package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNetworkPolicyMode;
import com.cloudforge.backend.k8s.entity.KubernetesPodSecurityStandard;

import java.time.Instant;
import java.util.UUID;

public record KubernetesNamespaceBindingDto(
        UUID id,
        UUID workspaceId,
        UUID clusterConnectionId,
        String clusterName,
        UUID projectId,
        String projectName,
        UUID environmentId,
        String environmentName,
        EnvironmentType environmentType,
        String namespaceName,
        String workloadServiceAccountName,
        String imagePullSecretName,
        KubernetesPodSecurityStandard podSecurityStandard,
        KubernetesNetworkPolicyMode networkPolicyMode,
        boolean resourceQuotaConfigured,
        boolean limitRangeConfigured,
        KubernetesNamespaceBindingStatus status,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
