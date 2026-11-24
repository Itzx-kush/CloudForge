package com.cloudforge.backend.oci.dto;

import java.util.List;

public record OciSubsystemStatusDto(
        boolean ociEnabled,
        boolean allowLocalDevRegistry,
        List<String> allowedPlatforms,
        List<String> allowedRegistryHosts,
        long activeRegistryConnections,
        long totalBuilds,
        long succeededBuilds,
        long verifiedArtifacts,
        long activeBuildWorkers
) {
}
