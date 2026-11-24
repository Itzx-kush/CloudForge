package com.cloudforge.backend.secrets.service;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.SecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretManagementUnavailableException;
import com.cloudforge.backend.secrets.dto.WorkerSecretDeliveryRequest;
import com.cloudforge.backend.secrets.dto.WorkerSecretDeliveryResponse;
import com.cloudforge.backend.secrets.entity.PipelineRunSecretReferenceEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import com.cloudforge.backend.secrets.repository.PipelineRunSecretReferenceRepository;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces strict lease-bound, fenced, short-lived secret delivery exclusively for
 * authenticated isolated workers holding an active, non-revoked stage attempt lease.
 */
@Service
public class SecretDeliveryService {

    private final WorkerProperties workerProperties;
    private final SecretManagementProperties secretProperties;
    private final SecretKeyProvider keyProvider;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final PipelineRunSecretReferenceRepository secretReferenceRepository;
    private final SecretManagementService secretManagementService;
    private final SecretAuditService secretAuditService;

    public SecretDeliveryService(
            WorkerProperties workerProperties,
            SecretManagementProperties secretProperties,
            SecretKeyProvider keyProvider,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            PipelineRunSecretReferenceRepository secretReferenceRepository,
            SecretManagementService secretManagementService,
            SecretAuditService secretAuditService
    ) {
        this.workerProperties = workerProperties;
        this.secretProperties = secretProperties;
        this.keyProvider = keyProvider;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.secretReferenceRepository = secretReferenceRepository;
        this.secretManagementService = secretManagementService;
        this.secretAuditService = secretAuditService;
    }

