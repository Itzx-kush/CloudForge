package com.cloudforge.worker.agent;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerAgentLoopUnitTest {

    @Test
    void executesFullAgentCycleRegisterClaimAckLogsCompleteAndGracefulShutdown() {
        WorkerAgentConfig config = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "unit-worker-1",
                "CLOUDFORGE_WORKER_TOKEN", "unit-test-worker-token-12345678"
        ));

        UUID runId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        List<String> events = new ArrayList<>();
        List<ControlPlaneClient.LogEntry> uploadedLogs = new ArrayList<>();
        List<ControlPlaneClient.CompletionPayload> completions = new ArrayList<>();
        AtomicBoolean claimedOnce = new AtomicBoolean(false);

        ControlPlaneClient stubClient = new ControlPlaneClient() {
            @Override
            public void register(RegistrationPayload payload) {
                events.add("REGISTER:" + payload.workerId());
            }

            @Override
            public HeartbeatResult heartbeat(String workerId, List<ActiveLease> activeLeases) {
                events.add("HEARTBEAT:" + activeLeases.size());
                return new HeartbeatResult(workerId, "AVAILABLE", Instant.now(), List.of());
            }

            @Override
            public Optional<ClaimedStageLease> claimNextStage(String workerId) {
                if (!claimedOnce.compareAndSet(false, true)) {
                    return Optional.empty();
                }
                events.add("CLAIM:" + workerId);
                return Optional.of(new ClaimedStageLease(
                        new StageInfo(
                                UUID.randomUUID(), UUID.randomUUID(), runId, stageId,
                                "smoke", "Smoke Test", "TEST", 0, 1,
                                "org/repo", "main", "abc1234", 60, 0, 1
                        ),
                        attemptId, workerId, "lease-tok-1", 1L,
                        "CONTROLLED_SMOKE_TEST", true,
                        Instant.now(), Instant.now().plusSeconds(60), 10, 100, 16384
                ));
            }

            @Override
            public void acknowledgeRunning(String workerId, UUID attId, String leaseToken, long fence, Instant startedAt) {
                events.add("RUNNING:" + attId);
            }

            @Override
            public void appendLogs(String workerId, UUID attId, String leaseToken, long fence, List<LogEntry> entries) {
                uploadedLogs.addAll(entries);
            }

            @Override
            public void completeAttempt(String workerId, UUID attId, CompletionPayload payload) {
                events.add("COMPLETE:" + payload.resultStatus());
                completions.add(payload);
            }

            @Override
            public void updateStatus(String workerId, String status) {
                events.add("STATUS:" + status);
            }
        };

        IsolatedExecutionAdapter stubAdapter = new IsolatedExecutionAdapter() {
            @Override
            public String adapterType() {
                return "MOCK_TEST";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public ExecutionResult execute(ExecutionRequest request, AtomicBoolean cancelSignal, LogLineConsumer logConsumer) {
                Instant start = Instant.now();
                logConsumer.accept("STDOUT", "[CONTROLLED_SMOKE_TEST] deterministic ok", start);
                return new ExecutionResult(
                        "SUCCEEDED", "NONE", 0, "Smoke test succeeded",
                        Map.of("durationMs", 15), start, Instant.now(), true
                );
            }
        };

        try (WorkerAgentLoop loop = new WorkerAgentLoop(config, stubClient, stubAdapter)) {
            loop.registerWorker();
            assertThat(loop.runSingleClaimCycle()).isTrue();
            assertThat(loop.runSingleClaimCycle()).isFalse();
            loop.requestGracefulShutdown();
        }

        assertThat(events).containsExactly(
                "REGISTER:unit-worker-1",
                "CLAIM:unit-worker-1",
                "RUNNING:" + attemptId,
                "COMPLETE:SUCCEEDED",
                "STATUS:DRAINING",
                "STATUS:OFFLINE"
        );
        assertThat(uploadedLogs).hasSize(1);
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0).terminationConfirmed()).isTrue();
        assertThat(completions.get(0).exitCode()).isEqualTo(0);
    }

    @Test
    void fetchesLeaseBoundSecretsRedactsStreamedOutputAndFailsClosedWhenDeliveryDisabled() {
        String ephemeralSecret = "worker_loop_secret_token_alpha_999";
        UUID runId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        // 1. Worker with secret delivery disabled fails closed before executing secret-bound stage
        WorkerAgentConfig disabledCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_WORKER_ID", "unit-worker-sec",
                "CLOUDFORGE_WORKER_TOKEN", "unit-test-worker-token-12345678",
                "CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "false"
        ));
        List<ControlPlaneClient.CompletionPayload> disabledCompletions = new ArrayList<>();
        AtomicBoolean claimedDisabled = new AtomicBoolean(false);
        ControlPlaneClient secretLeaseClient = new ControlPlaneClient() {
            @Override public void register(RegistrationPayload payload) {}
            @Override public HeartbeatResult heartbeat(String w, List<ActiveLease> l) { return null; }
            @Override public Optional<ClaimedStageLease> claimNextStage(String w) {
                if (!claimedDisabled.compareAndSet(false, true)) return Optional.empty();
                return Optional.of(new ClaimedStageLease(
                        new StageInfo(UUID.randomUUID(), UUID.randomUUID(), runId, stageId,
                                "verify", "Verify", "SOURCE_PREP", 0, 1,
                                "org/repo", "main", "abc", 60, 0, 1),
                        attemptId, w, "lease-1", 1L, "ENV_VALIDATION", true,
                        Instant.now(), Instant.now().plusSeconds(60), 10, 100, 16384,
                        true, List.of("RUNTIME_KEY")
                ));
            }
            @Override public void acknowledgeRunning(String w, UUID a, String lt, long f, Instant s) {}
            @Override public SecretDeliveryResult fetchAttemptSecrets(String w, UUID a, String lt, long f) {
                return new SecretDeliveryResult(
                        attemptId, runId, stageId, 1L, Instant.now(),
                        List.of(new DeliveredSecret("RUNTIME_KEY", "SMOKE_KEY", UUID.randomUUID(), UUID.randomUUID(), 1, ephemeralSecret))
                );
            }
            @Override public void appendLogs(String w, UUID a, String lt, long f, List<LogEntry> e) {}
            @Override public void completeAttempt(String w, UUID a, CompletionPayload payload) {
                disabledCompletions.add(payload);
            }
            @Override public void updateStatus(String w, String s) {}
        };
        try (WorkerAgentLoop loop = new WorkerAgentLoop(disabledCfg, secretLeaseClient, new IsolatedExecutionAdapter() {
            @Override public String adapterType() { return "MOCK_TEST"; }
            @Override public boolean isAvailable() { return true; }
            @Override public ExecutionResult execute(ExecutionRequest r, AtomicBoolean c, LogLineConsumer l) {
                throw new AssertionError("Container adapter must not execute when secret delivery is disabled");
            }
        })) {
            assertThat(loop.runSingleClaimCycle()).isTrue();
        }
        assertThat(disabledCompletions).hasSize(1);
        assertThat(disabledCompletions.get(0).resultStatus()).isEqualTo("FAILED");
        assertThat(disabledCompletions.get(0).failureCategory()).isEqualTo("SECRET_DELIVERY_DISABLED");

        // 2. Enabled worker over HTTPS fetches pinned secret and scrubs logs & summary before upload
        WorkerAgentConfig enabledCfg = WorkerAgentConfig.fromEnv(Map.of(
                "CLOUDFORGE_CONTROL_PLANE_URL", "https://control.cloudforge.internal",
                "CLOUDFORGE_WORKER_ID", "unit-worker-sec",
                "CLOUDFORGE_WORKER_TOKEN", "unit-test-worker-token-12345678",
                "CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED", "true"
        ));
        List<ControlPlaneClient.LogEntry> uploadedLogs = new ArrayList<>();
        List<ControlPlaneClient.CompletionPayload> enabledCompletions = new ArrayList<>();
        AtomicBoolean claimedEnabled = new AtomicBoolean(false);
        ControlPlaneClient enabledClient = new ControlPlaneClient() {
            @Override public void register(RegistrationPayload payload) {}
            @Override public HeartbeatResult heartbeat(String w, List<ActiveLease> l) { return null; }
            @Override public Optional<ClaimedStageLease> claimNextStage(String w) {
                if (!claimedEnabled.compareAndSet(false, true)) return Optional.empty();
                return Optional.of(new ClaimedStageLease(
                        new StageInfo(UUID.randomUUID(), UUID.randomUUID(), runId, stageId,
                                "verify", "Verify", "SOURCE_PREP", 0, 1,
                                "org/repo", "main", "abc", 60, 0, 1),
                        attemptId, w, "lease-2", 2L, "ENV_VALIDATION", true,
                        Instant.now(), Instant.now().plusSeconds(60), 10, 100, 16384,
                        true, List.of("RUNTIME_KEY")
                ));
            }
            @Override public void acknowledgeRunning(String w, UUID a, String lt, long f, Instant s) {}
            @Override public SecretDeliveryResult fetchAttemptSecrets(String w, UUID a, String lt, long f) {
                return new SecretDeliveryResult(
                        attemptId, runId, stageId, 2L, Instant.now(),
                        List.of(new DeliveredSecret("RUNTIME_KEY", "SMOKE_KEY", UUID.randomUUID(), UUID.randomUUID(), 1, ephemeralSecret))
                );
            }
            @Override public void appendLogs(String w, UUID a, String lt, long f, List<LogEntry> entries) {
                uploadedLogs.addAll(entries);
            }
            @Override public void completeAttempt(String w, UUID a, CompletionPayload payload) {
                enabledCompletions.add(payload);
            }
            @Override public void updateStatus(String w, String s) {}
        };
        try (WorkerAgentLoop loop = new WorkerAgentLoop(enabledCfg, enabledClient, new IsolatedExecutionAdapter() {
            @Override public String adapterType() { return "MOCK_TEST"; }
            @Override public boolean isAvailable() { return true; }
            @Override public ExecutionResult execute(ExecutionRequest req, AtomicBoolean c, LogLineConsumer logConsumer) {
                assertThat(req.ephemeralSecrets()).containsEntry("RUNTIME_KEY", ephemeralSecret);
                Instant now = Instant.now();
                logConsumer.accept("STDOUT", "Accidental leak " + ephemeralSecret, now);
                return new ExecutionResult(
                        "SUCCEEDED", "NONE", 0, "Done with " + ephemeralSecret,
                        Map.of("durationMs", 12), now, Instant.now(), true
                );
            }
        })) {
            assertThat(loop.runSingleClaimCycle()).isTrue();
        }
        assertThat(uploadedLogs).hasSize(1);
        assertThat(uploadedLogs.get(0).message()).contains("[REDACTED]").doesNotContain(ephemeralSecret);
        assertThat(enabledCompletions).hasSize(1);
        assertThat(enabledCompletions.get(0).resultStatus()).isEqualTo("SUCCEEDED");
        assertThat(enabledCompletions.get(0).summary()).contains("[REDACTED]").doesNotContain(ephemeralSecret);
    }
}
