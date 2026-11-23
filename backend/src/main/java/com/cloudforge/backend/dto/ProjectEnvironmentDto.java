package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.EnvironmentType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProjectEnvironmentDto(
        UUID id,
        UUID projectId,
        String name,
        EnvironmentType environmentType,
        String branchOverride,
        Map<String, String> configVariables,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
