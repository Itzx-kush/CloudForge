package com.cloudforge.worker.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerTelemetryTest {

    @Test
    void writesAndAtomicallyUpdatesPrometheusTextfileSnapshotCrossPlatform(@TempDir Path tempDir) throws Exception {
        Path metricsDir = tempDir.resolve("worker-metrics");
        WorkerTelemetry telemetry = new WorkerTelemetry(metricsDir.toString());

        telemetry.completed(1_500_000_000L);
        telemetry.heartbeat(true, 1);

        Path promFile = metricsDir.resolve("cloudforge.prom");
        assertThat(promFile).exists();
        String content = Files.readString(promFile, StandardCharsets.UTF_8);
        assertThat(content)
                .contains("cloudforge_worker_active_assignments 1")
                .contains("cloudforge_worker_heartbeat_failures_total 0")
                .contains("cloudforge_worker_assignments_total 1")
                .contains("cloudforge_worker_assignment_seconds_total 1.500000");

        telemetry.heartbeat(false, 0);
        String updated = Files.readString(promFile, StandardCharsets.UTF_8);
        assertThat(updated)
                .contains("cloudforge_worker_active_assignments 0")
                .contains("cloudforge_worker_heartbeat_failures_total 1");
    }

    @Test
    void doesNotFailWhenDirectoryIsUnsetOrUnwritable(@TempDir Path tempDir) throws Exception {
        WorkerTelemetry disabled = new WorkerTelemetry("");
        disabled.completed(100L);
        disabled.heartbeat(true, 0);

        Path regularFile = tempDir.resolve("not-a-directory");
        Files.writeString(regularFile, "blocked", StandardCharsets.UTF_8);
        WorkerTelemetry invalidDir = new WorkerTelemetry(regularFile.toString());
        invalidDir.heartbeat(false, 0);
        assertThat(Files.readString(regularFile, StandardCharsets.UTF_8)).isEqualTo("blocked");
    }
}
