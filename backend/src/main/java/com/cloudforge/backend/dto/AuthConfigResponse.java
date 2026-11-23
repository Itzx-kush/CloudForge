package com.cloudforge.backend.dto;

public record AuthConfigResponse(
        boolean githubOAuthConfigured,
        boolean githubAppConfigured,
        String githubAppSlug,
        String loginUrl
) {
}
