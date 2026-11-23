package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceSummaryDto(
        UUID id,
        String name,
        String slug,
        String description,
        boolean personal,
        WorkspaceRole role,
        long memberCount,
        long projectCount,
        long connectedRepositoryCount,
        Instant createdAt,
        Instant updatedAt
) {
}
