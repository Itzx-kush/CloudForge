package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciPublishAttemptStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record WorkerPublishResultReportRequest(
        @NotBlank @Size(max = 128) String leaseToken,
        @Positive long fencingGeneration,
        @NotNull OciPublishAttemptStatus status,
        @Size(max = 128) String reportedDigest,
        @Size(max = 128) String verifiedDigest,
        @Size(max = 128) String imageMediaType,
        @Size(max = 64) String platform,
        Long imageSizeBytes,
        boolean verified,
        @Size(max = 40) String failureCategory,
        @Size(max = 500) String errorSummary,
        Instant completedAt
) {
}
