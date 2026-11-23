package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.UUID;

public record UserProfileDto(
        UUID id,
        Long githubUserId,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        String profileUrl,
        Instant lastLoginAt,
        Instant createdAt
) {
}
