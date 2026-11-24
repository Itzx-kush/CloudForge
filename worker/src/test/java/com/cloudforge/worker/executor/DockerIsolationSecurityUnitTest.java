package com.cloudforge.worker.executor;

import com.cloudforge.worker.config.WorkerAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerIsolationSecurityUnitTest {

    @Test
    void enforcesWorkerTokenAndTrustedImageAllowlistInConfig() {
        // Missing token rejected
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "local-worker-1"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLOUDFORGE_WORKER_TOKEN");

        // Arbitrary untrusted Docker image rejected
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_EXECUTOR_IMAGE", "evilregistry.io/attacker:latest"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");

        // Pinned digest on trusted image accepted
        WorkerAgentConfig valid = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_EXECUTOR_IMAGE", "alpine:3.20"
        ));
        assertThat(valid.executorImage()).isEqualTo("alpine:3.20");
    }

    @Test
    void buildsHardenedDockerCommandWithNoPrivilegedOrSocketMountsAndRejectsUnlistedOperations() {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_EXECUTOR_IMAGE", "alpine:3.20",
                "CLOUDFORGE_EXECUTOR_CPU_LIMIT", "0.5",
                "CLOUDFORGE_EXECUTOR_MEMORY_LIMIT", "128m",
                "CLOUDFORGE_EXECUTOR_PIDS_LIMIT", "64"
        ));
        DockerIsolatedExecutionAdapter adapter = new DockerIsolatedExecutionAdapter(config);

        UUID attemptId = UUID.randomUUID();
        IsolatedExecutionAdapter.ExecutionRequest request = new IsolatedExecutionAdapter.ExecutionRequest(
                attemptId, UUID.randomUUID(), UUID.randomUUID(), "source",
                "ENV_VALIDATION", 1, 60, 100, 16384
        );

        List<String> cmd = adapter.buildDockerRunCommand("cf-exec-test1234", request);
        assertThat(cmd)
                .contains("--rm", "--user", "65534:65534", "--cap-drop=ALL",
                        "no-new-privileges:true", "--read-only", "--network=none",
                        "--cpus", "0.5", "--memory", "128m", "--memory-swap", "128m",
                        "--pids-limit", "64", "alpine:3.20")
                .doesNotContain("--privileged", "-v", "--volume", "/var/run/docker.sock");
        assertThat(String.join(" ", cmd))
                .doesNotContain("docker.sock")
                .doesNotContain("valid-ephemeral-worker-secret-9999");

        // Unsupported operations strictly rejected
        IsolatedExecutionAdapter.ExecutionRequest badOp = new IsolatedExecutionAdapter.ExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "build",
                "ARBITRARY_SHELL_BUILD", 1, 60, 100, 16384
        );
        assertThatThrownBy(() -> adapter.buildDockerRunCommand("cf-exec-bad", badOp))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
