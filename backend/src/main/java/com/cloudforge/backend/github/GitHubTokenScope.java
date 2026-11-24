package com.cloudforge.backend.github;

import java.util.Map;

/**
 * Least-privilege GitHub App installation token permission scopes.
 * Repository discovery continues to request only metadata:read so installations
 * that have not yet approved Actions: Read keep working cleanly.
 */
public enum GitHubTokenScope {

    REPOSITORY_METADATA(Map.of("metadata", "read")),
    ACTIONS_READ(Map.of("metadata", "read", "actions", "read")),
    CONTENTS_READ(Map.of("metadata", "read", "contents", "read"));

    private final Map<String, String> permissions;

    GitHubTokenScope(Map<String, String> permissions) {
        this.permissions = permissions;
    }

    public Map<String, String> permissions() {
        return permissions;
    }
}
