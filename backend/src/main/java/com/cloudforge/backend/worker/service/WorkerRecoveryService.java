package com.cloudforge.backend.worker.service;

import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Periodically detects offline workers and recovers expired stage leases safely.
 *
 * <p>Always fences expired attempts by incrementing {@code fencingGeneration} and
 * clearing {@code activeLeaseToken} before re-queueing idempotent work so stale workers
 * can never overwrite newer attempts. Non-idempotent expired attempts are never
 * blindly redispatched.</p>
 */
@Service
public class WorkerRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WorkerRecoveryService.class);

    private final WorkerProperties workerProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final PipelineRunEventRepository runEventRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final WorkerResultProcessorService resultProcessorService;
    private final ObjectMapper objectMapper;

    public WorkerRecoveryService(
            WorkerProperties workerProperties,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            PipelineRunEventRepository runEventRepository,
            StageExecutionAttemptRepository attemptRepository,
            WorkerResultProcessorService resultProcessorService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.runEventRepository = runEventRepository;
        this.attemptRepository = attemptRepository;
        this.resultProcessorService = resultProcessorService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${cloudforge.workers.recovery-interval-ms:15000}")
    public void scheduledSweep() {
        if (!workerProperties.isEnabled() || !workerProperties.isSchedulerEnabled()) {
            return;
        }
        RecoverySummary summary = recoverExpiredWork(Instant.now());
        if (summary.markedOfflineWorkers() > 0 || summary.recoveredAttempts() > 0) {
            log.info("Worker recovery sweep markedOfflineWorkers={} recoveredAttempts={}",
                    summary.markedOfflineWorkers(), summary.recoveredAttempts());
        }
    }

    @Transactional
    public RecoverySummary recoverExpiredWork(Instant referenceTime) {
        Instant now = referenceTime != null ? referenceTime : Instant.now();
        int offlineCount = markStaleWorkersOffline(now);

        List<StageExecutionAttemptRepository.AttemptLockIds> expiredBatch = attemptRepository.findExpiredActiveAttemptIds(
                List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING),
                now,
                PageRequest.of(0, workerProperties.getMaxRecoveryBatchSize())
        );

        int recovered = 0;
        for (StageExecutionAttemptRepository.AttemptLockIds expiredIds : expiredBatch) {
            if (recoverSingleExpiredAttempt(expiredIds, now)) {
                recovered++;
            }
        }
        return new RecoverySummary(offlineCount, recovered);
    }

    private int markStaleWorkersOffline(Instant now) {
        Instant threshold = now.minusSeconds(workerProperties.getHeartbeatTimeoutSeconds());
        List<WorkerNodeEntity> stale = workerNodeRepository.findAllByStatusInAndLastHeartbeatAtBefore(
                List.of(WorkerStatus.AVAILABLE, WorkerStatus.BUSY, WorkerStatus.DRAINING),
                threshold
        );
        for (WorkerNodeEntity worker : stale) {
            worker.setStatus(WorkerStatus.OFFLINE);
            worker.setOfflineAt(now);
            workerNodeRepository.save(worker);
        }
        return stale.size();
    }

    private boolean recoverSingleExpiredAttempt(StageExecutionAttemptRepository.AttemptLockIds ids, Instant now) {
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(ids.getWorkerId()).orElse(null);

        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(ids.getPipelineRunId(), ids.getWorkspaceId())
                .orElse(null);
        if (run == null) {
            return false;
        }

        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(ids.getStageRunId())
                .orElse(null);
        if (stage == null) {
            return false;
        }

        StageExecutionAttemptEntity attempt = attemptRepository.findByIdForUpdate(ids.getAttemptId()).orElse(null);
        if (attempt == null || !attempt.getStatus().isActive() || !attempt.getLeaseExpiresAt().isBefore(now)) {
            return false;
        }

        // Fence expired attempt so old worker cannot report late results
        attempt.setStatus(AttemptStatus.EXPIRED);
        attempt.setFailureCategory(AttemptFailureCategory.LEASE_EXPIRED);
        attempt.setSummary("Worker lease expired without heartbeat renewal; attempt fenced by recovery service");
        attempt.setCompletedAt(now);
        attempt.setRecoveredAt(now);
        attemptRepository.save(attempt);

        if (worker != null) {
            worker.setActiveAssignments(Math.max(0, worker.getActiveAssignments() - 1));
            if (worker.getStatus() == WorkerStatus.BUSY && worker.getActiveAssignments() < worker.getMaxConcurrency()) {
                worker.setStatus(WorkerStatus.AVAILABLE);
            }
            workerNodeRepository.save(worker);
        }

        if (stage.getStatus().isTerminal()) {
            return true;
        }

        boolean cancelRequested = run.getCancellationRequestedAt() != null
                || stage.getCancellationRequestedAt() != null;

        if (cancelRequested) {
            stage.fenceExpiredLease();
            PipelineStageStatus prev = stage.transitionStatus(
                    PipelineStageStatus.CANCELLED,
                    now,
                    "Worker lease expired during cancellation; attempt fenced and stage marked CANCELLED"
            );
            stageRunRepository.save(stage);
            recordRecoveryEvent(
                    run,
                    stage,
                    "STAGE_LEASE_RECOVERED_CANCELLED",
                    prev.name(),
                    PipelineStageStatus.CANCELLED.name(),
                    "Expired worker \"" + attempt.getWorkerId() + "\" fenced during cancellation",
                    Map.of("attemptId", attempt.getId().toString(), "newFence", stage.getFencingGeneration()),
                    now
            );
            resultProcessorService.evaluateDownstreamAndFinalizeRun(run, "recovery", now);
            return true;
        }

        // Safe redispatch ONLY when operation is explicitly idempotent and retry budget remains
        if (attempt.isIdempotentOperation() && stage.getAttemptNumber() <= stage.getMaxRetries()) {
            long backoffSeconds = (long) workerProperties.getRetryBaseBackoffSeconds()
                    * (1L << Math.max(0, Math.min(attempt.getAttemptNumber() - 1, 5)));
            Instant retryAt = now.plusSeconds(backoffSeconds);
            String reason = "Lease expired for worker \"" + attempt.getWorkerId() + "\" (attempt #"
                    + attempt.getAttemptNumber() + "); fenced and re-queued for retry after " + backoffSeconds + "s";

            stage.requeueForRetry(now, retryAt, reason);
            stageRunRepository.save(stage);
            recordRecoveryEvent(
                    run,
                    stage,
                    "STAGE_LEASE_RECOVERED",
                    PipelineStageStatus.RUNNING.name(),
                    PipelineStageStatus.READY.name(),
                    reason,
                    Map.of(
                            "expiredWorkerId", attempt.getWorkerId(),
                            "attemptNumber", attempt.getAttemptNumber(),
                            "newFence", stage.getFencingGeneration(),
                            "nextRetryAt", retryAt.toString()
                    ),
                    now
            );
            return true;
        }

        // Non-idempotent or retries exhausted -> fence and fail stage cleanly
        stage.fenceExpiredLease();
        String failReason = !attempt.isIdempotentOperation()
                ? "Worker lease expired for non-idempotent operation; fenced without redispatch"
                : "Worker lease expired and retry limit (" + stage.getMaxRetries() + ") exhausted";
        PipelineStageStatus prev = stage.transitionStatus(PipelineStageStatus.FAILED, now, failReason);
        stageRunRepository.save(stage);
        recordRecoveryEvent(
                run,
                stage,
                "STAGE_LEASE_EXPIRED_FAILED",
                prev.name(),
                PipelineStageStatus.FAILED.name(),
                failReason,
                Map.of(
                        "expiredWorkerId", attempt.getWorkerId(),
                        "attemptNumber", attempt.getAttemptNumber(),
                        "newFence", stage.getFencingGeneration()
                ),
                now
        );
        resultProcessorService.evaluateDownstreamAndFinalizeRun(run, "recovery", now);
        return true;
    }

    private void recordRecoveryEvent(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            String eventType,
            String fromStatus,
            String toStatus,
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
        event.setActorUsername("system:recovery");
        event.setMessage(message != null && message.length() > 500 ? message.substring(0, 500) : message);
        event.setMetadataJson(writeJson(metadata));
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

    public record RecoverySummary(int markedOfflineWorkers, int recoveredAttempts) {
    }
}
