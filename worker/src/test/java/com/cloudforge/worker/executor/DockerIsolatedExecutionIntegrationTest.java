package com.cloudforge.worker.executor;

import com.cloudforge.worker.config.WorkerAgentConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real local Docker isolated container execution verification.
 *
 * <p>Executes actual hardened {@code alpine:3.20} containers whenever a Docker Engine
 * daemon is reachable (including GitHub Actions {@code ubuntu-latest} CI). Skips cleanly
 * with an explicit assumption message when Docker Engine is not running locally.</p>
 */
class DockerIsolatedExecutionIntegrationTest {

    private DockerIsolatedExecutionAdapter adapter;

    @BeforeEach
    void setUp() {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "docker-it-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "docker-it-ephemeral-secret-99999",
                "CLOUDFORGE_EXECUTOR_IMAGE", "alpine:3.20",
                "CLOUDFORGE_EXECUTOR_CPU_LIMIT", "0.5",
                "CLOUDFORGE_EXECUTOR_MEMORY_LIMIT", "128m",
                "CLOUDFORGE_EXECUTOR_PIDS_LIMIT", "64"
        ));
        adapter = new DockerIsolatedExecutionAdapter(config);
        Assumptions.assumeTrue(
                adapter.isAvailable(),
                "Skipping live Docker container execution test because Docker daemon is not running in this environment"
        );
    }

    @Test
    void executesAllThreeAllowlistedOperationsInsideHardenedIsolatedContainersAndCleansUp() {
        for (String op : List.of("ENV_VALIDATION", "CONTROLLED_SMOKE_TEST", "WORKER_DIAGNOSTICS")) {
            UUID attemptId = UUID.randomUUID();
            String containerName = "cf-exec-" + attemptId.toString().replace("-", "").substring(0, 12);
            List<String> capturedLogs = Collections.synchronizedList(new ArrayList<>());

            IsolatedExecutionAdapter.ExecutionResult result = adapter.execute(
                    new IsolatedExecutionAdapter.ExecutionRequest(
                            attemptId, UUID.randomUUID(), UUID.randomUUID(),
                            op.toLowerCase(), op, 1, 30, 100, 32768
                    ),
                    new AtomicBoolean(false),
                    (stream, message, ts) -> capturedLogs.add(stream + ":" + message)
            );

            assertThat(result.resultStatus()).as("Result status for " + op).isEqualTo("SUCCEEDED");
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(result.terminationConfirmed()).isTrue();
            assertThat(adapter.verifyContainerRemoved(containerName)).isTrue();
            assertThat(capturedLogs).isNotEmpty();

            if ("ENV_VALIDATION".equals(op)) {
                assertThat(capturedLogs).anyMatch(l -> l.contains("uid=65534"));
                assertThat(capturedLogs).anyMatch(l -> l.contains("Read-only root filesystem verified"));
            }
            if ("WORKER_DIAGNOSTICS".equals(op)) {
                assertThat(capturedLogs).anyMatch(l -> l.contains("Verified no Docker socket present inside container"));
            }
        }
    }

    @Test
    void enforcesRealContainerExecutionTimeoutAndCancellationWithConfirmedCleanup() {
        // 1. Timeout enforcement on real container
        UUID timeoutAttempt = UUID.randomUUID();
        String timeoutContainer = "cf-exec-" + timeoutAttempt.toString().replace("-", "").substring(0, 12);
        IsolatedExecutionAdapter.ExecutionResult timeoutResult = adapter.executeContainerArgs(
                new IsolatedExecutionAdapter.ExecutionRequest(
                        timeoutAttempt, UUID.randomUUID(), UUID.randomUUID(),
                        "timeout-probe", "WORKER_DIAGNOSTICS", 1, 2, 50, 8192
                ),
                List.of("/bin/sh", "-c", "echo starting-sleep; sleep 20"),
                new AtomicBoolean(false),
                (s, m, t) -> { }
        );
        assertThat(timeoutResult.resultStatus()).isEqualTo("TIMED_OUT");
        assertThat(timeoutResult.failureCategory()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(timeoutResult.terminationConfirmed()).isTrue();
        assertThat(adapter.verifyContainerRemoved(timeoutContainer)).isTrue();

        // 2. Active cancellation enforcement on real container
        UUID cancelAttempt = UUID.randomUUID();
        String cancelContainer = "cf-exec-" + cancelAttempt.toString().replace("-", "").substring(0, 12);
        AtomicBoolean cancelSignal = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(1000);
                cancelSignal.set(true);
            } catch (InterruptedException ignored) {
            }
        });
        canceller.start();

        IsolatedExecutionAdapter.ExecutionResult cancelResult = adapter.executeContainerArgs(
                new IsolatedExecutionAdapter.ExecutionRequest(
                        cancelAttempt, UUID.randomUUID(), UUID.randomUUID(),
                        "cancel-probe", "WORKER_DIAGNOSTICS", 1, 30, 50, 8192
                ),
                List.of("/bin/sh", "-c", "echo running-until-cancelled; sleep 20"),
                cancelSignal,
                (s, m, t) -> { }
        );
        assertThat(cancelResult.resultStatus()).isEqualTo("CANCELLED");
        assertThat(cancelResult.terminationConfirmed()).isTrue();
        assertThat(adapter.verifyContainerRemoved(cancelContainer)).isTrue();
    }
}
