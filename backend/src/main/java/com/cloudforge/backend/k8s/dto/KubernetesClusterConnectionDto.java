package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesClusterType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Safe cluster metadata response exposed to workspace users.
 * Never returns kubeconfigs, bearer tokens, or private keys.
 */
public record KubernetesClusterConnectionDto(
        UUID id,
        UUID workspaceId,
        String name,
        String slug,
        String clusterIdentifier,
        KubernetesClusterType clusterType,
        EnvironmentType environmentCategory,
        String apiServerUrl,
        String caCertificateFingerprintSha256,
        UUID credentialProjectId,
        String credentialProjectName,
        UUID credentialEnvironmentId,
        String credentialEnvironmentName,
        UUID bearerTokenSecretId,
        String bearerTokenSecretName,
        String serviceAccountName,
        boolean allowLocalLoopback,
        boolean productionApprovalRequired,
        KubernetesClusterStatus connectionStatus,
        Instant validationRequestedAt,
        Instant lastValidatedAt,
        String lastValidationWorkerId,
        String lastValidationError,
        String kubernetesVersion,
        Map<String, Object> supportedCapabilities,
        List<KubernetesNamespaceBindingDto> namespaceBindings,
        String description,
        String createdByUsername,
        String updatedByUsername,
        Instant createdAt,
        Instant updatedAt
) {
}
