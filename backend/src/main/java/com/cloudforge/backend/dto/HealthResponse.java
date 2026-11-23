package com.cloudforge.backend.dto;

import java.time.Instant;

public record HealthResponse(
        String application,
        String version,
        String phase,
        String status,
        DatabaseHealthDto database,
        Instant timestamp
) {
}
