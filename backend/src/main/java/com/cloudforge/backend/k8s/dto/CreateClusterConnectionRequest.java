package com.cloudforge.backend.k8s.dto;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.k8s.entity.KubernetesClusterType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Registers an operator-approved Kubernetes cluster connection using strictly a
 * Phase 3.4 encrypted secret reference for the narrowly scoped deployment ServiceAccount
 * bearer token (never plaintext tokens or admin kubeconfigs).
 */
public record CreateClusterConnectionRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(min = 2, max = 128) String clusterIdentifier,
        @NotNull KubernetesClusterType clusterType,
        @NotNull EnvironmentType environmentCategory,
        @NotBlank @Size(max = 512) String apiServerUrl,
        @NotBlank @Size(max = 32768) String caCertificatePem,
        @NotNull UUID credentialProjectId,
        @NotNull UUID credentialEnvironmentId,
        @NotNull UUID bearerTokenSecretId,
        @NotBlank @Size(min = 2, max = 128) String serviceAccountName,
        Boolean allowLocalLoopback,
        Boolean productionApprovalRequired,
        @Size(max = 500) String description
) {
}
