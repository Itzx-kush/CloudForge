package com.cloudforge.backend.dto;

import com.cloudforge.backend.entity.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceMemberDto(
        UUID id,
        UUID userId,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        WorkspaceRole role,
        Instant joinedAt
) {
}
