package com.cloudforge.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAllMigrationsAndCreatesExpectedTables() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSizeGreaterThanOrEqualTo(6);

        List<String> expectedTables = List.of(
                "users",
                "workspaces",
                "workspace_members",
                "github_installations",
                "linked_repositories",
                "projects",
                "project_environments",
                "audit_events",
                "webhook_deliveries",
                "github_workflows",
                "github_workflow_runs",
                "pipeline_definitions",
                "pipeline_stage_definitions",
                "pipeline_runs",
                "pipeline_stage_runs",
                "pipeline_run_events",
                "pipeline_idempotency_keys",
                "worker_nodes",
                "worker_credentials",
                "stage_execution_attempts",
                "stage_execution_logs",
                "environment_secrets",
                "environment_secret_versions",
                "pipeline_run_secret_references",
                "secret_key_rotation_runs"
        );

        for (String table : expectedTables) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_SCHEMA) = 'public' AND LOWER(TABLE_NAME) = ?",
                    Integer.class,
                    table
            );
            assertThat(count)
                    .withFailMessage("Expected table %s to exist after Flyway migrations", table)
                    .isEqualTo(1);
        }
    }
}
