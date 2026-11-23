package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkspaceRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddWorkspaceMemberRequest(
        @NotBlank(message = "GitHub username is required")
        @Size(max = 100, message = "Username must be at most 100 characters")
        String username,

        @NotNull(message = "Workspace role is required")
        WorkspaceRole role
) {
}
