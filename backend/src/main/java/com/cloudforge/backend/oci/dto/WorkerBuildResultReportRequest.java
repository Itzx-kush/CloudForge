package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciBuildStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record WorkerBuildResultReportRequest(
        @NotBlank @Size(max = 128) String leaseToken,
        @Positive long fencingGeneration,
        @NotNull OciBuildStatus status,
        @Size(max = 128) String builtManifestDigest,
        @Size(max = 128) String imageMediaType,
        Long imageSizeBytes,
        UUID ociHandoffId,
        @Size(max = 40) String failureCategory,
        @Size(max = 500) String errorSummary,
        Instant startedAt,
        Instant completedAt
) {
}
