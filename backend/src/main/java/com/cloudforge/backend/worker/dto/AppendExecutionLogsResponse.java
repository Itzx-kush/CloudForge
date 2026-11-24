package com.cloudforge.backend.worker.dto;

public record AppendExecutionLogsResponse(
        int acceptedCount,
        int droppedCount,
        boolean limitReached,
        int lastSequenceNumber
) {
}
