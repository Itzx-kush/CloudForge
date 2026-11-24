package com.cloudforge.backend.oci.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OciArtifactEventDto(
        UUID id,
        UUID pipelineRunId,
        UUID buildRecordId,
        UUID artifactId,
        UUID publishAttemptId,
        String eventType,
        String actorLabel,
        String message,
        Map<String, Object> metadata,
        Instant occurredAt
) {
}
