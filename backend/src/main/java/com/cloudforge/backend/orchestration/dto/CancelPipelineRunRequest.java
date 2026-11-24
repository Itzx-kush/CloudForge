package com.cloudforge.backend.orchestration.dto;

import jakarta.validation.constraints.Size;

public record CancelPipelineRunRequest(
        @Size(max = 400, message = "Cancellation reason must be at most 400 characters")
        String reason
) {
}
