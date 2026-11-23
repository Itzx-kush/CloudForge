package com.cloudforge.backend.repository;

public record DatabaseProbeResult(
        boolean connected,
        String engine,
        long responseTimeMs,
        String message
) {
}
