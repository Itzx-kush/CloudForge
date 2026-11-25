package com.cloudforge.backend.worker.service;

import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.dto.WorkerHeartbeatRequest;
import com.cloudforge.backend.worker.dto.WorkerHeartbeatResponse;
import com.cloudforge.backend.worker.dto.WorkerNodeDto;
import com.cloudforge.backend.worker.dto.WorkerPoolStatusDto;
import com.cloudforge.backend.worker.dto.WorkerRegistrationRequest;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages trusted worker registration, heartbeats, lease renewals, cancellation signaling,
 * availability transitions, and operational pool telemetry.
 */
@Service
public class WorkerRegistryService {

    private final WorkerProperties workerProperties;
    private final KubernetesDeploymentProperties k8sProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public WorkerRegistryService(
            WorkerProperties workerProperties,
            KubernetesDeploymentProperties k8sProperties,
            WorkerNodeRepository workerNodeRepository,
            StageExecutionAttemptRepository attemptRepository,
            PipelineStageRunRepository stageRunRepository,
            WorkspaceAuthorizationService authorizationService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.k8sProperties = k8sProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.attemptRepository = attemptRepository;
        this.stageRunRepository = stageRunRepository;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkerNodeDto registerWorker(WorkerRegistrationRequest request, WorkerPrincipal principal) {
        if (!principal.workerId().equals(request.workerId().trim())) {
            throw new ForbiddenOperationException(
                    "Authenticated worker \"" + principal.workerId()
                            + "\" cannot register under different identity \"" + request.workerId() + "\""
            );
        }

        List<String> normalizedOps = validateSupportedOperations(request.supportedOperations());
        String sourceOperation = AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE.name();
        String ociBuildOp = AllowedWorkerOperation.OCI_IMAGE_BUILD.name();
        String ociPublishOp = AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name();
        String k8sDeployOp = AllowedWorkerOperation.KUBERNETES_DEPLOY.name();
        boolean advertisesOci = normalizedOps.contains(ociBuildOp) || normalizedOps.contains(ociPublishOp);
        boolean advertisesK8s = normalizedOps.contains(k8sDeployOp);
        if (request.adapterType() == WorkerAdapterType.GITHUB_SOURCE
                ? !normalizedOps.equals(List.of(sourceOperation)) || request.maxConcurrency() != 1
                : normalizedOps.contains(sourceOperation)) {
            throw new BadRequestException("GitHub source acquisition requires a dedicated source worker");
        }
        if (request.adapterType() == WorkerAdapterType.OCI_BUILDKIT) {
            boolean onlyOciOps = !normalizedOps.isEmpty() && normalizedOps.stream()
                    .allMatch(op -> op.equals(ociBuildOp) || op.equals(ociPublishOp));
            if (!onlyOciOps || request.maxConcurrency() > 2) {
                throw new BadRequestException("OCI_BUILDKIT workers may only advertise OCI_IMAGE_BUILD / OCI_IMAGE_PUBLISH with maxConcurrency <= 2");
            }
        } else if (advertisesOci) {
            throw new BadRequestException("OCI build and publish operations require a dedicated OCI_BUILDKIT worker");
        }
        if (request.adapterType() == WorkerAdapterType.KUBERNETES_DEPLOY) {
            if (!k8sProperties.isEnabled()
                    || !k8sProperties.parsedAllowedWorkerIds().contains(principal.workerId())) {
                throw new ForbiddenOperationException(
                        "Worker \"" + principal.workerId() + "\" is not authorized for KUBERNETES_DEPLOY execution"
                );
            }
            if (!normalizedOps.equals(List.of(k8sDeployOp)) || request.maxConcurrency() > 2) {
                throw new BadRequestException("KUBERNETES_DEPLOY workers may only advertise KUBERNETES_DEPLOY with maxConcurrency <= 2");
            }
        } else if (advertisesK8s) {
            throw new BadRequestException("Kubernetes deployment requires a dedicated KUBERNETES_DEPLOY worker");
        }
        Instant now = Instant.now();

        WorkerNodeEntity node = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseGet(() -> {
                    WorkerNodeEntity created = new WorkerNodeEntity();
                    created.setWorkerId(principal.workerId());
                    created.setRegisteredAt(now);
                    return created;
                });

        node.setDisplayName(request.displayName().trim());
        node.setAdapterType(request.adapterType());
        node.setSupportedOperationsJson(writeJson(normalizedOps));
        node.setMaxConcurrency(request.maxConcurrency());
        node.setCredentialKeyId(principal.keyId());
        node.setHostLabel(trimToNull(request.hostLabel()));
        node.setAgentVersion(trimToNull(request.agentVersion()));
        node.setLastHeartbeatAt(now);
        node.setOfflineAt(null);

        long activeCount = attemptRepository.findAllByWorkerIdAndStatusIn(
                principal.workerId(),
                List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING)
        ).size();
        node.setActiveAssignments((int) activeCount);
        node.setStatus(activeCount >= node.getMaxConcurrency() ? WorkerStatus.BUSY : WorkerStatus.AVAILABLE);

        WorkerNodeEntity saved = workerNodeRepository.saveAndFlush(node);
        return toDto(saved);
    }

