package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProjectRequest(
        @NotBlank(message = "Project name is required")
        @Size(min = 2, max = 120, message = "Project name must be between 2 and 120 characters")
        String name,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        UUID linkedRepositoryId,

        @Size(min = 1, max = 150, message = "Default branch must be between 1 and 150 characters")
        @Pattern(
                regexp = "^[A-Za-z0-9._/-]+$",
                message = "Default branch may only contain letters, numbers, dots, underscores, slashes, and hyphens"
        )
        String defaultBranch,

        ProjectStatus status
) {
}
