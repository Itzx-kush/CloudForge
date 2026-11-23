package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.ProjectStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectDetailDto(
        UUID id,
        UUID workspaceId,
        String workspaceName,
        String name,
        String slug,
        String description,
        LinkedRepositoryDto linkedRepository,
        String defaultBranch,
        ProjectStatus status,
        String createdByUsername,
        List<ProjectEnvironmentDto> environments,
        Instant createdAt,
        Instant updatedAt
) {
}
