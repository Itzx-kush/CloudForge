package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventDto(
        UUID id,
        UUID workspaceId,
        String actorUsername,
        String action,
        String resourceType,
        String resourceId,
        String summary,
        Map<String, Object> metadata,
        String requestId,
        Instant createdAt
) {
}
