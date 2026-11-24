package com.cloudforge.backend.worker.service;

import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.worker.config.WorkerProperties;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, fair FIFO stage scheduler and atomic lease manager.
 *
 * <p>Discovers eligible {@code READY} stages matching a trusted worker's advertised
 * allowlisted capabilities, acquires row-level locks in deterministic order
 * ({@code Worker -> Run -> Stage}), creates a fenced {@link StageExecutionAttemptEntity},
 * and commits immediately before returning the {@link WorkerAssignmentContract.LeasedStageWork}
 * so database transactions are never held open during external container execution.</p>
 */
@Service
public class WorkerSchedulerService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WorkerProperties workerProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final PipelineRunEventRepository runEventRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final WorkerRegistryService workerRegistryService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public WorkerSchedulerService(
            WorkerProperties workerProperties,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            PipelineRunEventRepository runEventRepository,
            StageExecutionAttemptRepository attemptRepository,
            WorkerRegistryService workerRegistryService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.runEventRepository = runEventRepository;
        this.attemptRepository = attemptRepository;
        this.workerRegistryService = workerRegistryService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<WorkerAssignmentContract.LeasedStageWork> claimNextEligibleStage(
            String pathWorkerId,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        if (!workerProperties.isEnabled()) {
            return Optional.empty();
        }

        // Lock 1: Worker node row
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Worker \"" + principal.workerId() + "\" must register before claiming work"
                ));

        Instant now = Instant.now();
        worker.setLastHeartbeatAt(now);

        if (worker.getStatus() == WorkerStatus.DRAINING || worker.getStatus() == WorkerStatus.OFFLINE) {
            workerNodeRepository.save(worker);
            return Optional.empty();
        }

        int currentActive = attemptRepository.findAllByWorkerIdAndStatusIn(
                worker.getWorkerId(),
                List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING)
        ).size();
        worker.setActiveAssignments(currentActive);
        if (currentActive >= worker.getMaxConcurrency()) {
            worker.setStatus(WorkerStatus.BUSY);
            workerNodeRepository.save(worker);
            return Optional.empty();
        }

        List<String> supportedOperations = workerRegistryService.readOperationsList(worker.getSupportedOperationsJson())
                .stream()
                .filter(op -> AllowedWorkerOperation.parseStrict(op).isPresent())
                .toList();
        if (supportedOperations.isEmpty()) {
            return Optional.empty();
        }

        List<PipelineStageRunRepository.EligibleStageCandidate> candidates =
                stageRunRepository.findEligibleReadyStages(
                        supportedOperations,
                        now,
                        PageRequest.of(0, workerProperties.getMaxClaimBatchSize())
                );

        for (PipelineStageRunRepository.EligibleStageCandidate candidate : candidates) {
            Optional<WorkerAssignmentContract.LeasedStageWork> claimed = tryClaimCandidateStage(
                    worker,
                    candidate.getStageId(),
                    candidate.getPipelineRunId(),
                    candidate.getWorkspaceId(),
                    supportedOperations,
                    now
            );
            if (claimed.isPresent()) {
                return claimed;
            }
        }

        worker.setStatus(currentActive >= worker.getMaxConcurrency() ? WorkerStatus.BUSY : WorkerStatus.AVAILABLE);
        workerNodeRepository.save(worker);
        return Optional.empty();
    }

    private Optional<WorkerAssignmentContract.LeasedStageWork> tryClaimCandidateStage(
            WorkerNodeEntity worker,
            UUID stageId,
            UUID runId,
            UUID workspaceId,
            List<String> workerSupportedOperations,
            Instant now
    ) {
        // Strict lock order: Worker -> Run -> Stage (neither Run nor Stage was loaded into L1 cache yet)
        // Lock 2: Pipeline run row
        Optional<PipelineRunEntity> runOpt = runRepository.findByIdAndWorkspaceIdForUpdate(runId, workspaceId);
        if (runOpt.isEmpty()) {
            return Optional.empty();
        }
        PipelineRunEntity run = runOpt.get();
        if (run.getStatus().isTerminal() || run.getCancellationRequestedAt() != null) {
            return Optional.empty();
        }

        // Lock 3: Stage run row
        Optional<PipelineStageRunEntity> stageOpt = stageRunRepository.findByIdForUpdate(stageId);
        if (stageOpt.isEmpty()) {
            return Optional.empty();
        }
        PipelineStageRunEntity stage = stageOpt.get();

        // Re-verify all scheduling invariants inside pessimistic lock
        if (stage.getStatus() != PipelineStageStatus.READY
                || stage.getActiveLeaseToken() != null
                || stage.getCancellationRequestedAt() != null
                || (stage.getNextRetryAt() != null && stage.getNextRetryAt().isAfter(now))
                || stage.getAttemptNumber() > stage.getMaxRetries()) {
            return Optional.empty();
        }

        if (!StringUtils.hasText(stage.getAllowedOperation())
                || !workerSupportedOperations.contains(stage.getAllowedOperation())) {
            return Optional.empty();
        }
        Optional<AllowedWorkerOperation> operationOpt = AllowedWorkerOperation.parseStrict(stage.getAllowedOperation())
                .filter(op -> op.supportsStageType(stage.getStageType()));
        if (operationOpt.isEmpty()) {
            return Optional.empty();
        }
        AllowedWorkerOperation operation = operationOpt.get();

        // Transition parent run QUEUED -> RUNNING via state machine if first execution
        if (run.getStatus() == PipelineRunStatus.QUEUED) {
            PipelineRunStatus prevRun = run.transitionStatus(
                    PipelineRunStatus.RUNNING,
                    now,
                    "Execution started on isolated worker \"" + worker.getWorkerId() + "\"",
                    null
            );
            runRepository.save(run);
            recordEvent(
                    run,
                    null,
                    "RUN_RUNNING",
                    prevRun.name(),
                    PipelineRunStatus.RUNNING.name(),
                    worker.getWorkerId(),
                    "Pipeline run started execution on isolated worker \"" + worker.getWorkerId() + "\"",
                    Map.of("workerId", worker.getWorkerId()),
                    now
            );
            auditService.record(
                    run.getWorkspace(),
                    null,
                    "PIPELINE_RUN_STARTED",
                    "PIPELINE_RUN",
                    run.getId().toString(),
                    "Started pipeline run #" + run.getRunNumber() + " on isolated worker \"" + worker.getWorkerId() + "\"",
                    Map.of(
                            "workerId", worker.getWorkerId(),
                            "runNumber", run.getRunNumber(),
                            "stageKey", stage.getStageKey()
                    )
            );
        }

        String leaseToken = generateLeaseToken();
        Instant leaseExpiresAt = now.plusSeconds(workerProperties.getLeaseDurationSeconds());
        long fencingGeneration = stage.assignLease(
                worker.getWorkerId(),
                leaseToken,
                leaseExpiresAt,
                now,
                "Claimed by isolated worker \"" + worker.getWorkerId() + "\" for allowlisted operation " + operation.name()
        );
        stageRunRepository.save(stage);

        StageExecutionAttemptEntity attempt = new StageExecutionAttemptEntity();
        attempt.setWorkspace(run.getWorkspace());
        attempt.setPipelineRun(run);
        attempt.setStageRun(stage);
        attempt.setWorkerId(worker.getWorkerId());
        attempt.setAttemptNumber(stage.getAttemptNumber());
        attempt.setFencingGeneration(fencingGeneration);
        attempt.setLeaseToken(leaseToken);
        attempt.setAllowedOperation(operation.name());
        attempt.setIdempotentOperation(operation.isIdempotent());
        attempt.setStatus(AttemptStatus.CLAIMED);
        attempt.setFailureCategory(AttemptFailureCategory.NONE);
        attempt.setLeaseStartedAt(now);
        attempt.setLeaseExpiresAt(leaseExpiresAt);
        attempt.setLastHeartbeatAt(now);
        StageExecutionAttemptEntity savedAttempt = attemptRepository.saveAndFlush(attempt);

        int nextActive = worker.getActiveAssignments() + 1;
        worker.setActiveAssignments(nextActive);
        worker.setStatus(nextActive >= worker.getMaxConcurrency() ? WorkerStatus.BUSY : WorkerStatus.AVAILABLE);
        workerNodeRepository.saveAndFlush(worker);

        recordEvent(
                run,
                stage,
                "STAGE_CLAIMED",
                PipelineStageStatus.READY.name(),
                PipelineStageStatus.RUNNING.name(),
                worker.getWorkerId(),
                "Worker \"" + worker.getWorkerId() + "\" claimed attempt #" + stage.getAttemptNumber()
                        + " (fence=" + fencingGeneration + ", op=" + operation.name() + ")",
                Map.of(
                        "attemptId", savedAttempt.getId().toString(),
                        "attemptNumber", stage.getAttemptNumber(),
                        "fencingGeneration", fencingGeneration,
                        "workerId", worker.getWorkerId(),
                        "allowedOperation", operation.name()
                ),
                now
        );

        String repoFullName = run.getLinkedRepository() != null ? run.getLinkedRepository().getFullName() : null;
        WorkerAssignmentContract.StageDispatchDescriptor descriptor =
                new WorkerAssignmentContract.StageDispatchDescriptor(
                        run.getWorkspace().getId(),
                        run.getProject().getId(),
                        run.getId(),
                        stage.getId(),
                        stage.getStageKey(),
                        stage.getDisplayName(),
                        stage.getStageType(),
                        stage.getExecutionOrder(),
                        run.getDefinitionVersion(),
                        repoFullName,
                        run.getTargetBranch(),
                        run.getCommitSha(),
                        stage.getTimeoutSeconds(),
                        stage.getMaxRetries(),
                        stage.getAttemptNumber()
                );

        return Optional.of(new WorkerAssignmentContract.LeasedStageWork(
                descriptor,
                savedAttempt.getId(),
                worker.getWorkerId(),
                leaseToken,
                fencingGeneration,
                operation.name(),
                operation.isIdempotent(),
                now,
                leaseExpiresAt,
                workerProperties.getHeartbeatIntervalSeconds(),
                workerProperties.getMaxLogLinesPerAttempt(),
                workerProperties.getMaxLogBytesPerAttempt()
        ));
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static String generateLeaseToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
