package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkspaceRole;
import jakarta.validation.constraints.NotNull;

public record UpdateWorkspaceMemberRoleRequest(
        @NotNull(message = "Workspace role is required")
        WorkspaceRole role
) {
}
