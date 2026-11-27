package com.cloudforge.backend.integration;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Enables PostgreSQL-backed regression tests when a local PostgreSQL 17 instance
 * is reachable, and skips gracefully in environments without PostgreSQL running
 * or when a local database has not been migrated to the current schema version
 * without {@code TEST_POSTGRES_RUN_FLYWAY=true}.
 */
final class PostgresAvailableCondition implements ExecutionCondition {

    static final String DEFAULT_URL = "jdbc:postgresql://127.0.0.1:5432/cloudforge";
    static final String DEFAULT_USER = "cloudforge";
    static final String DEFAULT_PASSWORD = "cloudforge_local_dev_password";
    private static final int REQUIRED_SCHEMA_VERSION = 11;

    static String resolveUrl() {
        return firstNonBlank(System.getenv("TEST_POSTGRES_URL"), System.getenv("SPRING_DATASOURCE_URL"), DEFAULT_URL);
    }

    static String resolveUser() {
        return firstNonBlank(System.getenv("TEST_POSTGRES_USER"), System.getenv("SPRING_DATASOURCE_USERNAME"), DEFAULT_USER);
    }

    static String resolvePassword() {
        return firstNonBlank(System.getenv("TEST_POSTGRES_PASSWORD"), System.getenv("SPRING_DATASOURCE_PASSWORD"), DEFAULT_PASSWORD);
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        String url = resolveUrl();
        String user = resolveUser();
        String password = resolvePassword();
        boolean runFlyway = "true".equalsIgnoreCase(System.getenv("TEST_POSTGRES_RUN_FLYWAY"));
        try {
            DriverManager.setLoginTimeout(2);
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                if (!conn.isValid(2)) {
                    return ConditionEvaluationResult.disabled("PostgreSQL connection was not valid");
                }
                if (!runFlyway) {
                    int currentVersion = readAppliedFlywayVersion(conn);
                    if (currentVersion < REQUIRED_SCHEMA_VERSION) {
                        return ConditionEvaluationResult.disabled(
                                "Skipping PostgreSQL regression test: database at " + url
                                        + " is at Flyway schema version " + currentVersion
                                        + " (< " + REQUIRED_SCHEMA_VERSION
                                        + ") and TEST_POSTGRES_RUN_FLYWAY is not true"
                        );
                    }
                }
                return ConditionEvaluationResult.enabled("PostgreSQL is reachable at " + url);
            }
        } catch (Exception ex) {
            return ConditionEvaluationResult.disabled("Skipping PostgreSQL regression test (PostgreSQL unavailable at " + url + "): " + ex.getMessage());
        }
    }

    private static int readAppliedFlywayVersion(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COALESCE(MAX(CAST(version AS INTEGER)), 0) FROM flyway_schema_history WHERE success = true AND version ~ '^[0-9]+$'"
             )) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }
}
