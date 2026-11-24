package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.dto.WorkerKubernetesDtos;
import com.cloudforge.backend.k8s.entity.KubernetesClusterConnectionEntity;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.repository.KubernetesClusterConnectionRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentOperationRepository;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.secrets.config.SecretManagementProperties;
import com.cloudforge.backend.secrets.crypto.SecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretManagementUnavailableException;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.entity.SecretVersionStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.secrets.service.SecretAuditService;
import com.cloudforge.backend.secrets.service.SecretManagementService;
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
 * Resolves Phase 3.4 AES-256-GCM encrypted Kubernetes ServiceAccount bearer tokens strictly
 * for authenticated, allowlisted {@code KUBERNETES_DEPLOY} workers executing either an active
 * cluster validation job or a leased, fenced {@code KUBERNETES_DEPLOY} stage attempt.
 *
 * <p>Never delivers Kubernetes credentials to browser sessions, {@code DOCKER_ISOLATED},
 * {@code GITHUB_SOURCE}, or {@code OCI_BUILDKIT} workers.</p>
 */
@Service
public class ClusterCredentialResolver {

    private final WorkerProperties workerProperties;
    private final KubernetesDeploymentProperties k8sProperties;
    private final SecretManagementProperties secretProperties;
    private final SecretKeyProvider keyProvider;
    private final SecretManagementService secretManagementService;
    private final EnvironmentSecretVersionRepository secretVersionRepository;
    private final SecretAuditService secretAuditService;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final KubernetesClusterConnectionRepository clusterRepository;
    private final KubernetesDeploymentOperationRepository deploymentOperationRepository;
    private final ClusterSecurityValidator clusterSecurityValidator;

    public ClusterCredentialResolver(
            WorkerProperties workerProperties,
            KubernetesDeploymentProperties k8sProperties,
            SecretManagementProperties secretProperties,
            SecretKeyProvider keyProvider,
            SecretManagementService secretManagementService,
            EnvironmentSecretVersionRepository secretVersionRepository,
            SecretAuditService secretAuditService,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            KubernetesClusterConnectionRepository clusterRepository,
            KubernetesDeploymentOperationRepository deploymentOperationRepository,
            ClusterSecurityValidator clusterSecurityValidator
    ) {
        this.workerProperties = workerProperties;
        this.k8sProperties = k8sProperties;
        this.secretProperties = secretProperties;
        this.keyProvider = keyProvider;
        this.secretManagementService = secretManagementService;
        this.secretVersionRepository = secretVersionRepository;
        this.secretAuditService = secretAuditService;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.clusterRepository = clusterRepository;
        this.deploymentOperationRepository = deploymentOperationRepository;
        this.clusterSecurityValidator = clusterSecurityValidator;
    }

    @Transactional
    public WorkerKubernetesDtos.ClusterCredentialsResponse resolveForClusterValidation(
            String pathWorkerId,
            UUID clusterId,
            WorkerKubernetesDtos.ClusterValidationNonceRequest request,
            WorkerPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        verifyBaseWorkerAccess(pathWorkerId, clusterId.toString(), principal, httpRequest);

        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ForbiddenOperationException("Registered worker node not found"));
        if (worker.getStatus() == WorkerStatus.OFFLINE || worker.getAdapterType() != WorkerAdapterType.KUBERNETES_DEPLOY) {
            deny(null, clusterId.toString(), principal, "invalid_worker_adapter", Map.of());
            throw new ForbiddenOperationException("An active KUBERNETES_DEPLOY worker is required");
        }

