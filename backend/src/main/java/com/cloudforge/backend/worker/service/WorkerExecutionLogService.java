package com.cloudforge.backend.worker.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.secrets.service.AttemptSecretRedactionService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.dto.AppendExecutionLogsRequest;
import com.cloudforge.backend.worker.dto.AppendExecutionLogsResponse;
import com.cloudforge.backend.worker.dto.StageExecutionLogDto;
import com.cloudforge.backend.worker.entity.LogStreamType;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.StageExecutionLogEntity;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.StageExecutionLogRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists and serves bounded, ordered, defense-in-depth sanitized execution logs
 * scoped strictly by workspace, pipeline run, stage run, and execution attempt.
 */
@Service
public class WorkerExecutionLogService {

    private final WorkerProperties workerProperties;
    private final StageExecutionAttemptRepository attemptRepository;
    private final StageExecutionLogRepository logRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final LogSanitizer logSanitizer;
    private final AttemptSecretRedactionService attemptSecretRedactionService;

    public WorkerExecutionLogService(
            WorkerProperties workerProperties,
            StageExecutionAttemptRepository attemptRepository,
            StageExecutionLogRepository logRepository,
            PipelineStageRunRepository stageRunRepository,
            WorkspaceAuthorizationService authorizationService,
            LogSanitizer logSanitizer,
            AttemptSecretRedactionService attemptSecretRedactionService
    ) {
        this.workerProperties = workerProperties;
        this.attemptRepository = attemptRepository;
        this.logRepository = logRepository;
        this.stageRunRepository = stageRunRepository;
        this.authorizationService = authorizationService;
        this.logSanitizer = logSanitizer;
        this.attemptSecretRedactionService = attemptSecretRedactionService;
    }

