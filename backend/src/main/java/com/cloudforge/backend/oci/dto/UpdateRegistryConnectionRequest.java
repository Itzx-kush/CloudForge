package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateRegistryConnectionRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(min = 1, max = 128) String allowedNamespace,
        UUID credentialProjectId,
        UUID credentialEnvironmentId,
        UUID usernameSecretId,
        UUID passwordSecretId,
        OciRegistryStatus status,
        @Size(max = 500) String description
) {
}
