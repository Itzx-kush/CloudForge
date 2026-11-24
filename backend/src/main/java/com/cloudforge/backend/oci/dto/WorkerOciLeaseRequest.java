package com.cloudforge.backend.oci.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record WorkerOciLeaseRequest(
        @NotBlank @Size(max = 128) String leaseToken,
        @Positive long fencingGeneration
) {
    @Override
    public String toString() {
        return "WorkerOciLeaseRequest[fencingGeneration=" + fencingGeneration + "]";
    }
}