        KubernetesClusterConnectionEntity cluster = clusterRepository.findByIdForUpdate(clusterId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster not found"));

        UUID workspaceId = cluster.getWorkspace().getId();
        if (cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATING
                || cluster.getValidationNonce() != request.validationNonce()) {
            deny(workspaceId, clusterId.toString(), principal, "stale_validation_nonce", Map.of());
            throw new ConflictException("Cluster validation nonce is stale or validation is not active");
        }

        clusterSecurityValidator.validateClusterEndpoint(
                cluster.getApiServerUrl(),
                cluster.getClusterType(),
                cluster.isAllowLocalLoopback()
        );
        String bearerToken = decryptActiveClusterToken(workspaceId, cluster, clusterId.toString(), principal);
        Instant now = Instant.now();

        secretAuditService.recordSecretEvent(
                cluster.getWorkspace(),
                null,
                SecretAuditService.ACTION_SECRET_ACCESS_GRANTED,
                "KUBERNETES_CLUSTER_VALIDATION_CREDENTIAL",
                cluster.getId().toString(),
                "Delivered encrypted Kubernetes ServiceAccount token for cluster validation \"" + cluster.getClusterIdentifier()
                        + "\" to worker \"" + principal.workerId() + "\"",
                Map.of(
                        "workerId", principal.workerId(),
                        "clusterId", cluster.getId().toString(),
                        "clusterIdentifier", cluster.getClusterIdentifier(),
                        "validationNonce", request.validationNonce()
                )
        );

        return new WorkerKubernetesDtos.ClusterCredentialsResponse(
                cluster.getId(),
                cluster.getApiServerUrl(),
                cluster.getCaCertificatePem(),
                bearerToken,
                cluster.getServiceAccountName(),
                cluster.isAllowLocalLoopback(),
                now
        );
    }

