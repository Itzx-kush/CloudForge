package com.cloudforge.backend.oci.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Ephemeral lease-bound registry credentials delivered strictly to an authenticated
 * OCI_BUILDKIT worker during an active OCI_IMAGE_PUBLISH attempt.
 */
public record WorkerRegistryCredentialResponse(
        UUID attemptId,
        UUID registryConnectionId,
        String registryHost,
        String scheme,
        String username,
        String password,
        boolean anonymous,
        Instant deliveredAt
) {
    @Override
    public String toString() {
        return "WorkerRegistryCredentialResponse[attemptId=" + attemptId
                + ", registryHost=" + registryHost
                + ", anonymous=" + anonymous
                + ", credentials=[REDACTED]]";
    }
}
