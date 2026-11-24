package com.cloudforge.backend.oci.dto;

import com.cloudforge.backend.oci.entity.OciHandoffType;

import java.time.Instant;
import java.util.UUID;

public record WorkerHandoffUploadResponse(
        UUID handoffId,
        OciHandoffType handoffType,
        String contentChecksumSha256,
        long archiveSizeBytes,
        long extractedBytes,
        int fileCount,
        Instant expiresAt
) {
}
