package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.dto.WorkerOciLeaseRequest;
import com.cloudforge.backend.oci.dto.WorkerRegistryCredentialResponse;
import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves encrypted OCI registry credentials strictly through CloudForge's Phase 3.4
 * AES-256-GCM envelope-encrypted secret subsystem for authenticated, lease-bound,
 * fenced {@code OCI_IMAGE_PUBLISH} attempts only.
 *
 * <p>Registry credentials are NEVER delivered to {@code OCI_IMAGE_BUILD} (Dockerfile execution)
 * or {@code GITHUB_SOURCE_ACQUIRE} stages.</p>
 */
@Service
public class RegistryCredentialResolver {

    private final WorkerProperties workerProperties;
    private final OciBuildProperties ociProperties;
    private final SecretManagementProperties secretProperties;
    private final SecretKeyProvider keyProvider;
    private final SecretManagementService secretManagementService;
    private final EnvironmentSecretVersionRepository secretVersionRepository;
    private final SecretAuditService secretAuditService;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final BuildRequestValidator buildRequestValidator;
    private final ObjectMapper objectMapper;

    public RegistryCredentialResolver(
            WorkerProperties workerProperties,
            OciBuildProperties ociProperties,
            SecretManagementProperties secretProperties,
            SecretKeyProvider keyProvider,
            SecretManagementService secretManagementService,
            EnvironmentSecretVersionRepository secretVersionRepository,
            SecretAuditService secretAuditService,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            BuildRequestValidator buildRequestValidator,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.ociProperties = ociProperties;
        this.secretProperties = secretProperties;
        this.keyProvider = keyProvider;
        this.secretManagementService = secretManagementService;
        this.secretVersionRepository = secretVersionRepository;
        this.secretAuditService = secretAuditService;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.buildRequestValidator = buildRequestValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WorkerRegistryCredentialResponse resolveForPublishAttempt(
            String pathWorkerId,
            UUID attemptId,
            WorkerOciLeaseRequest request,
            WorkerPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);

        if (!workerProperties.isEnabled() || !ociProperties.isEnabled()) {
            deny(null, attemptId, principal, "oci_disabled", Map.of());
            throw new SecretManagementUnavailableException("OCI build/publish execution is disabled");
        }
        if (!ociProperties.permitsBuildWorker(principal.workerId())) {
            deny(null, attemptId, principal, "worker_not_allowlisted", Map.of());
            throw new ForbiddenOperationException("Worker is not allowlisted for OCI registry credentials");
        }
        if (!isSecureOrAllowedLocalTransport(httpRequest)) {
            deny(null, attemptId, principal, "insecure_transport_rejected", Map.of());
            throw new ForbiddenOperationException("HTTPS transport is required for OCI registry credential delivery");
        }

        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .filter(ids -> principal.workerId().equals(ids.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Execution attempt unavailable"));

        // Lock order: Worker -> Run -> Stage -> Attempt
        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .orElseThrow(() -> new ForbiddenOperationException("Worker node not found"));
        if (worker.getStatus() == WorkerStatus.OFFLINE || worker.getAdapterType() != WorkerAdapterType.OCI_BUILDKIT) {
            deny(lockIds.getWorkspaceId(), attemptId, principal, "invalid_worker_adapter", Map.of());
            throw new ForbiddenOperationException("An active OCI_BUILDKIT worker is required");
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

        // Strictly restrict credential delivery to OCI_IMAGE_PUBLISH (never Dockerfile build stage!)
        if (!AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(attempt.getAllowedOperation())
                || !AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(stage.getAllowedOperation())) {
            deny(workspaceId, attemptId, principal, "operation_not_oci_publish", Map.of(
                    "allowedOperation", String.valueOf(attempt.getAllowedOperation())
            ));
            throw new ForbiddenOperationException("Registry credentials are strictly restricted to OCI_IMAGE_PUBLISH stages");
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
            deny(workspaceId, attemptId, principal, "invalid_or_expired_lease", Map.of());
            throw new ConflictException("Active unexpired lease and matching fencing generation are required");
        }

        if (attempt.getSecretDeliveryCount() >= ociProperties.getMaxCredentialDeliveriesPerAttempt()) {
            deny(workspaceId, attemptId, principal, "delivery_limit_exceeded", Map.of());
            throw new ConflictException("Registry credential delivery limit reached for this attempt");
        }

        Map<String, String> meta = readStringMap(stage.getMetadataJson());
        BuildRequestValidator.ValidatedOciPublishConfig publishConfig =
                buildRequestValidator.validatePublishStageConfig(workspaceId, stage.getStageKey(), meta);
        OciRegistryConnectionEntity registry = publishConfig.registryConnection();
        if (registry.getStatus() != OciRegistryStatus.ACTIVE) {
            deny(workspaceId, attemptId, principal, "registry_disabled", Map.of("registryId", registry.getId().toString()));
            throw new ConflictException("OCI registry connection is disabled");
        }

        // Anonymous local dev registry (explicitly enabled, no secrets bound)
        if (registry.getPasswordSecret() == null) {
            if (!registry.isAllowInsecureLocal() || !ociProperties.isAllowLocalDevRegistry()) {
                deny(workspaceId, attemptId, principal, "credentials_missing_for_remote_registry",
                        Map.of("registryId", registry.getId().toString()));
                throw new ConflictException("Encrypted registry credentials are required for remote OCI registries");
            }
            attempt.setSecretDeliveryCount(attempt.getSecretDeliveryCount() + 1);
            attempt.setLastSecretDeliveryAt(now);
            attemptRepository.save(attempt);
            return new WorkerRegistryCredentialResponse(
                    attempt.getId(),
                    registry.getId(),
                    registry.getRegistryHost(),
                    registry.getScheme(),
                    null,
                    null,
                    true,
                    now
            );
        }

        if (!secretProperties.isWorkerDeliveryEnabled()) {
            deny(workspaceId, attemptId, principal, "worker_secret_delivery_disabled", Map.of());
            throw new SecretManagementUnavailableException("Worker secret delivery is disabled");
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            deny(workspaceId, attemptId, principal, "key_provider_unavailable", Map.of());
            throw new SecretManagementUnavailableException(keyProvider.getStatusDiagnostic());
        }

        String password = decryptActiveSecret(workspaceId, registry.getPasswordSecret(), attemptId, principal);
        String username = registry.getUsernameSecret() != null
                ? decryptActiveSecret(workspaceId, registry.getUsernameSecret(), attemptId, principal)
                : "oauth2";

        attempt.setSecretDeliveryCount(attempt.getSecretDeliveryCount() + 1);
        attempt.setLastSecretDeliveryAt(now);
        attemptRepository.save(attempt);

        secretAuditService.recordSecretEvent(
                run.getWorkspace(),
                null,
                SecretAuditService.ACTION_SECRET_ACCESS_GRANTED,
                "OCI_REGISTRY_CREDENTIAL_DELIVERY",
                attempt.getId().toString(),
                "Delivered encrypted OCI registry credentials for \"" + registry.getRegistryHost()
                        + "\" to worker \"" + principal.workerId() + "\"",
                Map.of(
                        "workerId", principal.workerId(),
                        "attemptId", attempt.getId().toString(),
                        "pipelineRunId", run.getId().toString(),
                        "registryConnectionId", registry.getId().toString(),
                        "registryHost", registry.getRegistryHost()
                )
        );

        return new WorkerRegistryCredentialResponse(
                attempt.getId(),
                registry.getId(),
                registry.getRegistryHost(),
                registry.getScheme(),
                username,
                password,
                false,
                now
        );
    }

    /**
     * Resolves registry credential plaintext strictly in memory for defense-in-depth
     * control-plane log redaction on {@code OCI_IMAGE_PUBLISH} attempts.
     */
    @Transactional(readOnly = true)
    public List<String> resolveSecretsForPublishAttemptRedaction(StageExecutionAttemptEntity attempt) {
        if (attempt == null || attempt.getStageRun() == null
                || !AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(attempt.getAllowedOperation())) {
            return List.of();
        }
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            return List.of();
        }
        try {
            Map<String, String> meta = readStringMap(attempt.getStageRun().getMetadataJson());
            BuildRequestValidator.ValidatedOciPublishConfig cfg = buildRequestValidator.validatePublishStageConfig(
                    attempt.getWorkspace().getId(), attempt.getStageRun().getStageKey(), meta
            );
            OciRegistryConnectionEntity reg = cfg.registryConnection();
            if (reg.getPasswordSecret() == null) {
                return List.of();
            }
            java.util.ArrayList<String> values = new java.util.ArrayList<>(2);
            tryDecryptQuietly(attempt.getWorkspace().getId(), reg.getPasswordSecret(), values);
            if (reg.getUsernameSecret() != null) {
                tryDecryptQuietly(attempt.getWorkspace().getId(), reg.getUsernameSecret(), values);
            }
            return values;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private void tryDecryptQuietly(UUID workspaceId, EnvironmentSecretEntity secret, List<String> target) {
        if (secret == null || secret.getCurrentVersionId() == null) {
            return;
        }
        secretVersionRepository.findByIdAndSecretIdAndWorkspaceId(secret.getCurrentVersionId(), secret.getId(), workspaceId)
                .ifPresent(ver -> {
                    try {
                        target.add(secretManagementService.decryptVersionValue(secret, ver));
                    } catch (RuntimeException ignored) {
                    }
                });
    }

    private String decryptActiveSecret(
            UUID workspaceId,
            EnvironmentSecretEntity secret,
            UUID attemptId,
            WorkerPrincipal principal
    ) {
        if (!secret.getWorkspace().getId().equals(workspaceId)) {
            deny(workspaceId, attemptId, principal, "cross_workspace_registry_secret", Map.of());
            throw new ForbiddenOperationException("Registry secret workspace scope mismatch");
        }
        if (secret.getStatus() != SecretLifecycleStatus.ACTIVE || secret.getCurrentVersionId() == null) {
            deny(workspaceId, attemptId, principal, "registry_secret_inactive", Map.of("secretId", secret.getId().toString()));
            throw new ConflictException("Referenced registry credential secret \"" + secret.getSecretName() + "\" is not ACTIVE");
        }
        EnvironmentSecretVersionEntity version = secretVersionRepository
                .findByIdAndSecretIdAndWorkspaceId(secret.getCurrentVersionId(), secret.getId(), workspaceId)
                .orElseThrow(() -> new ConflictException("Active registry credential secret version is missing"));
        if (version.getStatus() != SecretVersionStatus.ACTIVE) {
            deny(workspaceId, attemptId, principal, "registry_secret_version_revoked", Map.of("secretId", secret.getId().toString()));
            throw new ConflictException("Active registry credential secret version has been revoked");
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
        if (!ociProperties.isAllowInsecureLocalCredentialDelivery()
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

    private void deny(UUID workspaceId, UUID attemptId, WorkerPrincipal principal, String reason, Map<String, Object> extra) {
        Map<String, Object> meta = new LinkedHashMap<>(extra);
        meta.put("workerId", principal.workerId());
        meta.put("attemptId", attemptId.toString());
        secretAuditService.recordDeniedAccess(
                workspaceId,
                null,
                "OCI_REGISTRY_CREDENTIAL_DELIVERY",
                attemptId.toString(),
                "worker:" + principal.workerId(),
                reason,
                meta
        );
    }
}
