package com.cloudforge.backend.secrets.dto;

import java.util.List;

public record EnvironmentSecretDetailDto(
        EnvironmentSecretSummaryDto secret,
        List<EnvironmentSecretVersionDto> versions
) {
}
