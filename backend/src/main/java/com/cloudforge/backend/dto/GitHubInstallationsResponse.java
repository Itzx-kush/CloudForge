package com.cloudforge.backend.dto;

import java.util.List;

public record GitHubInstallationsResponse(
        boolean githubAppConfigured,
        String githubAppSlug,
        List<GitHubInstallationDto> installations
) {
}