    @Transactional
    public AppendExecutionLogsResponse appendWorkerLogs(
            String pathWorkerId,
            UUID attemptId,
            AppendExecutionLogsRequest request,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        StageExecutionAttemptEntity attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        if (!principal.workerId().equals(attempt.getWorkerId())) {
            throw new ForbiddenOperationException("Attempt is leased to a different worker");
        }
        if (!request.leaseToken().equals(attempt.getLeaseToken())
                || request.fencingGeneration() != attempt.getFencingGeneration()) {
            throw new ConflictException("Stale lease token or fencing generation; log upload rejected");
        }
        if (!attempt.getStatus().isActive()) {
            throw new ConflictException("Cannot append logs to terminal execution attempt (" + attempt.getStatus() + ")");
        }

        int maxLines = workerProperties.getMaxLogLinesPerAttempt();
        int maxBytes = workerProperties.getMaxLogBytesPerAttempt();
        int maxMsgLen = workerProperties.getMaxLogMessageLength();

        long existingCount = logRepository.countByAttemptId(attempt.getId());
        long existingChars = logRepository.sumMessageCharsByAttemptId(attempt.getId());
        int seq = logRepository.findMaxSequenceNumberByAttemptId(attempt.getId());

        List<String> attemptSecrets = attemptSecretRedactionService.resolveSecretsForAttemptRedaction(attempt);
        List<String> expandedVariants = logSanitizer.expandSecretVariants(attemptSecrets);
        StageExecutionLogEntity previousEntity = (!expandedVariants.isEmpty() && existingCount > 0)
                ? logRepository.findTopByAttemptIdOrderBySequenceNumberDesc(attempt.getId()).orElse(null)
                : null;

        int accepted = 0;
        int dropped = 0;
        boolean limitReached = existingCount >= maxLines || existingChars >= maxBytes;

        for (AppendExecutionLogsRequest.LogEntryInput input : request.entries()) {
            if (existingCount >= maxLines || existingChars >= maxBytes) {
                limitReached = true;
                dropped++;
                continue;
            }
            LogSanitizer.SanitizedLogResult sanitized = logSanitizer.sanitize(input.message(), maxMsgLen, attemptSecrets);
            String msg = sanitized.message();
            boolean truncated = sanitized.truncated();

            if (previousEntity != null && !expandedVariants.isEmpty()) {
                LogSanitizer.BoundaryRedactionResult boundary = logSanitizer.redactBoundarySpan(
                        previousEntity.getMessage(),
                        msg,
                        expandedVariants
                );
                if (boundary.previousModified()) {
                    previousEntity.setMessage(boundary.previousMessage());
                    logRepository.save(previousEntity);
                    msg = boundary.currentMessage();
                }
            }

            long remainingChars = maxBytes - existingChars;
            if (msg.length() > remainingChars) {
                msg = msg.substring(0, (int) Math.max(0, remainingChars));
                truncated = true;
                limitReached = true;
            }

            seq++;
            StageExecutionLogEntity entity = new StageExecutionLogEntity();
            entity.setWorkspace(attempt.getWorkspace());
            entity.setPipelineRun(attempt.getPipelineRun());
            entity.setStageRun(attempt.getStageRun());
            entity.setAttempt(attempt);
            entity.setAttemptNumber(attempt.getAttemptNumber());
            entity.setWorkerId(principal.workerId());
            entity.setSequenceNumber(seq);
            entity.setStreamType(input.streamType() != null ? input.streamType() : LogStreamType.STDOUT);
            entity.setMessage(msg);
            entity.setTruncated(truncated);
            entity.setOccurredAt(input.occurredAt() != null ? input.occurredAt() : Instant.now());

            previousEntity = logRepository.save(entity);
            existingCount++;
            existingChars += msg.length();
            accepted++;
        }

        if (limitReached && dropped > 0 && existingCount <= maxLines) {
            seq++;
            StageExecutionLogEntity marker = new StageExecutionLogEntity();
            marker.setWorkspace(attempt.getWorkspace());
            marker.setPipelineRun(attempt.getPipelineRun());
            marker.setStageRun(attempt.getStageRun());
            marker.setAttempt(attempt);
            marker.setAttemptNumber(attempt.getAttemptNumber());
            marker.setWorkerId("control-plane");
            marker.setSequenceNumber(seq);
            marker.setStreamType(LogStreamType.SYSTEM);
            marker.setMessage("[CloudForge] Execution log storage limit reached ("
                    + maxLines + " lines / " + maxBytes + " bytes); " + dropped + " additional line(s) truncated.");
            marker.setTruncated(true);
            marker.setOccurredAt(Instant.now());
            logRepository.save(marker);
        }

        logRepository.flush();
        return new AppendExecutionLogsResponse(accepted, dropped, limitReached, seq);
    }

    @Transactional(readOnly = true)
    public PagedResponse<StageExecutionLogDto> listWorkspaceStageLogs(
            UUID workspaceId,
            UUID runId,
            UUID stageRunId,
            Integer attemptNumber,
            Pageable pageable,
            UUID userId
    ) {
        authorizationService.requireMembership(workspaceId, userId);
        stageRunRepository.findByIdAndPipelineRunIdAndWorkspaceId(stageRunId, runId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found in this workspace"));

        Page<StageExecutionLogEntity> page = attemptNumber != null
                ? logRepository.findAllByStageRunIdAndWorkspaceIdAndAttemptNumberOrderBySequenceNumberAsc(
                stageRunId, workspaceId, attemptNumber, pageable)
                : logRepository.findAllByStageRunIdAndWorkspaceIdOrderByAttemptNumberAscSequenceNumberAsc(
                stageRunId, workspaceId, pageable);

        return PagedResponse.from(page, this::toDto);
    }

    private StageExecutionLogDto toDto(StageExecutionLogEntity e) {
        return new StageExecutionLogDto(
                e.getId(),
                e.getAttempt().getId(),
                e.getAttemptNumber(),
                e.getWorkerId(),
                e.getSequenceNumber(),
                e.getStreamType(),
                e.getMessage(),
                e.isTruncated(),
                e.getOccurredAt()
        );
    }
}
