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
}
