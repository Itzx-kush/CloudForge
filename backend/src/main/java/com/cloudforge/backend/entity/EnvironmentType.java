package com.cloudforge.backend.entity;

public enum EnvironmentType {
    DEVELOPMENT("Development"),
    STAGING("Staging"),
    PRODUCTION("Production");

    private final String defaultDisplayName;

    EnvironmentType(String defaultDisplayName) {
        this.defaultDisplayName = defaultDisplayName;
    }

    public String getDefaultDisplayName() {
        return defaultDisplayName;
    }
}
