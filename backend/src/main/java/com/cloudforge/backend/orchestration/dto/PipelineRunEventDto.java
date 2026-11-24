package com.cloudforge.backend.orchestration.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PipelineRunEventDto(
        UUID id,
        UUID pipelineRunId,
        UUID stageRunId,
        String stageKey,
        String eventType,
        String fromStatus,
        String toStatus,
        String actorUsername,
        String message,
        Map<String, Object> metadata,
        Instant occurredAt
) {
}
