package com.cloudforge.worker.executor;

import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.security.WorkerSecretRedactor;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    @Test
    void enforcesHttpsForSecretDeliveryNeverPlacesSecretsInDockerCliAndRedactsEncodedAndBoundaryLeaks() {
        // Non-loopback HTTP with secret delivery enabled fails closed
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "http://control-plane.internal:8080",
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "true"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");

        // Loopback requires explicit opt-in
        assertThatThrownBy(() -> WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "http://127.0.0.1:8080",
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "true"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");

        // Valid opt-in loopback or HTTPS accepted, and buildDockerRunCommand never includes secret values or --env secrets
        WorkerAgentConfig secretEnabledConfig = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://cloudforge-control.internal",
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999",
                "CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "true"
        ));
        DockerIsolatedExecutionAdapter adapter = new DockerIsolatedExecutionAdapter(secretEnabledConfig);
        String secretVal = "cf_ephemeral_docker_secret_987654321";
        IsolatedExecutionAdapter.ExecutionRequest secReq = new IsolatedExecutionAdapter.ExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "verify",
                "ENV_VALIDATION", 1, 60, 100, 16384,
                Map.of("RUNTIME_KEY", secretVal)
        );
        List<String> cmd = adapter.buildDockerRunCommand("cf-exec-sec1", secReq);
        assertThat(cmd).contains("-i");
        assertThat(String.join(" ", cmd))
                .doesNotContain(secretVal)
                .doesNotContain("RUNTIME_KEY");

        // Disabled worker refuses ephemeral secret execution fail closed
        WorkerAgentConfig disabledConfig = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "local-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "valid-ephemeral-worker-secret-9999"
        ));
        DockerIsolatedExecutionAdapter disabledAdapter = new DockerIsolatedExecutionAdapter(disabledConfig);
        IsolatedExecutionAdapter.ExecutionResult deniedResult = disabledAdapter.execute(
                secReq, new java.util.concurrent.atomic.AtomicBoolean(false), (s, m, t) -> {}
        );
        assertThat(deniedResult.resultStatus()).isEqualTo("FAILED");
        assertThat(deniedResult.failureCategory()).isEqualTo("SECRET_DELIVERY_DISABLED");

        // WorkerSecretRedactor scrubs raw, Base64, URL-encoded, and cross-chunk split secrets
        WorkerSecretRedactor redactor = new WorkerSecretRedactor();
        redactor.addSecrets(List.of(secretVal));
        String b64 = Base64.getEncoder().encodeToString(secretVal.getBytes(StandardCharsets.UTF_8));
        String url = URLEncoder.encode(secretVal, StandardCharsets.UTF_8);
        assertThat(redactor.redact("raw=" + secretVal + " b64=" + b64 + " url=" + url))
                .doesNotContain(secretVal)
                .doesNotContain(b64)
                .doesNotContain(url)
                .contains("[REDACTED]");

        String p1 = secretVal.substring(0, 14);
        String p2 = secretVal.substring(14);
        var boundary = redactor.redactAcrossBoundary("head " + p1, p2 + " tail");
        assertThat(boundary.previousModified()).isTrue();
        assertThat(boundary.previousMessage() + boundary.currentMessage())
                .doesNotContain(secretVal)
                .contains("[REDACTED]");
    }
}
