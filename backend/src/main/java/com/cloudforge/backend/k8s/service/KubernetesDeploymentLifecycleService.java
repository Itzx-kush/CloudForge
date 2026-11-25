package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentOperationDto;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.cloudforge.backend.k8s.dto.WorkerKubernetesDtos;
import com.cloudforge.backend.k8s.entity.KubernetesClusterConnectionEntity;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentConfigEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentEventEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingEntity;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import com.cloudforge.backend.k8s.repository.KubernetesClusterConnectionRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentConfigRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentEventRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentOperationRepository;
import com.cloudforge.backend.k8s.repository.KubernetesNamespaceBindingRepository;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.LogSanitizer;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates worker-side Kubernetes cluster validation, deployment descriptor resolution,
 * fenced rollout status updates, readiness verification enforcement, cancellation propagation,
 * and expired-lease crash reconciliation.
 */
@Service
public class KubernetesDeploymentLifecycleService {

    public static final String META_DEPLOYMENT_CONFIG_ID = "deploymentConfigId";
    public static final String META_OCI_ARTIFACT_ID = "ociArtifactId";

    public static final List<KubernetesDeploymentStatus> ACTIVE_DEPLOYMENT_STATUSES = List.of(
            KubernetesDeploymentStatus.PENDING,
            KubernetesDeploymentStatus.VALIDATING,
            KubernetesDeploymentStatus.APPLYING,
            KubernetesDeploymentStatus.ROLLING_OUT,
            KubernetesDeploymentStatus.VERIFYING
    );

    private final WorkerProperties workerProperties;
    private final KubernetesDeploymentProperties k8sProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final KubernetesClusterConnectionRepository clusterRepository;
    private final KubernetesNamespaceBindingRepository namespaceBindingRepository;
    private final KubernetesDeploymentConfigRepository deploymentConfigRepository;
    private final KubernetesDeploymentOperationRepository deploymentOperationRepository;
    private final KubernetesDeploymentEventRepository deploymentEventRepository;
    private final OciImageArtifactRepository artifactRepository;
    private final KubernetesManifestGenerator manifestGenerator;
    private final ClusterSecurityValidator clusterSecurityValidator;
    private final LogSanitizer logSanitizer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public KubernetesDeploymentLifecycleService(
            WorkerProperties workerProperties,
            KubernetesDeploymentProperties k8sProperties,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            KubernetesClusterConnectionRepository clusterRepository,
            KubernetesNamespaceBindingRepository namespaceBindingRepository,
            KubernetesDeploymentConfigRepository deploymentConfigRepository,
            KubernetesDeploymentOperationRepository deploymentOperationRepository,
            KubernetesDeploymentEventRepository deploymentEventRepository,
            OciImageArtifactRepository artifactRepository,
            KubernetesManifestGenerator manifestGenerator,
            ClusterSecurityValidator clusterSecurityValidator,
            LogSanitizer logSanitizer,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.k8sProperties = k8sProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.clusterRepository = clusterRepository;
        this.namespaceBindingRepository = namespaceBindingRepository;
        this.deploymentConfigRepository = deploymentConfigRepository;
        this.deploymentOperationRepository = deploymentOperationRepository;
        this.deploymentEventRepository = deploymentEventRepository;
        this.artifactRepository = artifactRepository;
        this.manifestGenerator = manifestGenerator;
        this.clusterSecurityValidator = clusterSecurityValidator;
        this.logSanitizer = logSanitizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<WorkerKubernetesDtos.ClusterValidationTask> claimNextClusterValidation(
            String pathWorkerId,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!workerProperties.isEnabled() || !k8sProperties.isEnabled() || !k8sProperties.permitsDeployWorker(principal.workerId())) {
            return Optional.empty();
        }
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE
                        && w.getStatus() != WorkerStatus.DRAINING
                        && w.getAdapterType() == WorkerAdapterType.KUBERNETES_DEPLOY)
                .orElse(null);
        if (worker == null) {
            return Optional.empty();
        }

