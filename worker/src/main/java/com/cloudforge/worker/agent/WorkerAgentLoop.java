package com.cloudforge.worker.agent;

import com.cloudforge.worker.client.ControlPlaneClient;
import com.cloudforge.worker.config.WorkerAgentConfig;
import com.cloudforge.worker.executor.IsolatedExecutionAdapter;
import com.cloudforge.worker.oci.BuildKitExecutionAdapter;
import com.cloudforge.worker.oci.DockerfilePolicyValidator;
import com.cloudforge.worker.oci.OciLayoutInspector;
import com.cloudforge.worker.oci.OciRegistryClient;
import com.cloudforge.worker.security.WorkerSecretRedactor;
import com.cloudforge.worker.source.GitHubSourceArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * cancellation signaling, bounded log flushing, isolated execution (Docker allowlisted ops,
 * GitHub source acquisition, and OCI BuildKit build/publish), and graceful shutdown.
 */
public class WorkerAgentLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgentLoop.class);
    private static final int LOG_FLUSH_BATCH_SIZE = 25;

    private final WorkerAgentConfig config;
    private final ControlPlaneClient client;
    private final IsolatedExecutionAdapter adapter;
    private final BuildKitExecutionAdapter buildKitAdapter;
    private final OciRegistryClient ociRegistryClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<UUID, ActiveExecutionContext> activeExecutions = new ConcurrentHashMap<>();
    private ScheduledExecutorService heartbeatScheduler;

    public WorkerAgentLoop(
            WorkerAgentConfig config,
            ControlPlaneClient client,
            IsolatedExecutionAdapter adapter
    ) {
        this(config, client, adapter, new BuildKitExecutionAdapter(config), new OciRegistryClient());
    }

    public WorkerAgentLoop(
            WorkerAgentConfig config,
            ControlPlaneClient client,
            IsolatedExecutionAdapter adapter,
            BuildKitExecutionAdapter buildKitAdapter,
            OciRegistryClient ociRegistryClient
    ) {
        this.config = config;
        this.client = client;
        this.adapter = adapter;
        this.buildKitAdapter = buildKitAdapter;
        this.ociRegistryClient = ociRegistryClient;
    }

    public void registerWorker() {
        String adapterType = config.sourceOnly()
                ? "GITHUB_SOURCE"
                : config.ociBuildKitEnabled()
                ? "OCI_BUILDKIT"
                : adapter.adapterType();
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

        IsolatedExecutionAdapter.LogLineConsumer logConsumer = (stream, message, occurredAt) -> {
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
        };

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
            boolean ociBuildOp = WorkerAgentConfig.OCI_BUILD_OPERATION.equals(lease.allowedOperation());
            boolean ociPublishOp = WorkerAgentConfig.OCI_PUBLISH_OPERATION.equals(lease.allowedOperation());
            boolean ociOperation = ociBuildOp || ociPublishOp;

            // Strict worker capability isolation: never cross-execute across worker adapter types
            if (sourceOperation != config.sourceOnly()
                    || ociOperation != config.ociBuildKitEnabled()
                    || ((sourceOperation || ociOperation)
                    && (lease.hasSecretBindings() || (lease.secretAliases() != null && !lease.secretAliases().isEmpty())))) {
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

            IsolatedExecutionAdapter.ExecutionResult result;
            if (sourceOperation) {
                result = inspectSource(lease, startedAt, cancelSignal, logBuffer);
            } else if (ociBuildOp) {
                result = executeOciBuild(lease, startedAt, cancelSignal, logConsumer);
            } else if (ociPublishOp) {
                result = executeOciPublish(lease, startedAt, cancelSignal, redactor, logConsumer);
            } else {
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
                result = adapter.execute(execRequest, cancelSignal, logConsumer);
            }

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
        Path handoffTemp = null;
        try {
            var url = client.fetchSourceArchive(config.workerId(), lease.attemptId(),
                    lease.leaseToken(), lease.fencingGeneration());
            if (lease.sourceHandoffRequired()) {
                handoffTemp = Files.createTempDirectory("cloudforge-src-handoff-");
                Path canonicalZip = handoffTemp.resolve("verified-source.zip");
                var staged = GitHubSourceArchive.acquireAndPackage(
                        url, lease.stage().repositoryFullName(), lease.stage().commitSha(),
                        canonicalZip, deadline, cancelSignal
                );
                var uploaded = client.uploadSourceHandoff(
                        config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration(),
                        canonicalZip, staged.sha256(), staged.fileCount(), staged.extractedBytes()
                );
                usage = Map.of(
                        "sourceFileCount", staged.fileCount(),
                        "sourceBytes", staged.extractedBytes(),
                        "handoffChecksumSha256", staged.sha256(),
                        "handoffId", uploaded != null && uploaded.handoffId() != null ? uploaded.handoffId().toString() : ""
                );
                summary = "Pinned source archive verified, staged for OCI build handoff, and temporary source removed";
            } else {
                var inspection = GitHubSourceArchive.acquire(url, lease.stage().repositoryFullName(),
                        lease.stage().commitSha(), deadline, cancelSignal);
                usage = Map.of("sourceFileCount", inspection.fileCount(), "sourceBytes", inspection.extractedBytes());
                summary = "Pinned source archive verified and temporary source removed";
            }
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
        } finally {
            GitHubSourceArchive.deleteRecursivelyQuietly(handoffTemp);
        }
        logs.add(new ControlPlaneClient.LogEntry("SYSTEM", summary, Instant.now()));
        return new IsolatedExecutionAdapter.ExecutionResult(status, category, null, summary, usage,
                startedAt, Instant.now(), true);
    }

    private IsolatedExecutionAdapter.ExecutionResult executeOciBuild(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            AtomicBoolean cancelSignal,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("cloudforge-oci-build-");
            logConsumer.accept("SYSTEM", "[CloudForge OCI Build] Fetching immutable build descriptor from control plane", Instant.now());
            ControlPlaneClient.OciExecutionDescriptor descriptor = client.fetchOciDescriptor(
                    config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration()
            );
            if (descriptor == null || descriptor.expectedSourceSha256() == null) {
                return failOciBuild(lease, startedAt, "SOURCE_CONTEXT_INVALID", "Control plane returned incomplete OCI build descriptor", logConsumer);
            }

            if (cancelSignal.get()) {
                return cancelOciBuild(lease, startedAt, "OCI build cancelled before build context download", logConsumer);
            }

            Path sourceZip = workDir.resolve("source-handoff.zip");
            Path extractedSource = Files.createDirectory(workDir.resolve("source"));
            Path outputOciTar = workDir.resolve("oci-image-layout.tar");

            logConsumer.accept("SYSTEM", "[CloudForge OCI Build] Downloading verified source context (commit=" + descriptor.commitSha() + ")", Instant.now());
            ControlPlaneClient.DownloadedHandoff dl = client.downloadBuildContext(
                    config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration(), sourceZip
            );
            if (dl == null || !descriptor.expectedSourceSha256().equalsIgnoreCase(dl.contentChecksumSha256())) {
                return failOciBuild(lease, startedAt, "SOURCE_CONTEXT_INVALID", "Downloaded source handoff checksum header mismatch", logConsumer);
            }

            Instant deadline = startedAt.plusSeconds(Math.min(descriptor.timeoutSeconds(), config.maxExecutionTimeoutSeconds()));
            try {
                GitHubSourceArchive.extractVerifiedHandoff(sourceZip, descriptor.expectedSourceSha256(), extractedSource, deadline, cancelSignal);
            } catch (CancellationException ex) {
                return cancelOciBuild(lease, startedAt, "OCI build cancelled during source verification", logConsumer);
            } catch (IOException ex) {
                return failOciBuild(lease, startedAt, "SOURCE_CONTEXT_INVALID", "Source handoff verification failed: " + ex.getMessage(), logConsumer);
            }

            // Defensive Dockerfile & context policy validation before invoking BuildKit
            DockerfilePolicyValidator.ValidatedContext validatedContext;
            try {
                validatedContext = DockerfilePolicyValidator.validate(
                        extractedSource,
                        descriptor.buildContextPath(),
                        descriptor.dockerfilePath(),
                        config.allowedBaseImages()
                );
            } catch (DockerfilePolicyValidator.PolicyViolationException ex) {
                return failOciBuild(lease, startedAt, "DOCKERFILE_POLICY_VIOLATION", ex.getMessage(), logConsumer);
            }

            logConsumer.accept("SYSTEM",
                    "[CloudForge OCI Build] Dockerfile policy validated (contextFiles=" + validatedContext.contextFileCount()
                            + ", baseImages=" + validatedContext.baseImages() + ")",
                    Instant.now());

            BuildKitExecutionAdapter.BuildOutcome outcome = buildKitAdapter.executeBuild(
                    validatedContext.contextDirectory(),
                    validatedContext.dockerfile(),
                    outputOciTar,
                    descriptor.targetPlatform(),
                    descriptor.buildArgs(),
                    descriptor.timeoutSeconds(),
                    lease.maxLogLines(),
                    lease.maxLogBytes(),
                    cancelSignal,
                    logConsumer
            );

            if ("CANCELLED".equals(outcome.status())) {
                return cancelOciBuild(lease, startedAt, outcome.summary(), logConsumer);
            }
            if ("TIMED_OUT".equals(outcome.status())) {
                reportBuildResultQuietly(lease, new ControlPlaneClient.OciBuildResultPayload(
                        lease.leaseToken(), lease.fencingGeneration(), "TIMED_OUT",
                        null, null, null, null, "EXECUTION_TIMEOUT", outcome.summary(), startedAt, Instant.now()
                ));
                return new IsolatedExecutionAdapter.ExecutionResult(
                        "TIMED_OUT", "EXECUTION_TIMEOUT", outcome.exitCode(), outcome.summary(),
                        Map.of("adapter", "OCI_BUILDKIT"), startedAt, Instant.now(), outcome.terminationConfirmed()
                );
            }
            if (!"SUCCEEDED".equals(outcome.status()) || outcome.layout() == null) {
                return failOciBuild(lease, startedAt, outcome.failureCategory(), outcome.summary(), logConsumer);
            }

            if (cancelSignal.get()) {
                return cancelOciBuild(lease, startedAt, "OCI build cancelled before staging built OCI layout", logConsumer);
            }

            logConsumer.accept("SYSTEM", "[CloudForge OCI Build] Uploading verified OCI image layout handoff for publish stage", Instant.now());
            ControlPlaneClient.HandoffUploadResult ociUpload = client.uploadOciLayout(
                    config.workerId(),
                    lease.attemptId(),
                    lease.leaseToken(),
                    lease.fencingGeneration(),
                    outputOciTar,
                    outcome.layoutTarSha256(),
                    outcome.layout().totalImageBytes()
            );

            client.reportOciBuildResult(
                    config.workerId(),
                    lease.attemptId(),
                    new ControlPlaneClient.OciBuildResultPayload(
                            lease.leaseToken(),
                            lease.fencingGeneration(),
                            "SUCCEEDED",
                            outcome.layout().manifestDigest(),
                            outcome.layout().mediaType(),
                            outcome.layout().totalImageBytes(),
                            ociUpload.handoffId(),
                            null,
                            null,
                            startedAt,
                            outcome.completedAt()
                    )
            );

            return new IsolatedExecutionAdapter.ExecutionResult(
                    "SUCCEEDED",
                    "NONE",
                    0,
                    outcome.summary(),
                    Map.of(
                            "adapter", "OCI_BUILDKIT",
                            "builtManifestDigest", outcome.layout().manifestDigest(),
                            "platform", outcome.layout().platform(),
                            "imageSizeBytes", outcome.layout().totalImageBytes()
                    ),
                    startedAt,
                    Instant.now(),
                    true
            );
        } catch (Exception ex) {
            return failOciBuild(lease, startedAt, "BUILD_FAILED", "OCI build failed: " + ex.getMessage(), logConsumer);
        } finally {
            GitHubSourceArchive.deleteRecursivelyQuietly(workDir);
        }
    }

    private IsolatedExecutionAdapter.ExecutionResult executeOciPublish(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            AtomicBoolean cancelSignal,
            WorkerSecretRedactor redactor,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        Path workDir = null;
        char[] usernameChars = null;
        char[] passwordChars = null;
        try {
            workDir = Files.createTempDirectory("cloudforge-oci-publish-");
            logConsumer.accept("SYSTEM", "[CloudForge OCI Publish] Fetching publish descriptor from control plane", Instant.now());
            ControlPlaneClient.OciExecutionDescriptor descriptor = client.fetchOciDescriptor(
                    config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration()
            );
            if (descriptor == null || descriptor.expectedOciLayoutSha256() == null || descriptor.expectedManifestDigest() == null) {
                return failOciPublish(lease, startedAt, "OPERATION_FAILURE", "Incomplete OCI publish descriptor", logConsumer);
            }

            if (cancelSignal.get()) {
                return cancelOciPublish(lease, startedAt, "OCI publish cancelled before downloading built OCI layout", logConsumer);
            }

            Path ociTar = workDir.resolve("oci-image-layout.tar");
            ControlPlaneClient.DownloadedHandoff dl = client.downloadOciLayout(
                    config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration(), ociTar
            );
            String actualTarSha = OciLayoutInspector.sha256FileHex(ociTar);
            if (dl == null || !descriptor.expectedOciLayoutSha256().equalsIgnoreCase(actualTarSha)) {
                return failOciPublish(lease, startedAt, "SOURCE_CONTEXT_INVALID", "OCI image layout handoff checksum mismatch", logConsumer);
            }

            OciLayoutInspector.VerifiedOciLayout layout = OciLayoutInspector.inspectAndVerify(ociTar);
            if (!descriptor.expectedManifestDigest().equalsIgnoreCase(layout.manifestDigest())) {
                return failOciPublish(lease, startedAt, "DIGEST_VERIFICATION_FAILED",
                        "Local OCI layout digest does not match expected upstream build digest", logConsumer);
            }

            if (cancelSignal.get()) {
                return cancelOciPublish(lease, startedAt, "OCI publish cancelled prior to registry credential retrieval", logConsumer);
            }

            // Retrieve ephemeral lease-bound encrypted registry credentials strictly in memory
            ControlPlaneClient.RegistryCredentialResult creds = client.fetchRegistryCredentials(
                    config.workerId(), lease.attemptId(), lease.leaseToken(), lease.fencingGeneration()
            );
            if (creds != null && !creds.anonymous()) {
                if (creds.password() != null && !creds.password().isEmpty()) {
                    redactor.addSecrets(List.of(creds.password()));
                    passwordChars = creds.password().toCharArray();
                }
                if (creds.username() != null && !creds.username().isEmpty()) {
                    usernameChars = creds.username().toCharArray();
                }
            }

            boolean allowInsecureLocal = descriptor.allowInsecureLocalRegistry() && config.allowLocalDevRegistry();
            OciRegistryClient.PublishVerificationResult verified = ociRegistryClient.publishAndVerify(
                    descriptor.registryScheme(),
                    descriptor.registryHost(),
                    descriptor.targetImageName(),
                    descriptor.targetTag(),
                    allowInsecureLocal,
                    usernameChars,
                    passwordChars,
                    layout,
                    cancelSignal,
                    logConsumer
            );

            client.reportOciPublishResult(
                    config.workerId(),
                    lease.attemptId(),
                    new ControlPlaneClient.OciPublishResultPayload(
                            lease.leaseToken(),
                            lease.fencingGeneration(),
                            "VERIFIED",
                            verified.reportedDigest(),
                            verified.verifiedDigest(),
                            verified.mediaType(),
                            verified.platform(),
                            verified.totalImageBytes(),
                            verified.verified(),
                            null,
                            null,
                            Instant.now()
                    )
            );

            return new IsolatedExecutionAdapter.ExecutionResult(
                    "SUCCEEDED",
                    "NONE",
                    0,
                    "Published and verified immutable OCI artifact " + verified.immutableReference(),
                    Map.of(
                            "adapter", "OCI_BUILDKIT",
                            "immutableReference", verified.immutableReference(),
                            "manifestDigest", verified.verifiedDigest(),
                            "platform", verified.platform(),
                            "imageSizeBytes", verified.totalImageBytes()
                    ),
                    startedAt,
                    Instant.now(),
                    true
            );
        } catch (CancellationException ex) {
            return cancelOciPublish(lease, startedAt, "OCI publish cancelled prior to completion", logConsumer);
        } catch (OciRegistryClient.RegistryPublishException ex) {
            String safeErr = redactor.redact(ex.getMessage());
            return failOciPublish(lease, startedAt, ex.getFailureCategory(), safeErr, logConsumer);
        } catch (Exception ex) {
            String safeErr = redactor.redact(ex.getMessage());
            return failOciPublish(lease, startedAt, "OPERATION_FAILURE", "OCI publish failed: " + safeErr, logConsumer);
        } finally {
            if (usernameChars != null) {
                Arrays.fill(usernameChars, '\0');
            }
            if (passwordChars != null) {
                Arrays.fill(passwordChars, '\0');
            }
            GitHubSourceArchive.deleteRecursivelyQuietly(workDir);
        }
    }

    private IsolatedExecutionAdapter.ExecutionResult failOciBuild(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            String category,
            String summary,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        logConsumer.accept("STDERR", "[CloudForge OCI Build] " + summary, Instant.now());
        reportBuildResultQuietly(lease, new ControlPlaneClient.OciBuildResultPayload(
                lease.leaseToken(), lease.fencingGeneration(), "FAILED",
                null, null, null, null, category, summary, startedAt, Instant.now()
        ));
        return new IsolatedExecutionAdapter.ExecutionResult(
                "FAILED", category, 1, summary, Map.of("adapter", "OCI_BUILDKIT"), startedAt, Instant.now(), true
        );
    }

    private IsolatedExecutionAdapter.ExecutionResult cancelOciBuild(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            String summary,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        logConsumer.accept("SYSTEM", "[CloudForge OCI Build] " + summary, Instant.now());
        reportBuildResultQuietly(lease, new ControlPlaneClient.OciBuildResultPayload(
                lease.leaseToken(), lease.fencingGeneration(), "CANCELLED",
                null, null, null, null, "CANCELLED", summary, startedAt, Instant.now()
        ));
        return new IsolatedExecutionAdapter.ExecutionResult(
                "CANCELLED", "CANCELLED", 143, summary, Map.of("adapter", "OCI_BUILDKIT"), startedAt, Instant.now(), true
        );
    }

    private IsolatedExecutionAdapter.ExecutionResult failOciPublish(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            String category,
            String summary,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        logConsumer.accept("STDERR", "[CloudForge OCI Publish] " + summary, Instant.now());
        reportPublishResultQuietly(lease, new ControlPlaneClient.OciPublishResultPayload(
                lease.leaseToken(), lease.fencingGeneration(), "FAILED",
                null, null, null, null, null, false, category, summary, Instant.now()
        ));
        return new IsolatedExecutionAdapter.ExecutionResult(
                "FAILED", category, 1, summary, Map.of("adapter", "OCI_BUILDKIT"), startedAt, Instant.now(), true
        );
    }

    private IsolatedExecutionAdapter.ExecutionResult cancelOciPublish(
            ControlPlaneClient.ClaimedStageLease lease,
            Instant startedAt,
            String summary,
            IsolatedExecutionAdapter.LogLineConsumer logConsumer
    ) {
        logConsumer.accept("SYSTEM", "[CloudForge OCI Publish] " + summary, Instant.now());
        reportPublishResultQuietly(lease, new ControlPlaneClient.OciPublishResultPayload(
                lease.leaseToken(), lease.fencingGeneration(), "CANCELLED",
                null, null, null, null, null, false, "CANCELLED", summary, Instant.now()
        ));
        return new IsolatedExecutionAdapter.ExecutionResult(
                "CANCELLED", "CANCELLED", 143, summary, Map.of("adapter", "OCI_BUILDKIT"), startedAt, Instant.now(), true
        );
    }

    private void reportBuildResultQuietly(ControlPlaneClient.ClaimedStageLease lease, ControlPlaneClient.OciBuildResultPayload payload) {
        try {
            client.reportOciBuildResult(config.workerId(), lease.attemptId(), payload);
        } catch (Exception ignored) {
        }
    }

    private void reportPublishResultQuietly(ControlPlaneClient.ClaimedStageLease lease, ControlPlaneClient.OciPublishResultPayload payload) {
        try {
            client.reportOciPublishResult(config.workerId(), lease.attemptId(), payload);
        } catch (Exception ignored) {
        }
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
