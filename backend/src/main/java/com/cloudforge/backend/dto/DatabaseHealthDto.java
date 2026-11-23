package com.cloudforge.backend.dto;

public record DatabaseHealthDto(
        String status,
        String engine,
        Long responseTimeMs,
        String message
) {
}