        List<UUID> candidateIds = clusterRepository.findClusterIdsByStatusOrderByValidationRequested(
                KubernetesClusterStatus.VALIDATING,
                PageRequest.of(0, 5)
        );
        for (UUID clusterId : candidateIds) {
            KubernetesClusterConnectionEntity cluster = clusterRepository.findByIdForUpdate(clusterId).orElse(null);
            if (cluster == null || cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATING) {
                continue;
            }
            List<String> expectedNamespaces = namespaceBindingRepository
                    .findAllByClusterConnectionIdAndStatus(cluster.getId(), KubernetesNamespaceBindingStatus.ACTIVE)
                    .stream()
                    .map(KubernetesNamespaceBindingEntity::getNamespaceName)
                    .toList();

            return Optional.of(new WorkerKubernetesDtos.ClusterValidationTask(
                    cluster.getId(),
                    cluster.getWorkspace().getId(),
                    cluster.getClusterIdentifier(),
                    cluster.getName(),
                    cluster.getClusterType().name(),
                    cluster.getApiServerUrl(),
                    cluster.getCaCertificateFingerprintSha256(),
                    cluster.getServiceAccountName(),
                    cluster.isAllowLocalLoopback(),
                    cluster.getValidationNonce(),
                    expectedNamespaces
            ));
        }
        return Optional.empty();
    }

    @Transactional
    public void completeClusterValidation(
            String pathWorkerId,
            UUID clusterId,
            WorkerKubernetesDtos.ClusterValidationResultRequest request,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!workerProperties.isEnabled() || !k8sProperties.isEnabled() || !k8sProperties.permitsDeployWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not authorized for Kubernetes cluster validation");
        }
        workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE && w.getAdapterType() == WorkerAdapterType.KUBERNETES_DEPLOY)
                .orElseThrow(() -> new ForbiddenOperationException("An active KUBERNETES_DEPLOY worker is required"));

        KubernetesClusterConnectionEntity cluster = clusterRepository.findByIdForUpdate(clusterId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster not found"));

        if (cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATING
                || cluster.getValidationNonce() != request.validationNonce()) {
            throw new ConflictException("Cluster validation nonce is stale or validation is no longer active");
        }

        Instant now = request.validatedAt() != null ? request.validatedAt() : Instant.now();
        cluster.setLastValidationWorkerId(principal.workerId());
        cluster.setSupportedCapabilitiesJson(writeJson(request.supportedCapabilities() == null ? Map.of() : request.supportedCapabilities()));

        if (request.valid()) {
            cluster.setConnectionStatus(KubernetesClusterStatus.VALIDATED);
            cluster.setLastValidatedAt(now);
            cluster.setLastValidationError(null);
            cluster.setKubernetesVersion(StringUtils.hasText(request.kubernetesVersion())
                    ? logSanitizer.sanitize(request.kubernetesVersion().trim(), 60).message()
                    : null);
            if (request.verifiedNamespaces() != null && !request.verifiedNamespaces().isEmpty()) {
                List<KubernetesNamespaceBindingEntity> bindings = namespaceBindingRepository
                        .findAllByClusterConnectionIdAndStatus(cluster.getId(), KubernetesNamespaceBindingStatus.ACTIVE);
                for (KubernetesNamespaceBindingEntity b : bindings) {
                    if (request.verifiedNamespaces().contains(b.getNamespaceName())) {
                        b.setVerifiedAt(now);
                        namespaceBindingRepository.save(b);
                    }
                }
            }
        } else {
            cluster.setConnectionStatus(KubernetesClusterStatus.VALIDATION_FAILED);
            String safeErr = StringUtils.hasText(request.errorSummary())
                    ? logSanitizer.sanitize(request.errorSummary().trim(), 480).message()
                    : "Cluster validation failed";
            cluster.setLastValidationError(safeErr);
        }
        clusterRepository.saveAndFlush(cluster);

        auditService.record(
                cluster.getWorkspace(),
                null,
                request.valid() ? "KUBERNETES_CLUSTER_VALIDATED" : "KUBERNETES_CLUSTER_VALIDATION_FAILED",
                "KUBERNETES_CLUSTER",
                cluster.getId().toString(),
                request.valid()
                        ? "Validated Kubernetes cluster \"" + cluster.getName() + "\" (" + cluster.getKubernetesVersion() + ")"
                        : "Cluster validation failed for \"" + cluster.getName() + "\": " + cluster.getLastValidationError(),
                Map.of(
                        "workerId", principal.workerId(),
                        "clusterIdentifier", cluster.getClusterIdentifier(),
                        "valid", request.valid(),
                        "kubernetesVersion", String.valueOf(cluster.getKubernetesVersion())
                )
        );
    }

    @Transactional
    public WorkerKubernetesDtos.DeploymentExecutionDescriptor resolveExecutionDescriptor(
            String pathWorkerId,
            UUID attemptId,
            WorkerKubernetesDtos.LeaseRequest request,
            WorkerPrincipal principal
    ) {
        LockedK8sAttempt ctx = lockAndValidateK8sAttempt(
                pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal
        );
        PipelineRunEntity run = ctx.run();
        PipelineStageRunEntity stage = ctx.stage();
        StageExecutionAttemptEntity attempt = ctx.attempt();

        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByStageRunIdForUpdate(stage.getId())
                .orElseGet(() -> initializeOperationForPipelineStage(run, stage, attempt, principal));

        KubernetesClusterConnectionEntity cluster = op.getClusterConnection();
        if (cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATED) {
            throw new ConflictException("Kubernetes cluster \"" + cluster.getName() + "\" is not VALIDATED");
        }
        clusterSecurityValidator.validateClusterEndpoint(
                cluster.getApiServerUrl(),
                cluster.getClusterType(),
                cluster.isAllowLocalLoopback()
        );

        KubernetesNamespaceBindingEntity ns = op.getNamespaceBinding();
        if (ns.getStatus() != KubernetesNamespaceBindingStatus.ACTIVE) {
            throw new ConflictException("Namespace binding \"" + ns.getNamespaceName() + "\" is not ACTIVE");
        }

        OciImageArtifactEntity artifact = op.getOciArtifact();
        if (artifact.getVerificationStatus() != OciArtifactVerificationStatus.VERIFIED
                || !OciBuildLifecycleService.isValidDigest(artifact.getManifestDigest())
                || !artifact.getImmutableReference().endsWith("@" + artifact.getManifestDigest())) {
            throw new ConflictException("OCI artifact must be VERIFIED with a valid immutable SHA-256 digest");
        }

        op.setAttempt(attempt);
        op.setAssignedWorkerId(principal.workerId());
        op.setFencingGeneration(attempt.getFencingGeneration());
        deploymentOperationRepository.saveAndFlush(op);

        Map<String, Object> snapshot = readObjectMap(op.getConfigSnapshotJson());
        KubernetesProbeConfig readiness = parseProbe(snapshot.get("readinessProbe"),
                KubernetesProbeConfig.defaultReadiness(intVal(snapshot, "containerPort", 8080)));
        KubernetesProbeConfig liveness = parseProbe(snapshot.get("livenessProbe"),
                KubernetesProbeConfig.defaultLiveness(intVal(snapshot, "containerPort", 8080)));
        KubernetesProbeConfig startup = snapshot.get("startupProbe") != null
                ? parseProbe(snapshot.get("startupProbe"), null)
                : null;

        return new WorkerKubernetesDtos.DeploymentExecutionDescriptor(
                op.getId(),
                op.getWorkspace().getId(),
                op.getProject().getId(),
                op.getEnvironment().getId(),
                op.getEnvironment().getEnvironmentType().name(),
                cluster.getId(),
                cluster.getClusterIdentifier(),
                cluster.getApiServerUrl(),
                cluster.isAllowLocalLoopback(),
                ns.getId(),
                op.getNamespaceName(),
                ns.getWorkloadServiceAccountName(),
                ns.getImagePullSecretName(),
                op.getDeploymentConfig() != null ? op.getDeploymentConfig().getId() : null,
                artifact.getId(),
                op.getDeploymentName(),
                op.getServiceName(),
                op.getImmutableImageReference(),
                op.getManifestDigest(),
                op.getSourceCommitSha(),
                op.getManifestChecksumSha256(),
                op.getRequestedReplicas(),
                intVal(snapshot, "containerPort", 8080),
                intVal(snapshot, "servicePort", 80),
                strVal(snapshot, "cpuRequest", "100m"),
                strVal(snapshot, "cpuLimit", "500m"),
                strVal(snapshot, "memoryRequest", "128Mi"),
                strVal(snapshot, "memoryLimit", "512Mi"),
                readiness,
                liveness,
                startup,
                strVal(snapshot, "rollingMaxSurge", "1"),
                strVal(snapshot, "rollingMaxUnavailable", "0"),
                intVal(snapshot, "progressDeadlineSeconds", 300),
                intVal(snapshot, "deploymentTimeoutSeconds", 300),
                boolVal(snapshot, "readOnlyRootFilesystem", true),
                boolVal(snapshot, "runAsNonRoot", true),
                longVal(snapshot, "runAsUser", 10001L)
        );
    }

    @Transactional
    public void recordStatusUpdate(
            String pathWorkerId,
            UUID attemptId,
            WorkerKubernetesDtos.DeploymentStatusUpdateRequest request,
            WorkerPrincipal principal
    ) {
        LockedK8sAttempt ctx = lockAndValidateK8sAttempt(
                pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal
        );
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByStageRunIdForUpdate(ctx.stage().getId())
                .orElseThrow(() -> new ConflictException("Deployment operation not initialized for this stage"));

        if (op.getFencingGeneration() > request.fencingGeneration()) {
            throw new ConflictException("Stale worker status update rejected by fencing generation");
        }
        if (op.getStatus().isTerminal()) {
            throw new ConflictException("Deployment operation is already terminal (" + op.getStatus() + ")");
        }
        if (request.status().isTerminal()) {
            throw new BadRequestException("Use /k8s-result endpoint for terminal deployment outcomes");
        }

        Instant now = Instant.now();
        KubernetesDeploymentStatus previous = op.transitionStatus(request.status(), now);
        if (request.applyInitiated()) {
            op.setApplyInitiated(true);
        }
        applyObservedRolloutState(
                op,
                request.observedReplicas(),
                request.updatedReplicas(),
                request.readyReplicas(),
                request.availableReplicas(),
                request.observedGeneration(),
                request.k8sDeploymentUid(),
                request.k8sServiceUid(),
                request.rolloutConditions(),
                request.podDiagnostics()
        );
        op.setFencingGeneration(request.fencingGeneration());
        op.setAssignedWorkerId(principal.workerId());
        deploymentOperationRepository.saveAndFlush(op);

        String safeMessage = StringUtils.hasText(request.message())
                ? logSanitizer.sanitize(request.message().trim(), 480).message()
                : "Deployment transitioned to " + request.status()
                + " (ready " + op.getReadyReplicas() + "/" + op.getRequestedReplicas() + ")";

        if (previous != request.status() || StringUtils.hasText(request.message())) {
            recordDeploymentEvent(
                    op,
                    "DEPLOYMENT_" + request.status().name(),
                    previous.name(),
                    request.status().name(),
                    "worker:" + principal.workerId(),
                    safeMessage,
                    Map.of(
                            "readyReplicas", op.getReadyReplicas(),
                            "availableReplicas", op.getAvailableReplicas(),
                            "updatedReplicas", op.getUpdatedReplicas(),
                            "requestedReplicas", op.getRequestedReplicas(),
                            "applyInitiated", op.isApplyInitiated()
                    ),
                    now
            );
        }
    }

    @Transactional
    public void reportDeploymentResult(
            String pathWorkerId,
            UUID attemptId,
            WorkerKubernetesDtos.DeploymentResultReportRequest request,
            WorkerPrincipal principal
    ) {
        LockedK8sAttempt ctx = lockAndValidateK8sAttempt(
                pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal
        );
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByStageRunIdForUpdate(ctx.stage().getId())
                .orElseThrow(() -> new ConflictException("Deployment operation not initialized for this stage"));

        if (op.getFencingGeneration() > request.fencingGeneration()) {
            throw new ConflictException("Stale worker deployment result rejected by fencing generation");
        }
        if (op.getStatus().isTerminal()) {
            throw new ConflictException("Deployment operation is already terminal (" + op.getStatus() + ")");
        }
        if (!request.status().isTerminal()) {
            throw new BadRequestException("Deployment result must report a terminal status");
        }

        Instant now = request.completedAt() != null ? request.completedAt() : Instant.now();
        if (request.applyInitiated()) {
            op.setApplyInitiated(true);
        }
        applyObservedRolloutState(
                op,
                request.observedReplicas(),
                request.updatedReplicas(),
                request.readyReplicas(),
                request.availableReplicas(),
                request.observedGeneration(),
                request.k8sDeploymentUid(),
                request.k8sServiceUid(),
                request.rolloutConditions(),
                request.podDiagnostics()
        );

        String safeError = StringUtils.hasText(request.errorSummary())
                ? logSanitizer.sanitize(request.errorSummary().trim(), 480).message()
                : null;

        if (request.status() == KubernetesDeploymentStatus.SUCCEEDED) {
            // Strict server-side verification: NEVER accept SUCCEEDED without readiness and exact digest match
            String verifiedDigest = request.verifiedImageDigest() != null
                    ? request.verifiedImageDigest().trim().toLowerCase(Locale.ROOT)
                    : null;
            if (!op.getManifestDigest().equals(verifiedDigest)
                    || op.getReadyReplicas() < op.getRequestedReplicas()
                    || op.getAvailableReplicas() < op.getRequestedReplicas()
                    || op.getUpdatedReplicas() < op.getRequestedReplicas()
                    || !StringUtils.hasText(op.getK8sDeploymentUid())) {
                throw new ConflictException(
                        "Cannot mark Kubernetes deployment SUCCEEDED until desired replicas are ready/available and the deployed immutable image digest is verified"
                );
            }
            if (op.getStatus() == KubernetesDeploymentStatus.ROLLING_OUT || op.getStatus() == KubernetesDeploymentStatus.APPLYING) {
                op.transitionStatus(KubernetesDeploymentStatus.VERIFYING, now);
            }
            KubernetesDeploymentStatus prev = op.transitionStatus(KubernetesDeploymentStatus.SUCCEEDED, now);
            op.setFailureCategory(null);
            op.setErrorSummary(null);
            deploymentOperationRepository.saveAndFlush(op);

            recordDeploymentEvent(
                    op,
                    "DEPLOYMENT_SUCCEEDED",
                    prev.name(),
                    KubernetesDeploymentStatus.SUCCEEDED.name(),
                    "worker:" + principal.workerId(),
                    "Verified healthy rollout of " + op.getDeploymentName() + " (" + op.getReadyReplicas()
                            + "/" + op.getRequestedReplicas() + " ready) running " + op.getManifestDigest(),
                    Map.of(
                            "deploymentUid", op.getK8sDeploymentUid(),
                            "serviceUid", String.valueOf(op.getK8sServiceUid()),
                            "verifiedDigest", verifiedDigest,
                            "readyReplicas", op.getReadyReplicas()
                    ),
                    now
            );

            auditService.record(
                    op.getWorkspace(),
                    null,
                    "KUBERNETES_DEPLOYMENT_SUCCEEDED",
                    "KUBERNETES_DEPLOYMENT",
                    op.getId().toString(),
                    "Deployment \"" + op.getDeploymentName() + "\" in namespace \"" + op.getNamespaceName()
                            + "\" succeeded with verified digest " + op.getManifestDigest(),
                    Map.of(
                            "clusterIdentifier", op.getClusterConnection().getClusterIdentifier(),
                            "namespace", op.getNamespaceName(),
                            "deploymentName", op.getDeploymentName(),
                            "manifestDigest", op.getManifestDigest(),
                            "readyReplicas", op.getReadyReplicas()
                    )
            );
            return;
        }

        // Terminal failure, cancellation, or reconciliation required
        KubernetesDeploymentStatus targetStatus = request.status();
        // If cancelled after apply was initiated and rollout did not finish cleanly, record RECONCILIATION_REQUIRED
        if (targetStatus == KubernetesDeploymentStatus.CANCELLED && op.isApplyInitiated()) {
            targetStatus = KubernetesDeploymentStatus.RECONCILIATION_REQUIRED;
            safeError = (safeError != null ? safeError + " — " : "")
                    + "Cancellation occurred after Kubernetes apply began; inspect cluster workload state before retrying (no automatic rollback performed).";
        }
        KubernetesDeploymentStatus prev = op.transitionStatus(targetStatus, now);
        op.setFailureCategory(request.failureCategory());
        op.setErrorSummary(safeError);
        deploymentOperationRepository.saveAndFlush(op);

        recordDeploymentEvent(
                op,
                "DEPLOYMENT_" + targetStatus.name(),
                prev.name(),
                targetStatus.name(),
                "worker:" + principal.workerId(),
                safeError != null ? safeError : "Deployment ended with status " + targetStatus,
                Map.of(
                        "failureCategory", String.valueOf(request.failureCategory()),
                        "applyInitiated", op.isApplyInitiated(),
                        "readyReplicas", op.getReadyReplicas(),
                        "requestedReplicas", op.getRequestedReplicas()
                ),
                now
        );

        auditService.record(
                op.getWorkspace(),
                null,
                "KUBERNETES_DEPLOYMENT_" + targetStatus.name(),
                "KUBERNETES_DEPLOYMENT",
                op.getId().toString(),
                "Deployment \"" + op.getDeploymentName() + "\" ended with " + targetStatus
                        + (safeError != null ? ": " + safeError : ""),
                Map.of(
                        "clusterIdentifier", op.getClusterConnection().getClusterIdentifier(),
                        "namespace", op.getNamespaceName(),
                        "status", targetStatus.name(),
                        "failureCategory", String.valueOf(request.failureCategory())
                )
        );
    }

    /**
     * Enforced by {@code WorkerResultProcessorService} before allowing a {@code KUBERNETES_DEPLOY}
     * stage attempt to be marked {@code SUCCEEDED}.
     */
    @Transactional(readOnly = true)
    public void verifyCompletionPreconditions(StageExecutionAttemptEntity attempt) {
        if (!AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(attempt.getAllowedOperation())) {
            return;
        }
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByStageRunId(attempt.getStageRun().getId())
                .orElseThrow(() -> new ConflictException(
                        "KUBERNETES_DEPLOY stage cannot succeed without a persisted verified deployment operation"
                ));
        if (op.getStatus() != KubernetesDeploymentStatus.SUCCEEDED
                || op.getReadyReplicas() < op.getRequestedReplicas()
                || op.getAvailableReplicas() < op.getRequestedReplicas()
                || !StringUtils.hasText(op.getK8sDeploymentUid())) {
            throw new ConflictException(
                    "KUBERNETES_DEPLOY stage cannot succeed until Kubernetes rollout readiness and immutable image digest are verified"
            );
        }
    }

    /**
     * Reconciles crashed, lease-expired, or aborted deployment attempts safely.
     * If Kubernetes apply was already initiated, transitions to {@code RECONCILIATION_REQUIRED}
     * rather than blindly replaying or falsely claiming clean cancellation.
     */
    @Transactional
    public void reconcileAbortedAttempt(StageExecutionAttemptEntity attempt, String reason, boolean cancelled) {
        if (attempt == null || !AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(attempt.getAllowedOperation())) {
            return;
        }
        deploymentOperationRepository.findByStageRunIdForUpdate(attempt.getStageRun().getId()).ifPresent(op -> {
            if (op.getStatus().isTerminal()) {
                return;
            }
            Instant now = Instant.now();
            KubernetesDeploymentStatus target;
            String message;
            if (op.isApplyInitiated()) {
                target = KubernetesDeploymentStatus.RECONCILIATION_REQUIRED;
                message = reason + " (Kubernetes apply was already initiated; inspect live cluster state before retrying — no automatic rollback performed)";
            } else if (cancelled) {
                target = KubernetesDeploymentStatus.CANCELLED;
                message = reason;
            } else {
                target = KubernetesDeploymentStatus.FAILED;
                message = reason;
            }
            KubernetesDeploymentStatus prev = op.transitionStatus(target, now);
            op.setFailureCategory(target == KubernetesDeploymentStatus.RECONCILIATION_REQUIRED
                    ? "K8S_RECONCILIATION_REQUIRED"
                    : (cancelled ? "CANCELLED" : "LEASE_EXPIRED"));
            op.setErrorSummary(logSanitizer.sanitize(message, 480).message());
            deploymentOperationRepository.save(op);

            recordDeploymentEvent(
                    op,
                    "DEPLOYMENT_" + target.name(),
                    prev.name(),
                    target.name(),
                    "system:reconciliation",
                    op.getErrorSummary(),
                    Map.of("applyInitiated", op.isApplyInitiated(), "cancelled", cancelled),
                    now
            );
        });
    }

    /**
     * Propagates pipeline run cancellation to linked Kubernetes deployment operations.
     */
    @Transactional
    public void handlePipelineRunCancellation(UUID pipelineRunId, UserEntity actor, String reason, Instant now) {
        List<KubernetesDeploymentOperationEntity> ops = deploymentOperationRepository.findAllByPipelineRunIdForUpdate(pipelineRunId);
        for (KubernetesDeploymentOperationEntity op : ops) {
            if (op.getStatus().isTerminal()) {
                continue;
            }
            if (op.getCancellationRequestedAt() == null) {
                op.setCancellationRequestedAt(now);
                op.setCancelledBy(actor);
            }
            if (op.getStatus() == KubernetesDeploymentStatus.PENDING) {
                KubernetesDeploymentStatus prev = op.transitionStatus(KubernetesDeploymentStatus.CANCELLED, now);
                op.setFailureCategory("CANCELLED");
                op.setErrorSummary(reason);
                deploymentOperationRepository.save(op);
                recordDeploymentEvent(
                        op,
                        "DEPLOYMENT_CANCELLED",
                        prev.name(),
                        KubernetesDeploymentStatus.CANCELLED.name(),
                        actor != null ? "user:" + actor.getUsername() : "system",
                        "Cancelled before Kubernetes worker dispatch: " + reason,
                        Map.of("applyInitiated", false),
                        now
                );
            } else {
                deploymentOperationRepository.save(op);
                recordDeploymentEvent(
                        op,
                        "DEPLOYMENT_CANCELLATION_REQUESTED",
                        op.getStatus().name(),
                        op.getStatus().name(),
                        actor != null ? "user:" + actor.getUsername() : "system",
                        "Cancellation requested while deployment is " + op.getStatus()
                                + "; signaling active worker to halt further writes (note: cancellation does not perform automatic rollback)",
                        Map.of("applyInitiated", op.isApplyInitiated()),
                        now
                );
            }
        }
    }

    /**
     * Initializes a deployment operation for a DAG pipeline {@code DEPLOYMENT} stage that
     * links a saved {@code deploymentConfigId} and resolves its verified OCI image artifact
     * from either explicit stage metadata or the upstream {@code OCI_IMAGE_PUBLISH} stage in the same run.
     */
    private KubernetesDeploymentOperationEntity initializeOperationForPipelineStage(
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt,
            WorkerPrincipal principal
    ) {
        Map<String, String> meta = readStringMap(stage.getMetadataJson());
        String rawConfigId = meta.get(META_DEPLOYMENT_CONFIG_ID);
        if (!StringUtils.hasText(rawConfigId)) {
            throw new ConflictException("Pipeline DEPLOYMENT stage requires a deploymentConfigId or direct deployment operation");
        }
        UUID configId;
        try {
            configId = UUID.fromString(rawConfigId.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid deploymentConfigId UUID in stage metadata");
        }
        KubernetesDeploymentConfigEntity cfg = deploymentConfigRepository
                .findByIdAndWorkspaceIdWithDetails(configId, run.getWorkspace().getId())
                .orElseThrow(() -> new ForbiddenOperationException("Referenced deployment config does not belong to this workspace"));
        if (!cfg.getProject().getId().equals(run.getProject().getId())) {
            throw new ForbiddenOperationException("Deployment config project mismatch");
        }
        if (cfg.getEnvironment().getEnvironmentType() == EnvironmentType.PRODUCTION
                && !k8sProperties.isAllowProductionDeployments()) {
            throw new ForbiddenOperationException("Automated production deployments are disabled by platform policy");
        }

        OciImageArtifactEntity artifact;
        String rawArtId = meta.get(META_OCI_ARTIFACT_ID);
        if (StringUtils.hasText(rawArtId)) {
            artifact = artifactRepository
                    .findByIdAndWorkspaceIdWithDetails(UUID.fromString(rawArtId.trim()), run.getWorkspace().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Referenced OCI artifact not found"));
        } else {
            artifact = artifactRepository
                    .findAllByRunAndWorkspaceWithDetails(run.getId(), run.getWorkspace().getId())
                    .stream()
                    .filter(a -> a.getVerificationStatus() == OciArtifactVerificationStatus.VERIFIED)
                    .findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "No VERIFIED OCI image artifact was produced upstream in this pipeline run"
                    ));
        }

        if (!artifact.getProject().getId().equals(run.getProject().getId())
                || artifact.getVerificationStatus() != OciArtifactVerificationStatus.VERIFIED) {
            throw new ConflictException("Deployment requires a VERIFIED OCI image artifact owned by the same project");
        }

        KubernetesProbeConfig readiness = readProbeJson(cfg.getReadinessProbeJson(), KubernetesProbeConfig.defaultReadiness(cfg.getContainerPort()));
        KubernetesProbeConfig liveness = readProbeJson(cfg.getLivenessProbeJson(), KubernetesProbeConfig.defaultLiveness(cfg.getContainerPort()));
        KubernetesProbeConfig startup = StringUtils.hasText(cfg.getStartupProbeJson())
                ? readProbeJson(cfg.getStartupProbeJson(), null)
                : null;

        KubernetesManifestGenerator.GeneratedManifestBundle bundle = manifestGenerator.generate(
                new KubernetesManifestGenerator.ManifestInput(
                        run.getWorkspace().getId(),
                        run.getProject().getId(),
                        cfg.getEnvironment().getId(),
                        cfg.getEnvironment().getEnvironmentType().name(),
                        cfg.getId(),
                        artifact.getId(),
                        cfg.getNamespaceName(),
                        cfg.getNamespaceBinding().getWorkloadServiceAccountName(),
                        cfg.getNamespaceBinding().getImagePullSecretName(),
                        cfg.getDeploymentName(),
                        cfg.getServiceName(),
                        artifact.getImmutableReference(),
                        artifact.getManifestDigest(),
                        artifact.getCommitSha(),
                        cfg.getReplicaCount(),
                        cfg.getContainerPort(),
                        cfg.getServicePort(),
                        cfg.getCpuRequest(),
                        cfg.getCpuLimit(),
                        cfg.getMemoryRequest(),
                        cfg.getMemoryLimit(),
                        readiness,
                        liveness,
                        startup,
                        cfg.getRollingMaxSurge(),
                        cfg.getRollingMaxUnavailable(),
                        cfg.getProgressDeadlineSeconds(),
                        cfg.isReadOnlyRootFilesystem(),
                        cfg.isRunAsNonRoot(),
                        cfg.getRunAsUser()
                )
        );

        KubernetesDeploymentOperationEntity op = new KubernetesDeploymentOperationEntity();
        op.setWorkspace(run.getWorkspace());
        op.setProject(run.getProject());
        op.setEnvironment(cfg.getEnvironment());
        op.setClusterConnection(cfg.getClusterConnection());
        op.setNamespaceBinding(cfg.getNamespaceBinding());
        op.setDeploymentConfig(cfg);
        op.setOciArtifact(artifact);
        op.setPipelineRun(run);
        op.setStageRun(stage);
        op.setAttempt(attempt);
        op.setNamespaceName(cfg.getNamespaceName());
        op.setDeploymentName(cfg.getDeploymentName());
        op.setServiceName(cfg.getServiceName());
        op.setImmutableImageReference(artifact.getImmutableReference());
        op.setManifestDigest(artifact.getManifestDigest());
        op.setSourceCommitSha(artifact.getCommitSha());
        op.setManifestChecksumSha256(bundle.manifestChecksumSha256());
        op.setConfigSnapshotJson(writeJson(Map.ofEntries(
                Map.entry("deploymentName", cfg.getDeploymentName()),
                Map.entry("serviceName", cfg.getServiceName()),
                Map.entry("replicaCount", cfg.getReplicaCount()),
                Map.entry("containerPort", cfg.getContainerPort()),
                Map.entry("servicePort", cfg.getServicePort()),
                Map.entry("cpuRequest", cfg.getCpuRequest()),
                Map.entry("cpuLimit", cfg.getCpuLimit()),
                Map.entry("memoryRequest", cfg.getMemoryRequest()),
                Map.entry("memoryLimit", cfg.getMemoryLimit()),
                Map.entry("readinessProbe", readiness),
                Map.entry("livenessProbe", liveness),
                Map.entry("rollingMaxSurge", cfg.getRollingMaxSurge()),
                Map.entry("rollingMaxUnavailable", cfg.getRollingMaxUnavailable()),
                Map.entry("progressDeadlineSeconds", cfg.getProgressDeadlineSeconds()),
                Map.entry("deploymentTimeoutSeconds", cfg.getDeploymentTimeoutSeconds()),
                Map.entry("readOnlyRootFilesystem", cfg.isReadOnlyRootFilesystem()),
                Map.entry("runAsNonRoot", cfg.isRunAsNonRoot()),
                Map.entry("runAsUser", cfg.getRunAsUser())
        )));
        op.setRequestedReplicas(cfg.getReplicaCount());
        op.setRequestedBy(run.getRequestedBy());
        op.setAssignedWorkerId(principal.workerId());
        op.setFencingGeneration(attempt.getFencingGeneration());
        KubernetesDeploymentOperationEntity saved = deploymentOperationRepository.saveAndFlush(op);

        recordDeploymentEvent(
                saved,
                "DEPLOYMENT_INITIALIZED_FROM_PIPELINE",
                null,
                KubernetesDeploymentStatus.PENDING.name(),
                "worker:" + principal.workerId(),
                "Initialized Kubernetes deployment for pipeline run #" + run.getRunNumber()
                        + " with verified digest " + artifact.getManifestDigest(),
                Map.of("pipelineRunId", run.getId().toString(), "artifactId", artifact.getId().toString()),
                Instant.now()
        );
        return saved;
    }

    private LockedK8sAttempt lockAndValidateK8sAttempt(
            String pathWorkerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!workerProperties.isEnabled() || !k8sProperties.isEnabled()) {
            throw new ForbiddenOperationException("Kubernetes deployment execution is disabled");
        }
        if (!k8sProperties.permitsDeployWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not allowlisted for Kubernetes deployment execution");
        }

        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .filter(ids -> principal.workerId().equals(ids.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Execution attempt unavailable"));

        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE && w.getAdapterType() == WorkerAdapterType.KUBERNETES_DEPLOY)
                .orElseThrow(() -> new ForbiddenOperationException("An active KUBERNETES_DEPLOY worker is required"));
        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(lockIds.getPipelineRunId(), lockIds.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found"));
        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(lockIds.getStageRunId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found"));
        StageExecutionAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found"));

        Instant now = Instant.now();
        if (!AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(attempt.getAllowedOperation())
                || attempt.getStatus() != AttemptStatus.RUNNING
                || stage.getStatus() != PipelineStageStatus.RUNNING
                || run.getStatus().isTerminal()
                || attempt.isTerminationConfirmed()
                || attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null || !stage.getLeaseExpiresAt().isAfter(now)
                || !leaseToken.equals(attempt.getLeaseToken())
                || !leaseToken.equals(stage.getActiveLeaseToken())
                || fencingGeneration != attempt.getFencingGeneration()
                || fencingGeneration != stage.getFencingGeneration()) {
            throw new ConflictException("Active unexpired lease and matching fencing generation are required");
        }
        return new LockedK8sAttempt(worker, run, stage, attempt);
    }

    private void applyObservedRolloutState(
            KubernetesDeploymentOperationEntity op,
            Integer observedReplicas,
            Integer updatedReplicas,
            Integer readyReplicas,
            Integer availableReplicas,
            Long observedGeneration,
            String deploymentUid,
            String serviceUid,
            List<Map<String, Object>> rolloutConditions,
            List<Map<String, Object>> podDiagnostics
    ) {
        if (observedReplicas != null) op.setObservedReplicas(observedReplicas);
        if (updatedReplicas != null) op.setUpdatedReplicas(updatedReplicas);
        if (readyReplicas != null) op.setReadyReplicas(readyReplicas);
        if (availableReplicas != null) op.setAvailableReplicas(availableReplicas);
        if (observedGeneration != null) op.setObservedGeneration(observedGeneration);
        if (StringUtils.hasText(deploymentUid)) op.setK8sDeploymentUid(deploymentUid.trim());
        if (StringUtils.hasText(serviceUid)) op.setK8sServiceUid(serviceUid.trim());
        if (rolloutConditions != null) op.setRolloutConditionsJson(writeJson(rolloutConditions));
        if (podDiagnostics != null) op.setPodDiagnosticsJson(writeJson(podDiagnostics));
    }

    public void recordDeploymentEvent(
            KubernetesDeploymentOperationEntity op,
            String eventType,
            String fromStatus,
            String toStatus,
            String actorLabel,
            String message,
            Map<String, Object> metadata,
            Instant occurredAt
    ) {
        KubernetesDeploymentEventEntity event = new KubernetesDeploymentEventEntity();
        event.setWorkspace(op.getWorkspace());
        event.setDeploymentOperation(op);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setActorLabel(actorLabel);
        event.setMessage(message);
        event.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        event.setOccurredAt(occurredAt != null ? occurredAt : Instant.now());
        deploymentEventRepository.save(event);
    }

    public KubernetesDeploymentOperationDto toOperationDto(KubernetesDeploymentOperationEntity op) {
        return new KubernetesDeploymentOperationDto(
                op.getId(),
                op.getWorkspace().getId(),
                op.getProject().getId(),
                op.getProject().getName(),
                op.getEnvironment().getId(),
                op.getEnvironment().getName(),
                op.getEnvironment().getEnvironmentType(),
                op.getClusterConnection().getId(),
                op.getClusterConnection().getName(),
                op.getClusterConnection().getClusterIdentifier(),
                op.getNamespaceBinding().getId(),
                op.getNamespaceName(),
                op.getDeploymentConfig() != null ? op.getDeploymentConfig().getId() : null,
                op.getOciArtifact().getId(),
                op.getPipelineRun().getId(),
                op.getPipelineRun().getRunNumber(),
                op.getStageRun().getId(),
                op.getAttempt() != null ? op.getAttempt().getId() : null,
                op.getDeploymentName(),
                op.getServiceName(),
                op.getImmutableImageReference(),
                op.getManifestDigest(),
                op.getSourceCommitSha(),
                op.getManifestChecksumSha256(),
                readObjectMap(op.getConfigSnapshotJson()),
                op.getRequestedReplicas(),
                op.getObservedReplicas(),
                op.getUpdatedReplicas(),
                op.getReadyReplicas(),
                op.getAvailableReplicas(),
                op.getObservedGeneration(),
                op.getK8sDeploymentUid(),
                op.getK8sServiceUid(),
                readMapList(op.getRolloutConditionsJson()),
                readMapList(op.getPodDiagnosticsJson()),
                op.getStatus(),
                op.isApplyInitiated(),
                op.getAssignedWorkerId(),
                op.getFencingGeneration(),
                op.getRequestedBy() != null ? op.getRequestedBy().getUsername() : null,
                op.getCancelledBy() != null ? op.getCancelledBy().getUsername() : null,
                op.getCancellationRequestedAt(),
                op.getFailureCategory(),
                op.getErrorSummary(),
                op.getStartedAt(),
                op.getCompletedAt(),
                op.getCreatedAt(),
                op.getUpdatedAt()
        );
    }

    private KubernetesProbeConfig parseProbe(Object raw, KubernetesProbeConfig fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return objectMapper.convertValue(raw, KubernetesProbeConfig.class);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private KubernetesProbeConfig readProbeJson(String json, KubernetesProbeConfig fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, KubernetesProbeConfig.class);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private Map<String, String> readStringMap(String json) {
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

    public Map<String, Object> readObjectMap(String json) {
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

    public List<Map<String, Object>> readMapList(String json) {
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static int intVal(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static long longVal(Map<String, Object> map, String key, long fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    private static boolean boolVal(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static String strVal(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private record LockedK8sAttempt(
            WorkerNodeEntity worker,
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt
    ) {
    }
}
