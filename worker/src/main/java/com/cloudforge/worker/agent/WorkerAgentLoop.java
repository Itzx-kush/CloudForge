package com.cloudforge.worker.agent;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Execution-plane agent loop coordinating registration, periodic heartbeats, lease renewal,
 * cancellation signaling, bounded log flushing, isolated execution, and graceful shutdown.
 */
public class WorkerAgentLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgentLoop.class);
    private static final int LOG_FLUSH_BATCH_SIZE = 25;

    private final WorkerAgentConfig config;
    private final ControlPlaneClient client;
    private final IsolatedExecutionAdapter adapter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<UUID, ActiveExecutionContext> activeExecutions = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatScheduler;

    public WorkerAgentLoop(
            WorkerAgentConfig config,
            ControlPlaneClient client,
            IsolatedExecutionAdapter adapter
    ) {
        this.config = config;
        this.client = client;
        this.adapter = adapter;
    }

    public void registerWorker() {
        client.register(new ControlPlaneClient.RegistrationPayload(
                config.workerId(),
                config.displayName(),
                adapter.adapterType(),
                config.supportedOperations(),
                config.maxConcurrency(),
                config.hostLabel(),
                config.agentVersion()
        ));
        log.info("Registered trusted worker workerId={} adapter={} ops={}",
                config.workerId(), adapter.adapterType(), config.supportedOperations());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        registerWorker();

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cf-worker-heartbeat-" + config.workerId());
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleWithFixedDelay(
                this::sendHeartbeatSafely,
                config.heartbeatIntervalMs(),
                config.heartbeatIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        while (running.get()) {
            try {
                boolean executed = runSingleClaimCycle();
                if (!executed) {
                    Thread.sleep(config.pollIntervalMs());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("Worker poll cycle failed (will retry): {}", ex.getMessage());
                try {
                    Thread.sleep(config.pollIntervalMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Executes one atomic claim-and-run cycle. Returns {@code true} if a stage lease was claimed
     * and executed, {@code false} if no eligible work was available.
     */
    public boolean runSingleClaimCycle() {
        Optional<ControlPlaneClient.ClaimedStageLease> leaseOpt = client.claimNextStage(config.workerId());
        if (leaseOpt.isEmpty()) {
            return false;
        }
        executeClaimedLease(leaseOpt.get());
        return true;
    }

    public void sendHeartbeatSafely() {
        try {
            List<ControlPlaneClient.ActiveLease> leases = activeExecutions.values().stream()
                    .map(ctx -> new ControlPlaneClient.ActiveLease(
                            ctx.lease.attemptId(),
                            ctx.lease.leaseToken(),
                            ctx.lease.fencingGeneration()
                    ))
                    .toList();

            ControlPlaneClient.HeartbeatResult result = client.heartbeat(config.workerId(), leases);
            if (result != null && result.directives() != null) {
                for (ControlPlaneClient.LeaseDirective directive : result.directives()) {
                    ActiveExecutionContext ctx = activeExecutions.get(directive.attemptId());
                    if (ctx != null && (!directive.leaseValid() || directive.cancelRequested())) {
                        log.info("Heartbeat signaled stop for attempt={} (leaseValid={}, cancelRequested={})",
                                directive.attemptId(), directive.leaseValid(), directive.cancelRequested());
                        ctx.cancelSignal.set(true);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Worker heartbeat failed: {}", ex.getMessage());
        }
    }

    private void executeClaimedLease(ControlPlaneClient.ClaimedStageLease lease) {
        AtomicBoolean cancelSignal = new AtomicBoolean(false);
        ActiveExecutionContext ctx = new ActiveExecutionContext(lease, cancelSignal);
        activeExecutions.put(lease.attemptId(), ctx);

        List<ControlPlaneClient.LogEntry> logBuffer = new ArrayList<>();
        Object bufferLock = new Object();

        try {
            Instant startedAt = Instant.now();
            client.acknowledgeRunning(
                    config.workerId(),
                    lease.attemptId(),
                    lease.leaseToken(),
                    lease.fencingGeneration(),
                    startedAt
            );

            IsolatedExecutionAdapter.ExecutionRequest execRequest = new IsolatedExecutionAdapter.ExecutionRequest(
                    lease.attemptId(),
                    lease.stage().pipelineRunId(),
                    lease.stage().stageRunId(),
                    lease.stage().stageKey(),
                    lease.allowedOperation(),
                    lease.stage().attemptNumber(),
                    lease.stage().timeoutSeconds(),
                    lease.maxLogLines(),
                    lease.maxLogBytes()
            );

            IsolatedExecutionAdapter.ExecutionResult result = adapter.execute(
                    execRequest,
                    cancelSignal,
                    (stream, message, occurredAt) -> {
                        List<ControlPlaneClient.LogEntry> batchToFlush = null;
                        synchronized (bufferLock) {
                            logBuffer.add(new ControlPlaneClient.LogEntry(stream, message, occurredAt));
                            if (logBuffer.size() >= LOG_FLUSH_BATCH_SIZE) {
                                batchToFlush = new ArrayList<>(logBuffer);
                                logBuffer.clear();
                            }
                        }
                        if (batchToFlush != null) {
                            flushLogsQuietly(lease, batchToFlush);
                        }
                    }
            );

            // Flush any remaining buffered log entries prior to completion
            List<ControlPlaneClient.LogEntry> finalBatch;
            synchronized (bufferLock) {
                finalBatch = new ArrayList<>(logBuffer);
                logBuffer.clear();
            }
            if (!finalBatch.isEmpty()) {
                flushLogsQuietly(lease, finalBatch);
            }

            client.completeAttempt(
                    config.workerId(),
                    lease.attemptId(),
                    new ControlPlaneClient.CompletionPayload(
                            lease.stage().pipelineRunId(),
                            lease.stage().stageRunId(),
                            lease.stage().attemptNumber(),
                            lease.leaseToken(),
                            lease.fencingGeneration(),
                            result.resultStatus(),
                            result.failureCategory(),
                            result.exitCode(),
                            result.summary(),
                            result.resourceUsage(),
                            result.startedAt(),
                            result.completedAt(),
                            result.terminationConfirmed()
                    )
            );
            log.info("Completed stage={} attempt={} status={} exitCode={}",
                    lease.stage().stageKey(), lease.attemptId(), result.resultStatus(), result.exitCode());
        } finally {
            activeExecutions.remove(lease.attemptId());
        }
    }

    private void flushLogsQuietly(
            ControlPlaneClient.ClaimedStageLease lease,
            List<ControlPlaneClient.LogEntry> entries
    ) {
        try {
            client.appendLogs(
                    config.workerId(),
                    lease.attemptId(),
                    lease.leaseToken(),
                    lease.fencingGeneration(),
                    entries
            );
        } catch (Exception ex) {
            log.warn("Failed to flush {} log lines for attempt={}: {}", entries.size(), lease.attemptId(), ex.getMessage());
        }
    }

    public void requestGracefulShutdown() {
        running.set(false);
        try {
            client.updateStatus(config.workerId(), "DRAINING");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        try {
            client.updateStatus(config.workerId(), "OFFLINE");
        } catch (Exception ignored) {
        }
    }

    private record ActiveExecutionContext(
            ControlPlaneClient.ClaimedStageLease lease,
            AtomicBoolean cancelSignal
    ) {
    }
}
