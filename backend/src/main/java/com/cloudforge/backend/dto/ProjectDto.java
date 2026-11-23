package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectDto(
        UUID id,
        UUID workspaceId,
        String name,
        String slug,
        String description,
        LinkedRepositoryDto linkedRepository,
        String defaultBranch,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
