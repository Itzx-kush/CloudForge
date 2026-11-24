package com.cloudforge.backend.secrets.dto;

import java.util.List;
import java.util.Map;

public record KeyUsageSummaryDto(
        boolean enabled,
        boolean available,
        String providerType,
        String activeKeyId,
        List<String> configuredKeyIds,
        long totalSecretVersions,
        long versionsNeedingRotation,
        Map<String, Long> versionsByKekId,
        boolean rotationRunning,
        List<KeyRotationRunDto> recentRuns,
        String statusDiagnostic
) {
}
