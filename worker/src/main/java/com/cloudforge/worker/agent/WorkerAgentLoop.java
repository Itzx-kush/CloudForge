package com.cloudforge.worker.agent;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.cloudforge.worker.security.WorkerSecretRedactor;
import com.cloudforge.worker.source.GitHubSourceArchive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        String adapterType = config.sourceOnly() ? "GITHUB_SOURCE" : adapter.adapterType();
        client.register(new ControlPlaneClient.RegistrationPayload(
                config.workerId(),
                config.displayName(),
                adapterType,
                config.supportedOperations(),
                config.maxConcurrency(),
                config.hostLabel(),
                config.agentVersion()
        ));
        log.info("Registered trusted worker workerId={} adapter={} ops={}",
                config.workerId(), adapterType, config.supportedOperations());
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
        WorkerSecretRedactor redactor = new WorkerSecretRedactor();
        Map<String, String> ephemeralSecrets = new LinkedHashMap<>();
        AtomicReference<String> prevLineRef = new AtomicReference<>(null);

        try {
            Instant startedAt = Instant.now();
            client.acknowledgeRunning(
                    config.workerId(),
                    lease.attemptId(),
                    lease.leaseToken(),
                    lease.fencingGeneration(),
                    startedAt
            );

            boolean sourceOperation = WorkerAgentConfig.SOURCE_OPERATION.equals(lease.allowedOperation());
            if (sourceOperation != config.sourceOnly()
                    || (sourceOperation && (lease.hasSecretBindings()
                    || (lease.secretAliases() != null && !lease.secretAliases().isEmpty())))) {
                completeDeliveryFailure(lease, startedAt, "UNSUPPORTED_OPERATION", "Worker capability mismatch", redactor);
                return;
            }

            if (lease.hasSecretBindings() || (lease.secretAliases() != null && !lease.secretAliases().isEmpty())) {
                if (!config.secretDeliveryEnabled()) {
                    String msg = "Worker secret delivery is disabled (CLOUDFORGE_WORKER_SECRET_DELIVERY_ENABLED=false)";
                    completeDeliveryFailure(lease, startedAt, "SECRET_DELIVERY_DISABLED", msg, redactor);
                    return;
                }
                try {
                    ControlPlaneClient.SecretDeliveryResult delivery = client.fetchAttemptSecrets(
                            config.workerId(),
                            lease.attemptId(),
                            lease.leaseToken(),
                            lease.fencingGeneration()
                    );
                    if (delivery != null && delivery.secrets() != null) {
                        for (ControlPlaneClient.DeliveredSecret secret : delivery.secrets()) {
                            ephemeralSecrets.put(secret.alias(), secret.value());
                            redactor.addSecrets(List.of(secret.value()));
                        }
                    }
                    if (ephemeralSecrets.isEmpty()) {
                        completeDeliveryFailure(
                                lease, startedAt, "SECRET_DELIVERY_DENIED",
                                "Control plane returned zero authorized secrets for secret-bound stage",
                                redactor
                        );
                        return;
                    }
                } catch (Exception ex) {
                    String safeMsg = redactor.redact(ex.getMessage());
                    completeDeliveryFailure(
                            lease, startedAt, "SECRET_DELIVERY_DENIED",
                            "Failed to retrieve lease-bound secrets: " + (safeMsg != null ? safeMsg : "access denied"),
                            redactor
                    );
                    return;
                }
            }

            IsolatedExecutionAdapter.ExecutionRequest execRequest = new IsolatedExecutionAdapter.ExecutionRequest(
                    lease.attemptId(),
                    lease.stage().pipelineRunId(),
                    lease.stage().stageRunId(),
                    lease.stage().stageKey(),
                    lease.allowedOperation(),
                    lease.stage().attemptNumber(),
                    lease.stage().timeoutSeconds(),
                    lease.maxLogLines(),
                    lease.maxLogBytes(),
                    Map.copyOf(ephemeralSecrets)
            );

            IsolatedExecutionAdapter.ExecutionResult result = sourceOperation
                    ? inspectSource(lease, startedAt, cancelSignal, logBuffer)
                    : adapter.execute(
                    execRequest,
                    cancelSignal,
                    (stream, message, occurredAt) -> {
                        String redactedMsg = redactor.redact(message);
                        List<ControlPlaneClient.LogEntry> batchToFlush = null;
                        synchronized (bufferLock) {
                            String prev = prevLineRef.get();
                            if (prev != null && !logBuffer.isEmpty()) {
                                WorkerSecretRedactor.BoundaryPair boundary = redactor.redactAcrossBoundary(prev, redactedMsg);
                                if (boundary.previousModified()) {
                                    int lastIdx = logBuffer.size() - 1;
                                    ControlPlaneClient.LogEntry prevEntry = logBuffer.get(lastIdx);
                                    logBuffer.set(lastIdx, new ControlPlaneClient.LogEntry(
                                            prevEntry.streamType(), boundary.previousMessage(), prevEntry.occurredAt()
                                    ));
                                    redactedMsg = boundary.currentMessage();
                                }
                            }
                            logBuffer.add(new ControlPlaneClient.LogEntry(stream, redactedMsg, occurredAt));
                            prevLineRef.set(redactedMsg);
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
                            redactor.redact(result.summary()),
                            redactResourceUsage(result.resourceUsage(), redactor),
                            result.startedAt(),
                            result.completedAt(),
                            result.terminationConfirmed()
                    )
            );
            log.info("Completed stage={} attempt={} status={} exitCode={}",
                    lease.stage().stageKey(), lease.attemptId(), result.resultStatus(), result.exitCode());
        } finally {
            ephemeralSecrets.clear();
            redactor.clear();
            activeExecutions.remove(lease.attemptId());
        }
    }

    private IsolatedExecutionAdapter.ExecutionResult inspectSource(
            ControlPlaneClient.ClaimedStageLease lease, Instant startedAt, AtomicBoolean cancelSignal,
            List<ControlPlaneClient.LogEntry> logs
    ) {
        Instant deadline = startedAt.plusSeconds(Math.min(lease.stage().timeoutSeconds(), config.maxExecutionTimeoutSeconds()));
        logs.add(new ControlPlaneClient.LogEntry("SYSTEM", "Requesting archive for the pinned source commit", Instant.now()));
        String status = "SUCCEEDED";
        String category = "NONE";
        String summary;
        Map<String, Object> usage = Map.of();
        try {
            var url = client.fetchSourceArchive(config.workerId(), lease.attemptId(),
                    lease.leaseToken(), lease.fencingGeneration());
            var inspection = GitHubSourceArchive.acquire(url, lease.stage().repositoryFullName(),
                    lease.stage().commitSha(), deadline, cancelSignal);
            usage = Map.of("sourceFileCount", inspection.fileCount(), "sourceBytes", inspection.extractedBytes());
            summary = "Pinned source archive verified and temporary source removed";
        } catch (CancellationException ex) {
            status = "CANCELLED";
            category = "CANCELLED";
            summary = "GitHub source acquisition cancelled";
        } catch (GitHubSourceArchive.SourceTimeoutException ex) {
            status = "TIMED_OUT";
            category = "EXECUTION_TIMEOUT";
            summary = "GitHub source acquisition exceeded the stage deadline";
        } catch (IOException | RuntimeException ex) {
            status = "FAILED";
            category = "OPERATION_FAILURE";
            summary = "GitHub source acquisition failed. Check installation access and archive limits.";
        }
        logs.add(new ControlPlaneClient.LogEntry("SYSTEM", summary, Instant.now()));
        return new IsolatedExecutionAdapter.ExecutionResult(status, category, null, summary, usage,
                startedAt, Instant.now(), true);
    }

    private void completeDeliveryFailure(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            String failureCategory,
            String summary,
            WorkerSecretRedactor redactor
    ) {
        Instant now = Instant.now();
        String safeSummary = redactor.redact(summary);
        flushLogsQuietly(lease, List.of(
                new ControlPlaneClient.LogEntry("SYSTEM", "[CloudForge Worker] " + safeSummary, now)
        ));
        client.completeAttempt(
                config.workerId(),
                lease.attemptId(),
                new ControlPlaneClient.CompletionPayload(
                        lease.stage().pipelineRunId(),
                        lease.stage().stageRunId(),
                        lease.stage().attemptNumber(),
                        lease.leaseToken(),
                        lease.fencingGeneration(),
                        "FAILED",
                        failureCategory,
                        126,
                        safeSummary,
                        Map.of("secretDeliveryEnabled", config.secretDeliveryEnabled()),
                        startedAt,
                        now,
                        true
                )
        );
    }

    private Map<String, Object> redactResourceUsage(Map<String, Object> raw, WorkerSecretRedactor redactor) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof String s) {
                safe.put(k, redactor.redact(s));
            } else {
                safe.put(k, v);
            }
        });
        return safe;
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
