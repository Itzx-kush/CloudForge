package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.k8s.entity.KubernetesNetworkPolicyMode;
import com.cloudforge.backend.k8s.entity.KubernetesPodSecurityStandard;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateNamespaceBindingRequest(
        @NotNull UUID projectId,
        @NotNull UUID environmentId,
        @Size(max = 63) String namespaceName,
        @Size(max = 128) String workloadServiceAccountName,
        @Size(max = 128) String imagePullSecretName,
        KubernetesPodSecurityStandard podSecurityStandard,
        KubernetesNetworkPolicyMode networkPolicyMode,
        Boolean resourceQuotaConfigured,
        Boolean limitRangeConfigured
) {
}