    @Transactional
    public WorkerHeartbeatResponse processHeartbeat(
            String pathWorkerId,
            WorkerHeartbeatRequest request,
            WorkerPrincipal principal
    ) {
        requireMatchingWorkerIdentity(pathWorkerId, principal);

        WorkerNodeEntity node = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Worker \"" + principal.workerId() + "\" is not registered; register before sending heartbeats"
                ));

        Instant now = Instant.now();
        node.setLastHeartbeatAt(now);
        node.setOfflineAt(null);

        List<WorkerHeartbeatResponse.AttemptLeaseDirective> directives = new ArrayList<>();
        if (request != null && request.activeLeases() != null) {
            for (WorkerHeartbeatRequest.ActiveLeaseHeartbeat leaseHb : request.activeLeases()) {
                directives.add(renewActiveLease(principal.workerId(), leaseHb, now));
            }
        }

        long activeCount = attemptRepository.findAllByWorkerIdAndStatusIn(
                principal.workerId(),
                List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING)
        ).size();
        node.setActiveAssignments((int) activeCount);
        if (node.getStatus() != WorkerStatus.DRAINING) {
            node.setStatus(activeCount >= node.getMaxConcurrency() ? WorkerStatus.BUSY : WorkerStatus.AVAILABLE);
        }
        workerNodeRepository.saveAndFlush(node);

        return new WorkerHeartbeatResponse(
                node.getWorkerId(),
                node.getStatus(),
                now,
                directives
        );
    }

    @Transactional
    public WorkerNodeDto updateWorkerStatus(
            String pathWorkerId,
            WorkerStatus targetStatus,
            WorkerPrincipal principal
    ) {
        requireMatchingWorkerIdentity(pathWorkerId, principal);

        WorkerNodeEntity node = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ResourceNotFoundException("Worker not found: " + principal.workerId()));

        Instant now = Instant.now();
        node.setStatus(targetStatus);
        node.setLastHeartbeatAt(now);
        if (targetStatus == WorkerStatus.OFFLINE) {
            node.setOfflineAt(now);
        } else {
            node.setOfflineAt(null);
        }
        return toDto(workerNodeRepository.saveAndFlush(node));
    }

    @Transactional(readOnly = true)
    public WorkerPoolStatusDto getWorkspacePoolStatus(UUID workspaceId, UUID userId) {
        WorkspaceMemberEntity membership = authorizationService.requireMembership(workspaceId, userId);
        List<WorkerNodeEntity> allWorkers = workerNodeRepository.findAllByOrderByWorkerIdAsc();

        long available = allWorkers.stream().filter(w -> w.getStatus() == WorkerStatus.AVAILABLE).count();
        long busy = allWorkers.stream().filter(w -> w.getStatus() == WorkerStatus.BUSY).count();
        long draining = allWorkers.stream().filter(w -> w.getStatus() == WorkerStatus.DRAINING).count();
        long offline = allWorkers.stream().filter(w -> w.getStatus() == WorkerStatus.OFFLINE).count();
        long activeAssignments = attemptRepository.countByStatusIn(List.of(AttemptStatus.CLAIMED, AttemptStatus.RUNNING));

        long workspaceReady = stageRunRepository.countByWorkspaceIdAndStatus(workspaceId, PipelineStageStatus.READY);
        long workspaceRunning = stageRunRepository.countByWorkspaceIdAndStatus(workspaceId, PipelineStageStatus.RUNNING);
        long workspaceFailedAttempts = attemptRepository.countByWorkspaceIdAndStatusIn(
                workspaceId,
                List.of(AttemptStatus.FAILED, AttemptStatus.TIMED_OUT, AttemptStatus.EXPIRED, AttemptStatus.INTERRUPTED)
        );

        // Restrict detailed global worker node metadata strictly to workspace OWNERs;
        // DEVELOPER / VIEWER receive aggregate pool telemetry and allowed operations only.
        List<WorkerNodeDto> visibleWorkers = membership.getRole() == WorkspaceRole.OWNER
                ? allWorkers.stream().map(this::toDto).toList()
                : List.of();

        List<String> supportedOperations = Arrays.stream(AllowedWorkerOperation.values())
                .map(Enum::name)
                .toList();

        return new WorkerPoolStatusDto(
                workerProperties.isEnabled(),
                workerProperties.isEnabled() && workerProperties.isSchedulerEnabled(),
                allWorkers.size(),
                available,
                busy,
                draining,
                offline,
                activeAssignments,
                workspaceReady,
                workspaceRunning,
                workspaceFailedAttempts,
                supportedOperations,
                visibleWorkers,
                Instant.now()
        );
    }

    private WorkerHeartbeatResponse.AttemptLeaseDirective renewActiveLease(
            String workerId,
            WorkerHeartbeatRequest.ActiveLeaseHeartbeat hb,
            Instant now
    ) {
        StageExecutionAttemptEntity attempt = attemptRepository.findByIdForUpdate(hb.attemptId()).orElse(null);
        if (attempt == null) {
            return new WorkerHeartbeatResponse.AttemptLeaseDirective(
                    hb.attemptId(), null, false, null, true, now, "Attempt record not found"
            );
        }
        if (!workerId.equals(attempt.getWorkerId())
                || !hb.leaseToken().equals(attempt.getLeaseToken())
                || hb.fencingGeneration() != attempt.getFencingGeneration()) {
            return new WorkerHeartbeatResponse.AttemptLeaseDirective(
                    attempt.getId(),
                    attempt.getStageRun().getId(),
                    false,
                    attempt.getLeaseExpiresAt(),
                    true,
                    now,
                    "Lease fenced or owned by a newer generation; terminate local work immediately"
            );
        }
        if (!attempt.getStatus().isActive()) {
            return new WorkerHeartbeatResponse.AttemptLeaseDirective(
                    attempt.getId(),
                    attempt.getStageRun().getId(),
                    false,
                    attempt.getLeaseExpiresAt(),
                    true,
                    now,
                    "Attempt already terminated with status " + attempt.getStatus()
            );
        }
        if (attempt.getLeaseExpiresAt().isBefore(now)) {
            return new WorkerHeartbeatResponse.AttemptLeaseDirective(
                    attempt.getId(),
                    attempt.getStageRun().getId(),
                    false,
                    attempt.getLeaseExpiresAt(),
                    true,
                    now,
                    "Lease expired before heartbeat renewal; stop work"
            );
        }

        Instant newExpiry = now.plusSeconds(workerProperties.getLeaseDurationSeconds());
        attempt.setLastHeartbeatAt(now);
        attempt.setLeaseExpiresAt(newExpiry);
        attemptRepository.save(attempt);

        PipelineStageRunEntity stage = stageRunRepository.findByIdForUpdate(attempt.getStageRun().getId()).orElse(null);
        boolean cancelRequested = attempt.getCancellationRequestedAt() != null
                || (stage != null && stage.getCancellationRequestedAt() != null);
        if (stage != null && stage.getActiveLeaseToken() != null
                && stage.getActiveLeaseToken().equals(attempt.getLeaseToken())) {
            stage.renewLease(newExpiry);
            stageRunRepository.save(stage);
        }

        Instant terminationDeadline = cancelRequested
                ? (attempt.getCancellationRequestedAt() != null
                ? attempt.getCancellationRequestedAt().plusSeconds(workerProperties.getCancelGracePeriodSeconds())
                : now.plusSeconds(workerProperties.getCancelGracePeriodSeconds()))
                : null;

        return new WorkerHeartbeatResponse.AttemptLeaseDirective(
                attempt.getId(),
                attempt.getStageRun().getId(),
                true,
                newExpiry,
                cancelRequested,
                terminationDeadline,
                cancelRequested ? "Cancellation requested — terminate isolated container and confirm" : "Lease renewed"
        );
    }

    public static void requireMatchingWorkerIdentity(String pathWorkerId, WorkerPrincipal principal) {
        if (principal == null || !principal.workerId().equals(pathWorkerId)) {
            throw new ForbiddenOperationException(
                    "Worker identity mismatch: authenticated worker cannot act on behalf of \"" + pathWorkerId + "\""
            );
        }
    }

    public List<String> readOperationsList(String json) {
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

    private List<String> validateSupportedOperations(List<String> requested) {
        Set<String> valid = new LinkedHashSet<>();
        for (String op : requested) {
            AllowedWorkerOperation parsed = AllowedWorkerOperation.parseStrict(op)
                    .orElseThrow(() -> new BadRequestException(
                            "Unsupported worker operation capability \"" + op + "\"",
                            Map.of("supportedOperations", "Allowed values: ENV_VALIDATION, CONTROLLED_SMOKE_TEST, WORKER_DIAGNOSTICS")
                    ));
            valid.add(parsed.name());
        }
        return List.copyOf(valid);
    }

    public WorkerNodeDto toDto(WorkerNodeEntity entity) {
        return new WorkerNodeDto(
                entity.getWorkerId(),
                entity.getDisplayName(),
                entity.getAdapterType(),
                entity.getStatus(),
                readOperationsList(entity.getSupportedOperationsJson()),
                entity.getMaxConcurrency(),
                entity.getActiveAssignments(),
                entity.getHostLabel(),
                entity.getAgentVersion(),
                entity.getRegisteredAt(),
                entity.getLastHeartbeatAt(),
                entity.getOfflineAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Failed to serialize worker capability metadata");
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
