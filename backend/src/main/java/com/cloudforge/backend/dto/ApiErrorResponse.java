package com.cloudforge.backend.dto;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Map<String, String> fieldErrors
) {
    public ApiErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path,
            String requestId
    ) {
        this(timestamp, status, error, message, path, requestId, Map.of());
    }
}
