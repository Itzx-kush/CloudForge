package com.cloudforge.backend.worker.dto;

import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WorkerRegistrationRequest(
        @NotBlank
        @Size(min = 3, max = 128)
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{2,127}$",
                message = "workerId must contain only letters, digits, dots, underscores, or hyphens")
        String workerId,

        @NotBlank
        @Size(min = 2, max = 120)
        String displayName,

        @NotNull
        WorkerAdapterType adapterType,

        @NotEmpty
        @Size(max = 10)
        List<@NotBlank String> supportedOperations,

        @Min(1)
        @Max(16)
        int maxConcurrency,

        @Size(max = 120)
        String hostLabel,

        @Size(max = 64)
        String agentVersion
) {
}
