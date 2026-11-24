package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciRegistryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateRegistryConnectionRequest(
        @NotBlank @Size(min = 2, max = 120) String name,
        @NotBlank @Size(max = 255) String registryHost,
        @Size(max = 8) String scheme,
        @NotNull OciRegistryType registryType,
        @NotBlank @Size(min = 1, max = 128) String allowedNamespace,
        UUID credentialProjectId,
        UUID credentialEnvironmentId,
        UUID usernameSecretId,
        UUID passwordSecretId,
        Boolean allowInsecureLocal,
        @Size(max = 500) String description
) {
}