    @Transactional
    public WorkerSecretDeliveryResponse deliverSecretsForAttempt(
            String pathWorkerId,
            UUID attemptId,
            WorkerSecretDeliveryRequest request,
            WorkerPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        if (!workerProperties.isEnabled()) {
            deny(null, attemptId, principal, "workers_disabled", Map.of());
            throw new SecretManagementUnavailableException("Worker execution is disabled in control-plane configuration");
        }
        if (!secretProperties.isWorkerDeliveryEnabled()) {
            deny(null, attemptId, principal, "worker_delivery_disabled", Map.of());
            throw new SecretManagementUnavailableException("Worker secret delivery is disabled (CLOUDFORGE_SECRETS_WORKER_DELIVERY_ENABLED=false)");
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            deny(null, attemptId, principal, "key_provider_unavailable", Map.of());
            throw new SecretManagementUnavailableException(keyProvider.getStatusDiagnostic());
        }
        if (!isSecureOrAllowedLocalTransport(httpRequest)) {
            deny(null, attemptId, principal, "insecure_transport_rejected", Map.of());
            throw new ForbiddenOperationException("HTTPS transport is required for worker secret delivery");
        }

        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        if (!principal.workerId().equals(lockIds.getWorkerId())) {
            deny(lockIds.getWorkspaceId(), attemptId, principal, "worker_attempt_mismatch", Map.of(
                    "expectedWorkerId", lockIds.getWorkerId()
            ));
            throw new ForbiddenOperationException("Execution attempt is assigned to a different worker");
        }

        // Strict Phase 3.3/3.4 lock order: Worker -> Run -> Stage -> Attempt -> Refs
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ForbiddenOperationException("Registered worker node not found"));
        if (worker.getStatus() == WorkerStatus.OFFLINE) {
            deny(lockIds.getWorkspaceId(), attemptId, principal, "worker_offline", Map.of());
            throw new ForbiddenOperationException("Offline worker cannot fetch secrets");
        }

        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(lockIds.getPipelineRunId(), lockIds.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found for execution attempt"));
        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(lockIds.getStageRunId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found for execution attempt"));
        StageExecutionAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found: " + attemptId));

        UUID workspaceId = run.getWorkspace().getId();

        if (!principal.workerId().equals(attempt.getWorkerId())) {
            deny(workspaceId, attemptId, principal, "worker_attempt_mismatch", Map.of());
            throw new ForbiddenOperationException("Attempt is leased to a different worker");
        }
        if (!attempt.getStatus().isActive() || attempt.isTerminationConfirmed()) {
            deny(workspaceId, attemptId, principal, "attempt_not_active", Map.of(
                    "attemptStatus", attempt.getStatus().name()
            ));
            throw new ConflictException("Secret delivery rejected: attempt is not active (" + attempt.getStatus() + ")");
        }
        if (attempt.getCancellationRequestedAt() != null
                || stage.getCancellationRequestedAt() != null
                || run.getCancellationRequestedAt() != null) {
            deny(workspaceId, attemptId, principal, "run_or_stage_cancelling", Map.of());
            throw new ConflictException("Secret delivery rejected: run or stage cancellation requested");
        }
        if (run.getStatus().isTerminal() || stage.getStatus().isTerminal() || stage.getStatus() != PipelineStageStatus.RUNNING) {
            deny(workspaceId, attemptId, principal, "stage_or_run_not_running", Map.of(
                    "runStatus", run.getStatus().name(),
                    "stageStatus", stage.getStatus().name()
            ));
            throw new ConflictException("Secret delivery rejected: stage or run is not running");
        }

        Instant now = Instant.now();
        if (attempt.getLeaseExpiresAt() == null
                || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null
                || !stage.getLeaseExpiresAt().isAfter(now)) {
            deny(workspaceId, attemptId, principal, "lease_expired", Map.of());
            throw new ConflictException("Secret delivery rejected: stage execution lease has expired");
        }
        if (stage.getActiveLeaseToken() == null
                || !stage.getActiveLeaseToken().equals(request.leaseToken())
                || !attempt.getLeaseToken().equals(request.leaseToken())) {
            deny(workspaceId, attemptId, principal, "lease_token_mismatch", Map.of());
            throw new ConflictException("Secret delivery rejected: invalid or stale lease token");
        }
        if (attempt.getFencingGeneration() != request.fencingGeneration()
                || stage.getFencingGeneration() != request.fencingGeneration()) {
            deny(workspaceId, attemptId, principal, "stale_fencing_generation", Map.of(
                    "presentedFence", request.fencingGeneration(),
                    "currentFence", stage.getFencingGeneration()
            ));
            throw new ConflictException("Secret delivery rejected: stale fencing generation");
        }

        AllowedWorkerOperation operation = AllowedWorkerOperation.parseStrict(stage.getAllowedOperation())
                .filter(op -> op.supportsStageType(stage.getStageType()))
                .orElse(null);
        if (operation == null) {
            deny(workspaceId, attemptId, principal, "unsupported_stage_operation", Map.of());
            throw new ForbiddenOperationException("Secret delivery rejected: stage operation is not allowlisted");
        }
        if (!stage.isHasSecretBindings()) {
            deny(workspaceId, attemptId, principal, "no_secret_bindings_authorized", Map.of());
            throw new ForbiddenOperationException("Stage has no authorized secret bindings");
        }
        if (attempt.getSecretDeliveryCount() >= secretProperties.getMaxDeliveriesPerAttempt()) {
            deny(workspaceId, attemptId, principal, "delivery_limit_exceeded", Map.of(
                    "deliveryCount", attempt.getSecretDeliveryCount()
            ));
            throw new ConflictException("Secret delivery rejected: maximum deliveries per attempt exceeded");
        }

        List<PipelineRunSecretReferenceEntity> refs =
                secretReferenceRepository.findAllByStageRunIdWithSecretDetailsForUpdate(stage.getId());
        if (refs.isEmpty()) {
            deny(workspaceId, attemptId, principal, "pinned_references_missing", Map.of());
            throw new ConflictException("Secret delivery rejected: no pinned secret references exist for this stage run");
        }
        if (run.getTargetEnvironment() == null) {
            deny(workspaceId, attemptId, principal, "target_environment_missing", Map.of());
            throw new ConflictException("Secret delivery rejected: target environment is missing on pipeline run");
        }

        UUID runEnvId = run.getTargetEnvironment().getId();
        for (PipelineRunSecretReferenceEntity ref : refs) {
            if (!ref.getWorkspace().getId().equals(workspaceId)
                    || !ref.getProject().getId().equals(run.getProject().getId())
                    || !ref.getEnvironment().getId().equals(runEnvId)) {
                deny(workspaceId, attemptId, principal, "cross_scope_reference_detected", Map.of(
                        "entryId", ref.getSecret().getId().toString()
                ));
                throw new ForbiddenOperationException("Secret delivery rejected: scope boundary mismatch");
            }
            if (ref.getSecret().getStatus() != SecretLifecycleStatus.ACTIVE) {
                deny(workspaceId, attemptId, principal, "bound_secret_revoked_or_deleted", Map.of(
                        "entryId", ref.getSecret().getId().toString(),
                        "entryStatus", ref.getSecret().getStatus().name()
                ));
                throw new ConflictException(
                        "Secret delivery rejected: secret \"" + ref.getSecretName()
                                + "\" is " + ref.getSecret().getStatus()
                );
            }
            if (ref.getSecretVersion().getStatus() != SecretVersionStatus.ACTIVE) {
                deny(workspaceId, attemptId, principal, "pinned_version_revoked", Map.of(
                        "entryId", ref.getSecret().getId().toString(),
                        "versionNumber", ref.getPinnedVersionNumber()
                ));
                throw new ConflictException(
                        "Secret delivery rejected: pinned version v" + ref.getPinnedVersionNumber()
                                + " of \"" + ref.getSecretName() + "\" has been revoked"
                );
            }
        }

        List<WorkerSecretDeliveryResponse.DeliveredSecretEntry> deliveredEntries = new ArrayList<>(refs.size());
        List<Map<String, Object>> auditRefs = new ArrayList<>(refs.size());
        for (PipelineRunSecretReferenceEntity ref : refs) {
            String plaintextValue = secretManagementService.decryptVersionValue(ref.getSecret(), ref.getSecretVersion());
            ref.setDeliveryCount(ref.getDeliveryCount() + 1);
            ref.setLastDeliveredAt(now);
            ref.setLastDeliveredAttemptId(attempt.getId());

            deliveredEntries.add(new WorkerSecretDeliveryResponse.DeliveredSecretEntry(
                    ref.getDeliveryAlias(),
                    ref.getSecretName(),
                    ref.getSecret().getId(),
                    ref.getSecretVersion().getId(),
                    ref.getPinnedVersionNumber(),
                    plaintextValue
            ));
            auditRefs.add(Map.of(
                    "entryId", ref.getSecret().getId().toString(),
                    "entryName", ref.getSecretName(),
                    "targetAlias", ref.getDeliveryAlias(),
                    "pinnedVersion", ref.getPinnedVersionNumber()
            ));
        }

        secretReferenceRepository.saveAll(refs);
        attempt.setSecretDeliveryCount(attempt.getSecretDeliveryCount() + 1);
        attempt.setLastSecretDeliveryAt(now);
        attemptRepository.save(attempt);

        secretAuditService.recordSecretEvent(
                run.getWorkspace(),
                null,
                SecretAuditService.ACTION_SECRET_ACCESS_GRANTED,
                "WORKER_SECRET_DELIVERY",
                attempt.getId().toString(),
                "Delivered " + deliveredEntries.size() + " bound secret(s) to worker \"" + principal.workerId()
                        + "\" for pipeline run #" + run.getRunNumber() + " stage \"" + stage.getStageKey() + "\"",
                Map.of(
                        "workerId", principal.workerId(),
                        "attemptId", attempt.getId().toString(),
                        "pipelineRunId", run.getId().toString(),
                        "stageRunId", stage.getId().toString(),
                        "stageKey", stage.getStageKey(),
                        "fencingGeneration", attempt.getFencingGeneration(),
                        "environmentId", runEnvId.toString(),
                        "deliveredRefs", auditRefs
                )
        );

        return new WorkerSecretDeliveryResponse(
                attempt.getId(),
                run.getId(),
                stage.getId(),
                attempt.getFencingGeneration(),
                now,
                deliveredEntries
        );
    }

    private boolean isSecureOrAllowedLocalTransport(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        if (!secretProperties.isAllowInsecureLocalDelivery()) {
            return false;
        }
        return isLoopbackAddress(request.getRemoteAddr()) && isLoopbackHost(request.getServerName());
    }

    private boolean isLoopbackAddress(String addr) {
        if (!StringUtils.hasText(addr)) {
            return false;
        }
        try {
            return InetAddress.getByName(addr.trim()).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private boolean isLoopbackHost(String serverName) {
        if (!StringUtils.hasText(serverName)) {
            return false;
        }
        String normalized = serverName.trim().toLowerCase();
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private void deny(
            UUID workspaceId,
            UUID attemptId,
            WorkerPrincipal principal,
            String reason,
            Map<String, Object> extraMetadata
    ) {
        Map<String, Object> meta = new LinkedHashMap<>(extraMetadata);
        meta.put("workerId", principal.workerId());
        meta.put("attemptId", attemptId.toString());
        secretAuditService.recordDeniedAccess(
                workspaceId,
                null,
                "WORKER_SECRET_DELIVERY",
                attemptId.toString(),
                "worker:" + principal.workerId(),
                reason,
                meta
        );
    }
}
