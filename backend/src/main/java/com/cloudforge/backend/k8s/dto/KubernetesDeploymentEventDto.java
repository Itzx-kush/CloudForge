package com.cloudforge.backend.k8s.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record KubernetesDeploymentEventDto(
        UUID id,
        UUID deploymentOperationId,
        String eventType,
        String fromStatus,
        String toStatus,
        String actorLabel,
        String message,
        Map<String, Object> metadata,
        Instant occurredAt
) {
}
