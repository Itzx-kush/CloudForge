package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import com.cloudforge.backend.oci.entity.OciRegistryType;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only view of a workspace OCI registry connection.
 * Never exposes plaintext registry passwords or tokens.
 */
public record OciRegistryConnectionDto(
        UUID id,
        UUID workspaceId,
        String name,
        String slug,
        String registryHost,
        String scheme,
        OciRegistryType registryType,
        String allowedNamespace,
        UUID credentialProjectId,
        String credentialProjectName,
        UUID credentialEnvironmentId,
        String credentialEnvironmentName,
        UUID usernameSecretId,
        String usernameSecretName,
        UUID passwordSecretId,
        String passwordSecretName,
        boolean credentialsConfigured,
        boolean allowInsecureLocal,
        OciRegistryStatus status,
        String description,
        String createdByUsername,
        String updatedByUsername,
        Instant createdAt,
        Instant updatedAt
) {
}
