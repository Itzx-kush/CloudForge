package com.cloudforge.backend.source;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.github.GitHubSourceClient;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
public class WorkerSourceController {

    private final WorkerProperties workers;
    private final ProjectSourceService source;
    private final StageExecutionAttemptRepository attempts;
    private final WorkerNodeRepository nodes;
    private final PipelineRunRepository runs;
    private final PipelineStageRunRepository stages;

    public WorkerSourceController(WorkerProperties workers, ProjectSourceService source,
                                  StageExecutionAttemptRepository attempts, WorkerNodeRepository nodes,
                                  PipelineRunRepository runs, PipelineStageRunRepository stages) {
        this.workers = workers;
        this.source = source;
        this.attempts = attempts;
        this.nodes = nodes;
        this.runs = runs;
        this.stages = stages;
    }

    @Transactional
    @PostMapping("/api/internal/workers/{workerId}/attempts/{attemptId}/source-archive")
    public ResponseEntity<ArchiveGrant> issueArchive(@PathVariable String workerId, @PathVariable UUID attemptId,
                                                   @Valid @RequestBody LeaseProof proof, Authentication authentication,
                                                   HttpServletRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WorkerPrincipal principal)
                || !workers.isEnabled() || !request.isSecure() || !source.permitsWorker(workerId)) {
            throw new ForbiddenOperationException("Source acquisition requires an approved worker over HTTPS");
        }
        WorkerRegistryService.requireMatchingWorkerIdentity(workerId, principal);

        var ids = attempts.findLockIdsById(attemptId).filter(a -> workerId.equals(a.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Source acquisition attempt is unavailable"));
        // Share lock order with the existing fencing and recovery services.
        nodes.findByWorkerIdForUpdate(workerId)
                .filter(node -> node.getStatus() != WorkerStatus.OFFLINE
                        && node.getAdapterType() == WorkerAdapterType.GITHUB_SOURCE)
                .orElseThrow(() -> new ForbiddenOperationException("An active source worker is required"));
        var run = runs.findByIdAndWorkspaceIdForUpdate(ids.getPipelineRunId(), ids.getWorkspaceId())
                .orElseThrow(WorkerSourceController::unavailable);
        var stage = stages.findByIdForUpdate(ids.getStageRunId()).orElseThrow(WorkerSourceController::unavailable);
        var attempt = attempts.findByIdForUpdate(attemptId).orElseThrow(WorkerSourceController::unavailable);
        Instant now = Instant.now();
        String operation = AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE.name();
        if (attempt.getStatus() != AttemptStatus.RUNNING || stage.getStatus() != PipelineStageStatus.RUNNING
                || run.getStatus().isTerminal() || attempt.isTerminationConfirmed()
                || attempt.getSourceArchiveIssuedAt() != null
                || attempt.getCancellationRequestedAt() != null || stage.getCancellationRequestedAt() != null
                || run.getCancellationRequestedAt() != null || !operation.equals(attempt.getAllowedOperation())
                || !operation.equals(stage.getAllowedOperation()) || stage.isHasSecretBindings()
                || attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null || !stage.getLeaseExpiresAt().isAfter(now)
                || !proof.leaseToken().equals(attempt.getLeaseToken())
                || !proof.leaseToken().equals(stage.getActiveLeaseToken())
                || proof.fencingGeneration() != attempt.getFencingGeneration()
                || proof.fencingGeneration() != stage.getFencingGeneration()
                || !GitHubSourceClient.isCommitSha(run.getCommitSha())) {
            throw new ConflictException("Source acquisition requires a current lease and a pinned GitHub commit");
        }

        URI archive = source.requestArchive(run.getWorkspace().getId(), run.getLinkedRepository(), run.getCommitSha());
        Instant issuedAt = Instant.now();
        if (!attempt.getLeaseExpiresAt().isAfter(issuedAt) || !stage.getLeaseExpiresAt().isAfter(issuedAt)) {
            throw new ConflictException("Source archive lease expired before issuance");
        }
        attempt.setSourceArchiveIssuedAt(issuedAt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ArchiveGrant(archive));
    }

    private static ConflictException unavailable() {
        return new ConflictException("Source acquisition attempt is unavailable");
    }

    public record LeaseProof(@NotBlank @Size(max = 128) String leaseToken, @Positive long fencingGeneration) {
        @Override
        public String toString() {
            return "LeaseProof[fencingGeneration=" + fencingGeneration + "]";
        }
    }

    public record ArchiveGrant(URI downloadUrl) {
        @Override
        public String toString() {
            return "ArchiveGrant[downloadUrl=REDACTED]";
        }
    }
}
