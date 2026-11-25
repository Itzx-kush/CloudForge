package com.cloudforge.backend.worker.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentLifecycleService;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.cloudforge.backend.oci.service.SourceHandoffService;
import com.cloudforge.backend.orchestration.engine.StageDependencyEvaluator;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.secrets.service.AttemptSecretRedactionService;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.dto.AttemptRunningAckRequest;
import com.cloudforge.backend.worker.dto.CompleteAttemptRequest;
import com.cloudforge.backend.worker.dto.StageExecutionAttemptDto;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates fenced worker execution acknowledgments and outcomes, applies strict
 * Phase 3.2 state-machine transitions, schedules eligible retries without mutating
 * terminal FAILED stages, evaluates downstream DAG progressions, and finalizes runs.
 */
@Service
public class WorkerResultProcessorService {

    private final WorkerProperties workerProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final PipelineRunEventRepository runEventRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final AuditService auditService;
    private final LogSanitizer logSanitizer;
    private final AttemptSecretRedactionService attemptSecretRedactionService;
    private final OciBuildLifecycleService ociBuildLifecycleService;
    private final KubernetesDeploymentLifecycleService k8sLifecycleService;
    private final SourceHandoffService sourceHandoffService;
    private final ObjectMapper objectMapper;

    public WorkerResultProcessorService(
            WorkerProperties workerProperties,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            PipelineRunEventRepository runEventRepository,
            StageExecutionAttemptRepository attemptRepository,
            WorkspaceAuthorizationService authorizationService,
            AuditService auditService,
            LogSanitizer logSanitizer,
            AttemptSecretRedactionService attemptSecretRedactionService,
            OciBuildLifecycleService ociBuildLifecycleService,
            KubernetesDeploymentLifecycleService k8sLifecycleService,
            SourceHandoffService sourceHandoffService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.runEventRepository = runEventRepository;
        this.attemptRepository = attemptRepository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.logSanitizer = logSanitizer;
        this.attemptSecretRedactionService = attemptSecretRedactionService;
        this.ociBuildLifecycleService = ociBuildLifecycleService;
        this.k8sLifecycleService = k8sLifecycleService;
        this.sourceHandoffService = sourceHandoffService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StageExecutionAttemptDto acknowledgeAttemptRunning(
            String pathWorkerId,
            UUID attemptId,
            AttemptRunningAckRequest request,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        StageExecutionAttemptEntity attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        validateAttemptOwnershipAndFence(attempt, principal.workerId(), request.leaseToken(), request.fencingGeneration());
        if (!attempt.getStatus().isActive()) {
            throw new ConflictException("Execution attempt is already terminal (" + attempt.getStatus() + ")");
        }

        Instant now = Instant.now();
        if (attempt.getLeaseExpiresAt().isBefore(now)) {
            throw new ConflictException("Execution lease has expired; cannot transition to RUNNING");
        }

        attempt.setStatus(AttemptStatus.RUNNING);
        if (attempt.getStartedAt() == null) {
            attempt.setStartedAt(request.startedAt() != null ? request.startedAt() : now);
        }
        attempt.setLastHeartbeatAt(now);
        return toDto(attemptRepository.saveAndFlush(attempt));
    }

    @Transactional
    public StageExecutionAttemptDto completeAttempt(
            String pathWorkerId,
            UUID attemptId,
            CompleteAttemptRequest request,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        // Scalar projection avoids hydrating entities into Hibernate L1 cache prior to PESSIMISTIC_WRITE locks
        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId()).orElse(null);

        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(lockIds.getPipelineRunId(), lockIds.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found for execution attempt"));

        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(lockIds.getStageRunId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found for execution attempt"));

        StageExecutionAttemptEntity attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        // Strict verification of worker ownership, identifiers, lease token, fencing generation, and non-terminal state
        validateAttemptOwnershipAndFence(attempt, principal.workerId(), request.leaseToken(), request.fencingGeneration());

        if (!attempt.getPipelineRun().getId().equals(request.pipelineRunId())
                || !attempt.getStageRun().getId().equals(request.stageRunId())
                || attempt.getAttemptNumber() != request.attemptNumber()) {
            throw new BadRequestException("Mismatched runId, stageRunId, or attemptNumber in worker completion payload");
        }
        if (attempt.getStatus().isTerminal()) {
            throw new ConflictException("Duplicate completion rejected: attempt is already terminal (" + attempt.getStatus() + ")");
        }
        if (stage.getStatus().isTerminal() || run.getStatus().isTerminal()) {
            throw new ConflictException("Late completion rejected: stage or run has already reached a terminal state");
        }
        if (stage.getActiveLeaseToken() == null
                || !stage.getActiveLeaseToken().equals(request.leaseToken())
                || stage.getFencingGeneration() != request.fencingGeneration()) {
            throw new ConflictException("Stale completion rejected: stage is now fenced at generation " + stage.getFencingGeneration());
        }

        Instant now = Instant.now();
        if (attempt.getLeaseExpiresAt().isBefore(now)) {
            throw new ConflictException("Expired lease completion rejected: lease expired at " + attempt.getLeaseExpiresAt());
        }

        AttemptStatus reportedStatus = request.resultStatus();
        if (reportedStatus == null || reportedStatus.isActive() || reportedStatus == AttemptStatus.EXPIRED) {
            throw new BadRequestException("Worker completion must report a valid terminal status (SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, INTERRUPTED)");
        }
        if (reportedStatus == AttemptStatus.CANCELLED && !request.terminationConfirmed()) {
            throw new BadRequestException("Cannot report CANCELLED until isolated execution termination is confirmed");
        }

        if (reportedStatus == AttemptStatus.SUCCEEDED
                && AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE.name().equals(attempt.getAllowedOperation())
                && attempt.getSourceArchiveIssuedAt() == null) {
            throw new ConflictException("Source acquisition cannot succeed before an archive grant is issued");
        }
        AllowedWorkerOperation parsedOp = AllowedWorkerOperation.parseStrict(attempt.getAllowedOperation()).orElse(null);
        if (parsedOp != null && parsedOp.isOciOperation()) {
            if (reportedStatus == AttemptStatus.SUCCEEDED) {
                ociBuildLifecycleService.verifyCompletionPreconditions(attempt);
            } else {
                ociBuildLifecycleService.reconcileAbortedAttempt(
                        attempt,
                        StringUtils.hasText(request.summary()) ? request.summary().trim() : "OCI stage ended with " + reportedStatus,
                        reportedStatus == AttemptStatus.CANCELLED
                );
            }
        } else if (parsedOp != null && parsedOp.isKubernetesOperation()) {
            if (reportedStatus == AttemptStatus.SUCCEEDED) {
                k8sLifecycleService.verifyCompletionPreconditions(attempt);
            } else {
                k8sLifecycleService.reconcileAbortedAttempt(
                        attempt,
                        StringUtils.hasText(request.summary()) ? request.summary().trim() : "Kubernetes deployment stage ended with " + reportedStatus,
                        reportedStatus == AttemptStatus.CANCELLED
                );
            }
        }

        List<String> attemptSecrets = attemptSecretRedactionService.resolveSecretsForAttemptRedaction(attempt);
        String safeSummary = logSanitizer.sanitize(
                StringUtils.hasText(request.summary())
                        ? request.summary().trim()
                        : "Attempt #" + attempt.getAttemptNumber() + " ended with status " + reportedStatus,
                480,
                attemptSecrets
        ).message();

        AttemptFailureCategory category = resolveFailureCategory(reportedStatus, request.failureCategory());

        attempt.setStatus(reportedStatus);
        attempt.setFailureCategory(category);
        attempt.setExitCode(request.exitCode());
        attempt.setSummary(safeSummary);
        attempt.setResourceUsageJson(writeJson(sanitizeResourceUsage(request.resourceUsage(), attemptSecrets)));
        if (attempt.getStartedAt() == null) {
            attempt.setStartedAt(request.startedAt() != null ? request.startedAt() : attempt.getLeaseStartedAt());
        }
        attempt.setCompletedAt(request.completedAt() != null ? request.completedAt() : now);
        attempt.setTerminationConfirmed(request.terminationConfirmed());
        attemptRepository.save(attempt);

        releaseWorkerSlot(worker, now);

        // Apply state machine transitions cleanly
        switch (reportedStatus) {
            case SUCCEEDED -> handleStageSucceeded(run, stage, attempt, safeSummary, now);
            case CANCELLED -> handleStageCancelled(run, stage, attempt, safeSummary, now);
            case FAILED, TIMED_OUT, INTERRUPTED -> handleStageFailureOrRetry(run, stage, attempt, category, safeSummary, now);
            default -> throw new BadRequestException("Unsupported completion status: " + reportedStatus);
        }

        runRepository.flush();
        stageRunRepository.flush();
        attemptRepository.flush();
        return toDto(attempt);
    }

    @Transactional(readOnly = true)
    public List<StageExecutionAttemptDto> listWorkspaceStageAttempts(
            UUID workspaceId,
            UUID runId,
            UUID stageRunId,
            UUID userId
    ) {
        authorizationService.requireMembership(workspaceId, userId);
        stageRunRepository.findByIdAndPipelineRunIdAndWorkspaceId(stageRunId, runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found in this workspace"));

        return attemptRepository.findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAsc(stageRunId, workspaceId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private void handleStageSucceeded(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt,
            String summary,
            Instant now
    ) {
        PipelineStageStatus prevStage = stage.transitionStatus(PipelineStageStatus.SUCCEEDED, now, summary);
        stageRunRepository.save(stage);

        recordEvent(
                run,
                stage,
                "STAGE_SUCCEEDED",
                prevStage.name(),
                PipelineStageStatus.SUCCEEDED.name(),
                attempt.getWorkerId(),
                summary,
                Map.of(
                        "attemptId", attempt.getId().toString(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "exitCode", attempt.getExitCode() != null ? attempt.getExitCode() : 0
                ),
                now
        );

        evaluateDownstreamAndFinalizeRun(run, attempt.getWorkerId(), now);
    }

    private void handleStageCancelled(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt,
            String summary,
            Instant now
    ) {
        PipelineStageStatus prevStage = stage.transitionStatus(PipelineStageStatus.CANCELLED, now, summary);
        stageRunRepository.save(stage);

        recordEvent(
                run,
                stage,
                "STAGE_CANCELLED",
                prevStage.name(),
                PipelineStageStatus.CANCELLED.name(),
                attempt.getWorkerId(),
                "Isolated container termination confirmed: " + summary,
                Map.of(
                        "attemptId", attempt.getId().toString(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "terminationConfirmed", true
                ),
                now
        );

        evaluateDownstreamAndFinalizeRun(run, attempt.getWorkerId(), now);
    }

    private void handleStageFailureOrRetry(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt,
            AttemptFailureCategory category,
            String summary,
            Instant now
    ) {
        boolean cancelRequested = run.getCancellationRequestedAt() != null
                || stage.getCancellationRequestedAt() != null;

        if (cancelRequested) {
            PipelineStageStatus prev = stage.transitionStatus(
                    PipelineStageStatus.CANCELLED,
                    now,
                    "Stopped during cancellation: " + summary
            );
            stageRunRepository.save(stage);
            recordEvent(
                    run,
                    stage,
                    "STAGE_CANCELLED",
                    prev.name(),
                    PipelineStageStatus.CANCELLED.name(),
                    attempt.getWorkerId(),
                    "Stage terminated during cancellation (" + category.name() + "): " + summary,
                    Map.of("attemptNumber", attempt.getAttemptNumber(), "failureCategory", category.name()),
                    now
            );
            evaluateDownstreamAndFinalizeRun(run, attempt.getWorkerId(), now);
            return;
        }

        boolean retryEligible = category.isRetryEligible()
                && attempt.isIdempotentOperation()
                && stage.getAttemptNumber() <= stage.getMaxRetries();

        if (retryEligible) {
            long backoffSeconds = (long) workerProperties.getRetryBaseBackoffSeconds()
                    * (1L << Math.max(0, Math.min(attempt.getAttemptNumber() - 1, 5)));
            Instant retryAt = now.plusSeconds(backoffSeconds);
            String retryReason = "Attempt #" + attempt.getAttemptNumber() + " failed (" + category.name()
                    + "); retry " + attempt.getAttemptNumber() + "/" + stage.getMaxRetries()
                    + " scheduled after " + backoffSeconds + "s backoff";

            stage.requeueForRetry(now, retryAt, retryReason);
            stageRunRepository.save(stage);

            recordEvent(
                    run,
                    stage,
                    "STAGE_RETRY_SCHEDULED",
                    PipelineStageStatus.RUNNING.name(),
                    PipelineStageStatus.READY.name(),
                    attempt.getWorkerId(),
                    retryReason + " — " + summary,
                    Map.of(
                            "attemptNumber", attempt.getAttemptNumber(),
                            "maxRetries", stage.getMaxRetries(),
                            "failureCategory", category.name(),
                            "nextRetryAt", retryAt.toString()
                    ),
                    now
            );
            return;
        }

        // Retries exhausted or non-retryable failure -> terminal FAILED
        PipelineStageStatus prevStage = stage.transitionStatus(PipelineStageStatus.FAILED, now, summary);
        stageRunRepository.save(stage);

        recordEvent(
                run,
                stage,
                "STAGE_FAILED",
                prevStage.name(),
                PipelineStageStatus.FAILED.name(),
                attempt.getWorkerId(),
                summary,
                Map.of(
                        "attemptId", attempt.getId().toString(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "failureCategory", category.name(),
                        "exitCode", attempt.getExitCode() != null ? attempt.getExitCode() : -1
                ),
                now
        );

        evaluateDownstreamAndFinalizeRun(run, attempt.getWorkerId(), now);
    }

    /**
     * Evaluates downstream stage dependency progression and transitions the parent pipeline run
     * to its terminal outcome ({@code SUCCEEDED}, {@code FAILED}, or {@code CANCELLED}) once all stages complete.
     */
    public void evaluateDownstreamAndFinalizeRun(
            PipelineRunEntity run,
            String actorLabel,
            Instant now
    ) {
        List<PipelineStageRunEntity> allStages = stageRunRepository
                .findAllByPipelineRunIdOrderByExecutionOrderAsc(run.getId());
        Map<String, PipelineStageRunEntity> byKey = new LinkedHashMap<>();
        for (PipelineStageRunEntity s : allStages) {
            byKey.put(s.getStageKey(), s);
        }

        // If cancellation was requested while a stage ran, ensure no unstarted stages become READY
        if (run.getCancellationRequestedAt() != null) {
            for (PipelineStageRunEntity s : allStages) {
                if (s.getStatus() == PipelineStageStatus.PENDING || s.getStatus() == PipelineStageStatus.READY) {
                    PipelineStageStatus prev = s.transitionStatus(
                            PipelineStageStatus.CANCELLED,
                            now,
                            "Cancelled because parent pipeline run cancellation was requested"
                    );
                    stageRunRepository.save(s);
                    recordEvent(run, s, "STAGE_CANCELLED", prev.name(), PipelineStageStatus.CANCELLED.name(),
                            actorLabel, "Unstarted stage cancelled", Map.of(), now);
                }
            }
        } else {
            List<StageDependencyEvaluator.StageNodeState> states = allStages.stream()
                    .map(s -> new StageDependencyEvaluator.StageNodeState(
                            s.getStageKey(),
                            s.getStatus(),
                            readStringList(s.getDependsOnJson()),
                            s.isAllowFailure()
                    ))
                    .toList();

            List<StageDependencyEvaluator.StageTransitionDecision> decisions =
                    StageDependencyEvaluator.evaluateProgressions(states);

            for (StageDependencyEvaluator.StageTransitionDecision d : decisions) {
                PipelineStageRunEntity target = byKey.get(d.stageKey());
                if (target != null) {
                    target.transitionStatus(d.toStatus(), now, d.reason());
                    stageRunRepository.save(target);
                    recordEvent(
                            run,
                            target,
                            "STAGE_" + d.toStatus().name(),
                            d.fromStatus().name(),
                            d.toStatus().name(),
                            actorLabel,
                            d.reason(),
                            Map.of("stageType", target.getStageType().name()),
                            now
                    );
                }
            }
        }

        List<StageDependencyEvaluator.StageNodeState> updatedStates = allStages.stream()
                .map(s -> new StageDependencyEvaluator.StageNodeState(
                        s.getStageKey(),
                        s.getStatus(),
                        readStringList(s.getDependsOnJson()),
                        s.isAllowFailure()
                ))
                .toList();

        Optional<PipelineRunStatus> terminalOutcomeOpt = StageDependencyEvaluator.evaluateTerminalRunOutcome(updatedStates);
        if (terminalOutcomeOpt.isPresent() && !run.getStatus().isTerminal()) {
            PipelineRunStatus outcome = terminalOutcomeOpt.get();
            String finalReason = switch (outcome) {
                case SUCCEEDED -> "All required pipeline stages completed successfully";
                case FAILED -> "One or more required pipeline stages failed";
                case CANCELLED -> StringUtils.hasText(run.getCancellationReason())
                        ? run.getCancellationReason()
                        : "Pipeline run cancelled and active execution terminated";
                default -> "Pipeline run finished";
            };
            PipelineRunStatus prevRun = run.transitionStatus(outcome, now, finalReason, run.getCancelledBy());
            runRepository.save(run);
            sourceHandoffService.cleanupForRun(run.getId());

            recordEvent(
                    run,
                    null,
                    "RUN_" + outcome.name(),
                    prevRun.name(),
                    outcome.name(),
                    actorLabel,
                    finalReason,
                    Map.of("stageCount", allStages.size()),
                    now
            );

            auditService.record(
                    run.getWorkspace(),
                    run.getCancelledBy(),
                    "PIPELINE_RUN_" + outcome.name(),
                    "PIPELINE_RUN",
                    run.getId().toString(),
                    "Pipeline run #" + run.getRunNumber() + " for \"" + run.getDefinitionName()
                            + "\" reached terminal status " + outcome.name(),
                    Map.of(
                            "runNumber", run.getRunNumber(),
                            "outcome", outcome.name(),
                            "definitionVersion", run.getDefinitionVersion()
                    )
            );
        }
    }

    private void releaseWorkerSlot(WorkerNodeEntity worker, Instant now) {
        if (worker == null) {
            return;
        }
        int remaining = Math.max(0, worker.getActiveAssignments() - 1);
        worker.setActiveAssignments(remaining);
        worker.setLastHeartbeatAt(now);
        if (worker.getStatus() == WorkerStatus.BUSY && remaining < worker.getMaxConcurrency()) {
            worker.setStatus(WorkerStatus.AVAILABLE);
        }
        workerNodeRepository.save(worker);
    }

    private void validateAttemptOwnershipAndFence(
            StageExecutionAttemptEntity attempt,
            String workerId,
            String leaseToken,
            long fencingGeneration
    ) {
        if (!workerId.equals(attempt.getWorkerId())) {
            throw new ForbiddenOperationException("Worker \"" + workerId + "\" does not own execution attempt " + attempt.getId());
        }
        if (!leaseToken.equals(attempt.getLeaseToken())
                || fencingGeneration != attempt.getFencingGeneration()) {
            throw new ConflictException("Stale lease token or fencing generation for execution attempt " + attempt.getId());
        }
    }

    private AttemptFailureCategory resolveFailureCategory(AttemptStatus status, AttemptFailureCategory requested) {
        if (status == AttemptStatus.SUCCEEDED) {
            return AttemptFailureCategory.NONE;
        }
        if (status == AttemptStatus.CANCELLED) {
            return AttemptFailureCategory.CANCELLED;
        }
        if (status == AttemptStatus.TIMED_OUT) {
            return AttemptFailureCategory.EXECUTION_TIMEOUT;
        }
        if (requested != null && requested != AttemptFailureCategory.NONE) {
            return requested;
        }
        return AttemptFailureCategory.OPERATION_FAILURE;
    }

    private Map<String, Object> sanitizeResourceUsage(Map<String, Object> raw, List<String> attemptSecrets) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (count >= 16) {
                break;
            }
            if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
                String k = entry.getKey().trim();
                Object v = entry.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    safe.put(k, v);
                } else {
                    safe.put(k, logSanitizer.sanitize(String.valueOf(v), 120, attemptSecrets).message());
                }
                count++;
            }
        }
        return safe;
    }

    private void recordEvent(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            String eventType,
            String fromStatus,
            String toStatus,
            String actorLabel,
            String message,
            Map<String, Object> metadata,
            Instant occurredAt
    ) {
        PipelineRunEventEntity event = new PipelineRunEventEntity();
        event.setWorkspace(run.getWorkspace());
        event.setPipelineRun(run);
        event.setStageRun(stage);
        event.setStageKey(stage != null ? stage.getStageKey() : null);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setActor(null);
        event.setActorUsername("worker:" + actorLabel);
        event.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        event.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        event.setOccurredAt(occurredAt);
        runEventRepository.save(event);
    }

    public StageExecutionAttemptDto toDto(StageExecutionAttemptEntity a) {
        return new StageExecutionAttemptDto(
                a.getId(),
                a.getStageRun().getId(),
                a.getWorkerId(),
                a.getAttemptNumber(),
                a.getFencingGeneration(),
                a.getAllowedOperation(),
                a.getStatus(),
                a.getFailureCategory(),
                a.getExitCode(),
                a.getSummary(),
                readObjectMap(a.getResourceUsageJson()),
                a.getLeaseStartedAt(),
                a.getLeaseExpiresAt(),
                a.getLastHeartbeatAt(),
                a.getStartedAt(),
                a.getCompletedAt(),
                a.getCancellationRequestedAt(),
                a.isTerminationConfirmed(),
                a.getRecoveredAt()
        );
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
