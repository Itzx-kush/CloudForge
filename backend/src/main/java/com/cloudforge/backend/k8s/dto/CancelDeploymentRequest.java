package com.cloudforge.backend.k8s.dto;

import jakarta.validation.constraints.Size;

public record CancelDeploymentRequest(
        @Size(max = 400) String reason
) {
}