    @Transactional
    public WorkerKubernetesDtos.ClusterCredentialsResponse resolveForDeploymentAttempt(
            String pathWorkerId,
            UUID attemptId,
            WorkerKubernetesDtos.LeaseRequest request,
            WorkerPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        verifyBaseWorkerAccess(pathWorkerId, attemptId.toString(), principal, httpRequest);

        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .filter(ids -> principal.workerId().equals(ids.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Execution attempt unavailable"));

        // Strict deterministic lock order: Worker -> Run -> Stage -> Attempt -> DeploymentOperation
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ForbiddenOperationException("Registered worker node not found"));
        if (worker.getStatus() == WorkerStatus.OFFLINE || worker.getAdapterType() != WorkerAdapterType.KUBERNETES_DEPLOY) {
            deny(lockIds.getWorkspaceId(), attemptId.toString(), principal, "invalid_worker_adapter", Map.of());
            throw new ForbiddenOperationException("An active KUBERNETES_DEPLOY worker is required");
        }

        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(lockIds.getPipelineRunId(), lockIds.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found"));
        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(lockIds.getStageRunId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found"));
        StageExecutionAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found"));

        UUID workspaceId = run.getWorkspace().getId();
        Instant now = Instant.now();

        if (!AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(attempt.getAllowedOperation())
                || !AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(stage.getAllowedOperation())) {
            deny(workspaceId, attemptId.toString(), principal, "operation_not_k8s_deploy", Map.of());
            throw new ForbiddenOperationException("Kubernetes cluster credentials are restricted strictly to KUBERNETES_DEPLOY attempts");
        }

        if (attempt.getStatus() != AttemptStatus.RUNNING
                || stage.getStatus() != PipelineStageStatus.RUNNING
                || run.getStatus().isTerminal()
                || attempt.isTerminationConfirmed()
                || attempt.getCancellationRequestedAt() != null
                || stage.getCancellationRequestedAt() != null
                || run.getCancellationRequestedAt() != null
                || attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null || !stage.getLeaseExpiresAt().isAfter(now)
                || !request.leaseToken().equals(attempt.getLeaseToken())
                || !request.leaseToken().equals(stage.getActiveLeaseToken())
                || request.fencingGeneration() != attempt.getFencingGeneration()
                || request.fencingGeneration() != stage.getFencingGeneration()) {
            deny(workspaceId, attemptId.toString(), principal, "invalid_or_expired_lease", Map.of());
            throw new ConflictException("Active unexpired lease and matching fencing generation are required");
        }

        if (attempt.getSecretDeliveryCount() >= k8sProperties.getMaxCredentialDeliveriesPerAttempt()) {
            deny(workspaceId, attemptId.toString(), principal, "delivery_limit_exceeded", Map.of());
            throw new ConflictException("Cluster credential delivery limit reached for this attempt");
        }

        KubernetesDeploymentOperationEntity op = deploymentOperationRepository.findByStageRunIdForUpdate(stage.getId())
                .orElseThrow(() -> new ConflictException("Deployment execution descriptor must be resolved before requesting cluster credentials"));

        KubernetesClusterConnectionEntity cluster = op.getClusterConnection();
        if (!cluster.getWorkspace().getId().equals(workspaceId)) {
            deny(workspaceId, attemptId.toString(), principal, "cross_workspace_cluster", Map.of());
            throw new ForbiddenOperationException("Cluster workspace mismatch");
        }
        if (cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATED) {
            deny(workspaceId, attemptId.toString(), principal, "cluster_not_validated", Map.of(
                    "clusterStatus", cluster.getConnectionStatus().name()
            ));
            throw new ConflictException("Kubernetes cluster must be VALIDATED before credentials can be issued for deployment");
        }

        clusterSecurityValidator.validateClusterEndpoint(
                cluster.getApiServerUrl(),
                cluster.getClusterType(),
                cluster.isAllowLocalLoopback()
        );
        String bearerToken = decryptActiveClusterToken(workspaceId, cluster, attemptId.toString(), principal);

        attempt.setSecretDeliveryCount(attempt.getSecretDeliveryCount() + 1);
        attempt.setLastSecretDeliveryAt(now);
        attemptRepository.save(attempt);

        secretAuditService.recordSecretEvent(
                run.getWorkspace(),
                null,
                SecretAuditService.ACTION_SECRET_ACCESS_GRANTED,
                "KUBERNETES_DEPLOY_CREDENTIAL_DELIVERY",
                attempt.getId().toString(),
                "Delivered encrypted Kubernetes ServiceAccount token for \"" + cluster.getClusterIdentifier()
                        + "\" (" + op.getNamespaceName() + "/" + op.getDeploymentName() + ") to worker \"" + principal.workerId() + "\"",
                Map.of(
                        "workerId", principal.workerId(),
                        "attemptId", attempt.getId().toString(),
                        "deploymentOperationId", op.getId().toString(),
                        "clusterId", cluster.getId().toString(),
                        "namespace", op.getNamespaceName()
                )
        );

        return new WorkerKubernetesDtos.ClusterCredentialsResponse(
                cluster.getId(),
                cluster.getApiServerUrl(),
                cluster.getCaCertificatePem(),
                bearerToken,
                cluster.getServiceAccountName(),
                cluster.isAllowLocalLoopback(),
                now
        );
    }

    /**
     * Resolves the cluster bearer token transiently in memory for defense-in-depth log redaction.
     */
    @Transactional(readOnly = true)
    public List<String> resolveSecretsForDeploymentAttemptRedaction(StageExecutionAttemptEntity attempt) {
        if (attempt == null || attempt.getStageRun() == null
                || !AllowedWorkerOperation.KUBERNETES_DEPLOY.name().equals(attempt.getAllowedOperation())) {
            return List.of();
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            return List.of();
        }
        try {
            KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                    .findByStageRunId(attempt.getStageRun().getId())
                    .orElse(null);
            if (op == null || op.getClusterConnection() == null || op.getClusterConnection().getBearerTokenSecret() == null) {
                return List.of();
            }
            EnvironmentSecretEntity secret = op.getClusterConnection().getBearerTokenSecret();
            if (secret.getCurrentVersionId() == null) {
                return List.of();
            }
            List<String> values = new ArrayList<>(1);
            secretVersionRepository
                    .findByIdAndSecretIdAndWorkspaceId(secret.getCurrentVersionId(), secret.getId(), attempt.getWorkspace().getId())
                    .ifPresent(ver -> {
                        try {
                            values.add(secretManagementService.decryptVersionValue(secret, ver));
                        } catch (RuntimeException ignored) {
                        }
                    });
            return values;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private void verifyBaseWorkerAccess(
            String pathWorkerId,
            String resourceId,
            WorkerPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!workerProperties.isEnabled() || !k8sProperties.isEnabled()) {
            deny(null, resourceId, principal, "kubernetes_disabled", Map.of());
            throw new SecretManagementUnavailableException("Kubernetes deployment execution is disabled");
        }
        if (!k8sProperties.permitsDeployWorker(principal.workerId())) {
            deny(null, resourceId, principal, "worker_not_allowlisted", Map.of());
            throw new ForbiddenOperationException("Worker is not allowlisted for Kubernetes deployment execution");
        }
        if (!secretProperties.isWorkerDeliveryEnabled()) {
            deny(null, resourceId, principal, "worker_secret_delivery_disabled", Map.of());
            throw new SecretManagementUnavailableException("Worker secret delivery is disabled");
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            deny(null, resourceId, principal, "key_provider_unavailable", Map.of());
            throw new SecretManagementUnavailableException(keyProvider.getStatusDiagnostic());
        }
        if (!isSecureOrAllowedLocalTransport(httpRequest)) {
            deny(null, resourceId, principal, "insecure_transport_rejected", Map.of());
            throw new ForbiddenOperationException("HTTPS transport is required for Kubernetes cluster credential delivery");
        }
    }

    private String decryptActiveClusterToken(
            UUID workspaceId,
            KubernetesClusterConnectionEntity cluster,
            String resourceId,
            WorkerPrincipal principal
    ) {
        EnvironmentSecretEntity secret = cluster.getBearerTokenSecret();
        if (secret == null || !secret.getWorkspace().getId().equals(workspaceId)) {
            deny(workspaceId, resourceId, principal, "cross_workspace_cluster_secret", Map.of());
            throw new ForbiddenOperationException("Cluster bearer token secret workspace scope mismatch");
        }
        if (secret.getStatus() != SecretLifecycleStatus.ACTIVE || secret.getCurrentVersionId() == null) {
            deny(workspaceId, resourceId, principal, "cluster_secret_inactive", Map.of("secretId", secret.getId().toString()));
            throw new ConflictException("Cluster bearer token secret \"" + secret.getSecretName() + "\" is not ACTIVE");
        }
        EnvironmentSecretVersionEntity version = secretVersionRepository
                .findByIdAndSecretIdAndWorkspaceId(secret.getCurrentVersionId(), secret.getId(), workspaceId)
                .orElseThrow(() -> new ConflictException("Active cluster bearer token secret version is missing"));
        if (version.getStatus() != SecretVersionStatus.ACTIVE) {
            deny(workspaceId, resourceId, principal, "cluster_secret_version_revoked", Map.of("secretId", secret.getId().toString()));
            throw new ConflictException("Active cluster bearer token secret version has been revoked");
        }
        return secretManagementService.decryptVersionValue(secret, version);
    }

    private boolean isSecureOrAllowedLocalTransport(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        if (!k8sProperties.isAllowInsecureLocalCredentialDelivery()
                && !secretProperties.isAllowInsecureLocalDelivery()) {
            return false;
        }
        return isLoopbackAddress(request.getRemoteAddr()) && isLoopbackHost(request.getServerName());
    }

    private static boolean isLoopbackAddress(String addr) {
        if (!StringUtils.hasText(addr)) {
            return false;
        }
        try {
            return InetAddress.getByName(addr.trim()).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String lower = host.trim().toLowerCase();
        return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower);
    }

    private void deny(UUID workspaceId, String resourceId, WorkerPrincipal principal, String reason, Map<String, Object> extra) {
        Map<String, Object> meta = new LinkedHashMap<>(extra);
        meta.put("workerId", principal.workerId());
        secretAuditService.recordDeniedAccess(
                workspaceId,
                null,
                "KUBERNETES_CREDENTIAL_DELIVERY",
                resourceId,
                "worker:" + principal.workerId(),
                reason,
                meta
        );
    }
}
