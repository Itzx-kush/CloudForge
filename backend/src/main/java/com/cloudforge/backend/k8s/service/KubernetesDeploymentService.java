package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.config.KubernetesDeploymentProperties;
import com.cloudforge.backend.k8s.dto.CancelDeploymentRequest;
import com.cloudforge.backend.k8s.dto.CreateDeploymentRequest;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentConfigDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentEventDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentOperationDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentStatusDto;
import com.cloudforge.backend.k8s.dto.KubernetesProbeConfig;
import com.cloudforge.backend.k8s.dto.KubernetesSubsystemStatusDto;
import com.cloudforge.backend.k8s.entity.KubernetesClusterConnectionEntity;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentConfigEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingEntity;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import com.cloudforge.backend.k8s.repository.KubernetesClusterConnectionRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentConfigRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentEventRepository;
import com.cloudforge.backend.k8s.repository.KubernetesDeploymentOperationRepository;
import com.cloudforge.backend.k8s.repository.KubernetesNamespaceBindingRepository;
import com.cloudforge.backend.oci.entity.OciArtifactRetentionPolicy;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunEventEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.orchestration.entity.PipelineTriggerType;
import com.cloudforge.backend.orchestration.repository.PipelineRunEventRepository;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * User-facing Kubernetes deployment orchestration and query service.
 *
 * <p>Validates workspace RBAC, explicit production approval policy, verified immutable OCI
 * digests, approved validated clusters, and namespace bindings, then queues a single-stage
 * {@code DEPLOYMENT} ({@code KUBERNETES_DEPLOY}) run on CloudForge's established Phase 3.2/3.3
 * orchestration &amp; worker scheduling engine — never introducing a second scheduler or calling
 * Kubernetes APIs inside the Spring Boot web server.</p>
 */
@Service
public class KubernetesDeploymentService {

    private final WorkspaceAuthorizationService authorizationService;
    private final KubernetesDeploymentProperties k8sProperties;
    private final KubernetesClusterConnectionRepository clusterRepository;
    private final KubernetesNamespaceBindingRepository namespaceBindingRepository;
    private final KubernetesDeploymentConfigRepository deploymentConfigRepository;
    private final KubernetesDeploymentOperationRepository deploymentOperationRepository;
    private final KubernetesDeploymentEventRepository deploymentEventRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final OciImageArtifactRepository artifactRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final PipelineRunEventRepository runEventRepository;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineOrchestrationService orchestrationService;
    private final KubernetesDeploymentConfigValidator configValidator;
    private final KubernetesManifestGenerator manifestGenerator;
    private final ClusterSecurityValidator clusterSecurityValidator;
    private final KubernetesDeploymentLifecycleService lifecycleService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public KubernetesDeploymentService(
            WorkspaceAuthorizationService authorizationService,
            KubernetesDeploymentProperties k8sProperties,
            KubernetesClusterConnectionRepository clusterRepository,
            KubernetesNamespaceBindingRepository namespaceBindingRepository,
            KubernetesDeploymentConfigRepository deploymentConfigRepository,
            KubernetesDeploymentOperationRepository deploymentOperationRepository,
            KubernetesDeploymentEventRepository deploymentEventRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            OciImageArtifactRepository artifactRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            PipelineRunEventRepository runEventRepository,
            WorkerNodeRepository workerNodeRepository,
            PipelineOrchestrationService orchestrationService,
            KubernetesDeploymentConfigValidator configValidator,
            KubernetesManifestGenerator manifestGenerator,
            ClusterSecurityValidator clusterSecurityValidator,
            KubernetesDeploymentLifecycleService lifecycleService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.k8sProperties = k8sProperties;
        this.clusterRepository = clusterRepository;
        this.namespaceBindingRepository = namespaceBindingRepository;
        this.deploymentConfigRepository = deploymentConfigRepository;
        this.deploymentOperationRepository = deploymentOperationRepository;
        this.deploymentEventRepository = deploymentEventRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.artifactRepository = artifactRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.runEventRepository = runEventRepository;
        this.workerNodeRepository = workerNodeRepository;
        this.orchestrationService = orchestrationService;
        this.configValidator = configValidator;
        this.manifestGenerator = manifestGenerator;
        this.clusterSecurityValidator = clusterSecurityValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public KubernetesSubsystemStatusDto getSubsystemStatus(UUID workspaceId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);

        long configuredClusters = clusterRepository.countByWorkspaceId(workspaceId);
        long validatedClusters = clusterRepository.countByWorkspaceIdAndConnectionStatus(
                workspaceId, KubernetesClusterStatus.VALIDATED
        );
        long activeNamespaces = namespaceBindingRepository.countByWorkspaceIdAndStatus(
                workspaceId, KubernetesNamespaceBindingStatus.ACTIVE
        );
        long totalDeployments = deploymentOperationRepository.countByWorkspaceId(workspaceId);
        long runningDeployments = deploymentOperationRepository.countByWorkspaceIdAndStatusIn(
                workspaceId, KubernetesDeploymentLifecycleService.ACTIVE_DEPLOYMENT_STATUSES
        );
        long succeededDeployments = deploymentOperationRepository.countByWorkspaceIdAndStatusIn(
                workspaceId, List.of(KubernetesDeploymentStatus.SUCCEEDED)
        );
        long failedDeployments = deploymentOperationRepository.countByWorkspaceIdAndStatusIn(
                workspaceId, List.of(KubernetesDeploymentStatus.FAILED, KubernetesDeploymentStatus.RECONCILIATION_REQUIRED)
        );
        long activeDeployWorkers = workerNodeRepository.findAllByOrderByWorkerIdAsc().stream()
                .filter(w -> w.getAdapterType() == WorkerAdapterType.KUBERNETES_DEPLOY
                        && (w.getStatus() == WorkerStatus.AVAILABLE || w.getStatus() == WorkerStatus.BUSY))
                .count();

        return new KubernetesSubsystemStatusDto(
                k8sProperties.isEnabled(),
                k8sProperties.isAllowLocalDevClusters(),
                k8sProperties.isAllowProductionDeployments(),
                k8sProperties.parsedAllowedApiHosts().stream().sorted().toList(),
                k8sProperties.getMaxReplicas(),
                k8sProperties.getMaxDeploymentTimeoutSeconds(),
                configuredClusters,
                validatedClusters,
                activeNamespaces,
                totalDeployments,
                runningDeployments,
                succeededDeployments,
                failedDeployments,
                activeDeployWorkers
        );
    }

    @Transactional(readOnly = true)
    public List<KubernetesDeploymentConfigDto> listDeploymentConfigs(UUID workspaceId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        return deploymentConfigRepository.findAllByWorkspaceIdWithDetails(workspaceId).stream()
                .map(this::toConfigDto)
                .toList();
    }

    @Transactional
    public CreateDeploymentResult createDeployment(
            UUID workspaceId,
            CreateDeploymentRequest request,
            String headerIdempotencyKey,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, actor.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        String rawIdemKey = StringUtils.hasText(headerIdempotencyKey)
                ? headerIdempotencyKey.trim()
                : (StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : null);
        if (rawIdemKey != null && (rawIdemKey.length() < 4 || rawIdemKey.length() > 128)) {
            throw new BadRequestException("Idempotency-Key must be between 4 and 128 characters");
        }

        // 1. Validate project & environment ownership
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        ProjectEnvironmentEntity environment = environmentRepository
                .findByIdAndProjectId(request.environmentId(), project.getId())
                .orElseThrow(() -> new BadRequestException("Target environment does not belong to the selected project"));

        // 2. Lock and validate cluster connection belongs to this workspace and is VALIDATED
        KubernetesClusterConnectionEntity cluster = clusterRepository
                .findByIdAndWorkspaceIdForUpdate(request.clusterConnectionId(), workspaceId)
                .orElseThrow(() -> new ForbiddenOperationException("Referenced Kubernetes cluster does not belong to this workspace"));
        if (cluster.getConnectionStatus() != KubernetesClusterStatus.VALIDATED) {
            throw new ConflictException(
                    "Cluster \"" + cluster.getName() + "\" is not eligible for deployment (status: "
                            + cluster.getConnectionStatus() + "); validate the cluster first"
            );
        }
        clusterSecurityValidator.validateClusterEndpoint(
                cluster.getApiServerUrl(),
                cluster.getClusterType(),
                cluster.isAllowLocalLoopback()
        );

        // 3. Enforce environment & production deployment authorization policy
        boolean isProductionTarget = environment.getEnvironmentType() == EnvironmentType.PRODUCTION
                || cluster.getEnvironmentCategory() == EnvironmentType.PRODUCTION;
        if (isProductionTarget) {
            if (membership.getRole() != WorkspaceRole.OWNER) {
                throw new ForbiddenOperationException(
                        "Only workspace OWNER users may request deployments targeting PRODUCTION environments or clusters"
                );
            }
            if (!k8sProperties.isAllowProductionDeployments()) {
                throw new ForbiddenOperationException(
                        "Production Kubernetes deployments are disabled by platform policy (CLOUDFORGE_K8S_ALLOW_PRODUCTION_DEPLOYMENTS=false)"
                );
            }
            if (cluster.isProductionApprovalRequired()) {
                if (!Boolean.TRUE.equals(request.productionApprovalConfirmed())
                        || !StringUtils.hasText(request.productionApprovalJustification())
                        || request.productionApprovalJustification().trim().length() < 8) {
                    throw new BadRequestException(
                            "Production deployments require explicit productionApprovalConfirmed=true and a justification (>= 8 chars)",
                            Map.of("productionApprovalJustification", "Provide an explicit approval justification for PRODUCTION rollout")
                    );
                }
            }
        }

        // 4. Resolve & verify operator-approved namespace binding
        KubernetesNamespaceBindingEntity namespaceBinding = resolveNamespaceBinding(
                workspaceId, project.getId(), environment.getId(), cluster.getId(), request
        );
        if (namespaceBinding.getStatus() != KubernetesNamespaceBindingStatus.ACTIVE) {
            throw new ConflictException("Approved namespace binding \"" + namespaceBinding.getNamespaceName() + "\" is disabled");
        }
        if (StringUtils.hasText(request.namespace())
                && !namespaceBinding.getNamespaceName().equalsIgnoreCase(request.namespace().trim())) {
            throw new ForbiddenOperationException(
                    "Requested namespace \"" + request.namespace() + "\" does not match the approved binding \""
                            + namespaceBinding.getNamespaceName() + "\""
            );
        }

        // 5. Verify immutable OCI image artifact ownership, verification status, and digest
        OciImageArtifactEntity artifact = artifactRepository
                .findByIdAndWorkspaceIdWithDetails(request.ociArtifactId(), workspaceId)
                .orElseThrow(() -> new ForbiddenOperationException("Referenced OCI image artifact does not belong to this workspace"));
        if (!artifact.getProject().getId().equals(project.getId())) {
            throw new ForbiddenOperationException("Referenced OCI image artifact belongs to a different project");
        }
        if (artifact.getVerificationStatus() != OciArtifactVerificationStatus.VERIFIED) {
            throw new ConflictException(
                    "Only VERIFIED OCI image artifacts may be deployed (current status: " + artifact.getVerificationStatus() + ")"
            );
        }
        if (!OciBuildLifecycleService.isValidDigest(artifact.getManifestDigest())
                || artifact.getImmutableReference() == null
                || !artifact.getImmutableReference().endsWith("@" + artifact.getManifestDigest())) {
            throw new ConflictException(
                    "Artifact lacks a valid immutable SHA-256 image digest reference; mutable tags cannot be deployed"
            );
        }

        // 6. Validate declarative workload configuration
        String defaultName = project.getSlug() + "-" + environment.getEnvironmentType().name().toLowerCase().substring(0, 3);
        KubernetesDeploymentConfigValidator.ValidatedWorkloadConfig validatedCfg =
                configValidator.validate(request, defaultName);

        String fingerprint = computeRequestFingerprint(
                workspaceId, project.getId(), environment.getId(), cluster.getId(),
                namespaceBinding.getId(), artifact.getId(), validatedCfg.configChecksumSha256()
        );

        // 7. Handle workspace-scoped idempotent replay
        if (rawIdemKey != null) {
            Optional<KubernetesDeploymentOperationEntity> existingOpt =
                    deploymentOperationRepository.findByWorkspaceIdAndIdempotencyKey(workspaceId, rawIdemKey);
            if (existingOpt.isPresent()) {
                KubernetesDeploymentOperationEntity existing = existingOpt.get();
                if (!fingerprint.equals(existing.getRequestFingerprintSha256())) {
                    throw new ConflictException(
                            "Idempotency-Key \"" + rawIdemKey + "\" was already used with a different deployment configuration"
                    );
                }
                KubernetesDeploymentOperationEntity hydrated = deploymentOperationRepository
                        .findByIdAndWorkspaceIdWithDetails(existing.getId(), workspaceId)
                        .orElse(existing);
                return new CreateDeploymentResult(lifecycleService.toOperationDto(hydrated), true);
            }
        }

        // 8. Guard against overlapping concurrent deployments targeting the same cluster/namespace/workload
        if (deploymentOperationRepository.existsActiveOperationForTarget(
                cluster.getId(),
                namespaceBinding.getNamespaceName(),
                validatedCfg.deploymentName(),
                KubernetesDeploymentLifecycleService.ACTIVE_DEPLOYMENT_STATUSES
        )) {
            throw new ConflictException(
                    "Another deployment operation for \"" + validatedCfg.deploymentName()
                            + "\" in namespace \"" + namespaceBinding.getNamespaceName() + "\" is currently in progress"
            );
        }

        // 9. Upsert declarative deployment configuration record
        KubernetesDeploymentConfigEntity cfgEntity = deploymentConfigRepository
                .findByWorkspaceIdAndProjectIdAndEnvironmentIdAndClusterConnectionIdAndDeploymentName(
                        workspaceId, project.getId(), environment.getId(), cluster.getId(), validatedCfg.deploymentName()
                )
                .orElseGet(() -> {
                    KubernetesDeploymentConfigEntity created = new KubernetesDeploymentConfigEntity();
                    created.setWorkspace(workspace);
                    created.setProject(project);
                    created.setEnvironment(environment);
                    created.setClusterConnection(cluster);
                    created.setNamespaceBinding(namespaceBinding);
                    created.setCreatedBy(actor);
                    return created;
                });
        cfgEntity.setNamespaceName(namespaceBinding.getNamespaceName());
        cfgEntity.setDeploymentName(validatedCfg.deploymentName());
        cfgEntity.setServiceName(validatedCfg.serviceName());
        cfgEntity.setReplicaCount(validatedCfg.replicaCount());
        cfgEntity.setContainerPort(validatedCfg.containerPort());
        cfgEntity.setServicePort(validatedCfg.servicePort());
        cfgEntity.setCpuRequest(validatedCfg.cpuRequest());
        cfgEntity.setCpuLimit(validatedCfg.cpuLimit());
        cfgEntity.setMemoryRequest(validatedCfg.memoryRequest());
        cfgEntity.setMemoryLimit(validatedCfg.memoryLimit());
        cfgEntity.setReadinessProbeJson(validatedCfg.readinessProbeJson());
        cfgEntity.setLivenessProbeJson(validatedCfg.livenessProbeJson());
        cfgEntity.setStartupProbeJson(validatedCfg.startupProbeJson());
        cfgEntity.setRollingMaxSurge(validatedCfg.rollingMaxSurge());
        cfgEntity.setRollingMaxUnavailable(validatedCfg.rollingMaxUnavailable());
        cfgEntity.setProgressDeadlineSeconds(validatedCfg.progressDeadlineSeconds());
        cfgEntity.setDeploymentTimeoutSeconds(validatedCfg.deploymentTimeoutSeconds());
        cfgEntity.setReadOnlyRootFilesystem(validatedCfg.readOnlyRootFilesystem());
        cfgEntity.setRunAsNonRoot(validatedCfg.runAsNonRoot());
        cfgEntity.setRunAsUser(validatedCfg.runAsUser());
        cfgEntity.setConfigChecksumSha256(validatedCfg.configChecksumSha256());
        cfgEntity.setUpdatedBy(actor);
        KubernetesDeploymentConfigEntity savedCfg = deploymentConfigRepository.saveAndFlush(cfgEntity);

        // 10. Generate deterministic manifest checksum (Deployment + ClusterIP Service)
        KubernetesManifestGenerator.GeneratedManifestBundle manifestBundle = manifestGenerator.generate(
                new KubernetesManifestGenerator.ManifestInput(
                        workspaceId,
                        project.getId(),
                        environment.getId(),
                        environment.getEnvironmentType().name(),
                        savedCfg.getId(),
                        artifact.getId(),
                        namespaceBinding.getNamespaceName(),
                        namespaceBinding.getWorkloadServiceAccountName(),
                        namespaceBinding.getImagePullSecretName(),
                        validatedCfg.deploymentName(),
                        validatedCfg.serviceName(),
                        artifact.getImmutableReference(),
                        artifact.getManifestDigest(),
                        artifact.getCommitSha(),
                        validatedCfg.replicaCount(),
                        validatedCfg.containerPort(),
                        validatedCfg.servicePort(),
                        validatedCfg.cpuRequest(),
                        validatedCfg.cpuLimit(),
                        validatedCfg.memoryRequest(),
                        validatedCfg.memoryLimit(),
                        validatedCfg.readinessProbe(),
                        validatedCfg.livenessProbe(),
                        validatedCfg.startupProbe(),
                        validatedCfg.rollingMaxSurge(),
                        validatedCfg.rollingMaxUnavailable(),
                        validatedCfg.progressDeadlineSeconds(),
                        validatedCfg.readOnlyRootFilesystem(),
                        validatedCfg.runAsNonRoot(),
                        validatedCfg.runAsUser()
                )
        );

        // Mark referenced OCI artifact as retention-protected
        if (artifact.getRetentionPolicy() != OciArtifactRetentionPolicy.REFERENCED_PROTECTED) {
            artifact.setRetentionPolicy(OciArtifactRetentionPolicy.REFERENCED_PROTECTED);
            artifactRepository.save(artifact);
        }

        // 11. Queue single-stage DEPLOYMENT run on CloudForge's unified orchestration engine
        Instant now = Instant.now();
        PipelineRunEntity run = new PipelineRunEntity();
        run.setWorkspace(workspace);
        run.setProject(project);
        run.setLinkedRepository(artifact.getLinkedRepository());
        run.setTargetEnvironment(environment);
        run.setRunNumber(1);
        run.setDefinitionName("Kubernetes Deploy · " + validatedCfg.deploymentName());
        run.setDefinitionVersion(1);
        run.setDefinitionSnapshotJson(writeJson(Map.of(
                "type", "DIRECT_KUBERNETES_DEPLOYMENT",
                "clusterId", cluster.getId().toString(),
                "namespace", namespaceBinding.getNamespaceName(),
                "deploymentName", validatedCfg.deploymentName(),
                "ociArtifactId", artifact.getId().toString(),
                "manifestDigest", artifact.getManifestDigest()
        )));
        run.setTriggerType(PipelineTriggerType.MANUAL);
        run.setTargetBranch(project.getDefaultBranch());
        run.setCommitSha(artifact.getCommitSha());
        run.setInitialStatusReason("Queued — Awaiting KUBERNETES_DEPLOY worker");
        run.setRequestedBy(actor);
        run.setQueuedAt(now);
        PipelineRunEntity savedRun = runRepository.saveAndFlush(run);

        PipelineStageRunEntity stage = new PipelineStageRunEntity();
        stage.setWorkspace(workspace);
        stage.setPipelineRun(savedRun);
        stage.setStageKey("deploy");
        stage.setDisplayName("Deploy " + validatedCfg.deploymentName() + " (" + namespaceBinding.getNamespaceName() + ")");
        stage.setStageType(PipelineStageType.DEPLOYMENT);
        stage.setExecutionOrder(0);
        stage.setDependsOnJson("[]");
        stage.setMetadataJson(writeJson(Map.of(
                AllowedWorkerOperation.OPERATION_METADATA_KEY, AllowedWorkerOperation.KUBERNETES_DEPLOY.name(),
                KubernetesDeploymentLifecycleService.META_DEPLOYMENT_CONFIG_ID, savedCfg.getId().toString(),
                KubernetesDeploymentLifecycleService.META_OCI_ARTIFACT_ID, artifact.getId().toString()
        )));
        stage.setAllowedOperation(AllowedWorkerOperation.KUBERNETES_DEPLOY.name());
        stage.setSecretBindingsJson("[]");
        stage.setHasSecretBindings(false);
        stage.setTimeoutSeconds(validatedCfg.deploymentTimeoutSeconds());
        stage.setMaxRetries(0); // Never blindly replay external Kubernetes side effects
        stage.setAllowFailure(false);
        stage.setInitialStatusReason("Ready for KUBERNETES_DEPLOY worker");
        stage.transitionStatus(PipelineStageStatus.READY, now, "Ready for KUBERNETES_DEPLOY worker assignment");
        PipelineStageRunEntity savedStage = stageRunRepository.saveAndFlush(stage);

        PipelineRunEventEntity runEvent = new PipelineRunEventEntity();
        runEvent.setWorkspace(workspace);
        runEvent.setPipelineRun(savedRun);
        runEvent.setStageRun(savedStage);
        runEvent.setStageKey("deploy");
        runEvent.setEventType("RUN_QUEUED");
        runEvent.setFromStatus(null);
        runEvent.setToStatus(PipelineRunStatus.QUEUED.name());
        runEvent.setActor(actor);
        runEvent.setActorUsername(actor.getUsername());
        runEvent.setMessage("Queued Kubernetes deployment for " + validatedCfg.deploymentName()
                + " (" + artifact.getManifestDigest() + ")");
        runEvent.setMetadataJson(writeJson(Map.of("clusterId", cluster.getId().toString(), "namespace", namespaceBinding.getNamespaceName())));
        runEvent.setOccurredAt(now);
        runEventRepository.save(runEvent);

        // 12. Persist immutable deployment operation record
        KubernetesDeploymentOperationEntity op = new KubernetesDeploymentOperationEntity();
        op.setWorkspace(workspace);
        op.setProject(project);
        op.setEnvironment(environment);
        op.setClusterConnection(cluster);
        op.setNamespaceBinding(namespaceBinding);
        op.setDeploymentConfig(savedCfg);
        op.setOciArtifact(artifact);
        op.setPipelineRun(savedRun);
        op.setStageRun(savedStage);
        op.setIdempotencyKey(rawIdemKey);
        op.setRequestFingerprintSha256(fingerprint);
        op.setNamespaceName(namespaceBinding.getNamespaceName());
        op.setDeploymentName(validatedCfg.deploymentName());
        op.setServiceName(validatedCfg.serviceName());
        op.setImmutableImageReference(artifact.getImmutableReference());
        op.setManifestDigest(artifact.getManifestDigest());
        op.setSourceCommitSha(artifact.getCommitSha());
        op.setManifestChecksumSha256(manifestBundle.manifestChecksumSha256());
        op.setConfigSnapshotJson(validatedCfg.configSnapshotJson());
        op.setRequestedReplicas(validatedCfg.replicaCount());
        op.setRequestedBy(actor);
        if (isProductionTarget) {
            op.setProductionApprovalToken(request.productionApprovalJustification().trim());
            op.setProductionApprovedBy(actor);
            op.setProductionApprovedAt(now);
        }
        KubernetesDeploymentOperationEntity savedOp = deploymentOperationRepository.saveAndFlush(op);

        lifecycleService.recordDeploymentEvent(
                savedOp,
                "DEPLOYMENT_REQUESTED",
                null,
                KubernetesDeploymentStatus.PENDING.name(),
                "user:" + actor.getUsername(),
                "Queued deployment of " + artifact.getImmutableReference() + " to "
                        + cluster.getName() + "/" + namespaceBinding.getNamespaceName(),
                Map.of(
                        "manifestChecksumSha256", manifestBundle.manifestChecksumSha256(),
                        "requestedReplicas", validatedCfg.replicaCount(),
                        "pipelineRunId", savedRun.getId().toString()
                ),
                now
        );

        auditService.record(
                workspace,
                actor,
                "KUBERNETES_DEPLOYMENT_REQUESTED",
                "KUBERNETES_DEPLOYMENT",
                savedOp.getId().toString(),
                "Requested Kubernetes deployment \"" + savedOp.getDeploymentName() + "\" in "
                        + cluster.getClusterIdentifier() + "/" + savedOp.getNamespaceName()
                        + " (" + savedOp.getManifestDigest() + ")",
                Map.of(
                        "projectId", project.getId().toString(),
                        "environmentType", environment.getEnvironmentType().name(),
                        "clusterId", cluster.getId().toString(),
                        "namespace", savedOp.getNamespaceName(),
                        "artifactId", artifact.getId().toString(),
                        "manifestDigest", artifact.getManifestDigest(),
                        "manifestChecksum", savedOp.getManifestChecksumSha256()
                )
        );

        return new CreateDeploymentResult(lifecycleService.toOperationDto(savedOp), false);
    }

    @Transactional(readOnly = true)
    public PagedResponse<KubernetesDeploymentOperationDto> listDeployments(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            KubernetesDeploymentStatus status,
            String query,
            Pageable pageable,
            UUID userId
    ) {
        authorizationService.requireMembership(workspaceId, userId);
        Page<KubernetesDeploymentOperationEntity> page = deploymentOperationRepository.searchByWorkspace(
                workspaceId, projectId, environmentId, clusterId, status, query, pageable
        );
        return PagedResponse.from(page, lifecycleService::toOperationDto);
    }

    @Transactional(readOnly = true)
    public KubernetesDeploymentOperationDto getDeployment(UUID workspaceId, UUID deploymentId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByIdAndWorkspaceIdWithDetails(deploymentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes deployment operation not found in this workspace"));
        return lifecycleService.toOperationDto(op);
    }

    @Transactional(readOnly = true)
    public KubernetesDeploymentStatusDto getDeploymentStatus(UUID workspaceId, UUID deploymentId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByIdAndWorkspaceIdWithDetails(deploymentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes deployment operation not found in this workspace"));
        return new KubernetesDeploymentStatusDto(
                op.getId(),
                op.getWorkspace().getId(),
                op.getClusterConnection().getName(),
                op.getNamespaceName(),
                op.getDeploymentName(),
                op.getServiceName(),
                op.getImmutableImageReference(),
                op.getManifestDigest(),
                op.getStatus(),
                op.isApplyInitiated(),
                op.getRequestedReplicas(),
                op.getObservedReplicas(),
                op.getUpdatedReplicas(),
                op.getReadyReplicas(),
                op.getAvailableReplicas(),
                op.getObservedGeneration(),
                op.getK8sDeploymentUid(),
                op.getK8sServiceUid(),
                lifecycleService.readMapList(op.getRolloutConditionsJson()),
                lifecycleService.readMapList(op.getPodDiagnosticsJson()),
                op.getFailureCategory(),
                op.getErrorSummary(),
                op.getCancellationRequestedAt(),
                op.getStartedAt(),
                op.getCompletedAt(),
                op.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<KubernetesDeploymentEventDto> listDeploymentEvents(UUID workspaceId, UUID deploymentId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        deploymentOperationRepository.findByIdAndWorkspaceIdWithDetails(deploymentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes deployment operation not found in this workspace"));
        return deploymentEventRepository
                .findAllByDeploymentOperationIdAndWorkspaceIdOrderByOccurredAtAscCreatedAtAsc(deploymentId, workspaceId)
                .stream()
                .map(ev -> new KubernetesDeploymentEventDto(
                        ev.getId(),
                        ev.getDeploymentOperation().getId(),
                        ev.getEventType(),
                        ev.getFromStatus(),
                        ev.getToStatus(),
                        ev.getActorLabel(),
                        ev.getMessage(),
                        lifecycleService.readObjectMap(ev.getMetadataJson()),
                        ev.getOccurredAt()
                ))
                .toList();
    }

    @Transactional
    public KubernetesDeploymentOperationDto cancelDeployment(
            UUID workspaceId,
            UUID deploymentId,
            CancelDeploymentRequest request,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, actor.getId());
        KubernetesDeploymentOperationEntity op = deploymentOperationRepository
                .findByIdAndWorkspaceIdWithDetails(deploymentId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes deployment operation not found in this workspace"));

        if (op.getEnvironment().getEnvironmentType() == EnvironmentType.PRODUCTION
                && membership.getRole() != WorkspaceRole.OWNER) {
            throw new ForbiddenOperationException("Only workspace OWNER users may cancel PRODUCTION deployments");
        }
        if (op.getStatus().isTerminal()) {
            return lifecycleService.toOperationDto(op);
        }

        String reason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Cancelled by @" + actor.getUsername();

        // Delegate through CloudForge's existing pipeline cancellation flow so scheduler,
        // worker heartbeat directives, and linked deployment operation stay consistent.
        orchestrationService.cancelRun(workspaceId, op.getPipelineRun().getId(), new CancelPipelineRunRequest(reason), actor);

        KubernetesDeploymentOperationEntity refreshed = deploymentOperationRepository
                .findByIdAndWorkspaceIdWithDetails(deploymentId, workspaceId)
                .orElse(op);
        return lifecycleService.toOperationDto(refreshed);
    }

    private KubernetesNamespaceBindingEntity resolveNamespaceBinding(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            CreateDeploymentRequest request
    ) {
        if (request.namespaceBindingId() != null) {
            KubernetesNamespaceBindingEntity byId = namespaceBindingRepository
                    .findByIdAndWorkspaceIdWithDetails(request.namespaceBindingId(), workspaceId)
                    .orElseThrow(() -> new ForbiddenOperationException("Namespace binding does not belong to this workspace"));
            if (!byId.getProject().getId().equals(projectId)
                    || !byId.getEnvironment().getId().equals(environmentId)
                    || !byId.getClusterConnection().getId().equals(clusterId)) {
                throw new ForbiddenOperationException("Namespace binding does not match the selected project, environment, and cluster");
            }
            return byId;
        }
        return namespaceBindingRepository
                .findByWorkspaceIdAndProjectIdAndEnvironmentIdAndClusterConnectionId(
                        workspaceId, projectId, environmentId, clusterId
                )
                .orElseThrow(() -> new BadRequestException(
                        "No operator-approved namespace binding exists for this project, environment, and cluster; ask a workspace OWNER to bind an authorized namespace first"
                ));
    }

    private String computeRequestFingerprint(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            UUID namespaceBindingId,
            UUID artifactId,
            String configChecksum
    ) {
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("workspaceId", workspaceId.toString());
        fp.put("projectId", projectId.toString());
        fp.put("environmentId", environmentId.toString());
        fp.put("clusterId", clusterId.toString());
        fp.put("namespaceBindingId", namespaceBindingId.toString());
        fp.put("artifactId", artifactId.toString());
        fp.put("configChecksum", configChecksum);
        return KubernetesDeploymentConfigValidator.sha256Hex(writeJson(fp));
    }

    private KubernetesDeploymentConfigDto toConfigDto(KubernetesDeploymentConfigEntity c) {
        return new KubernetesDeploymentConfigDto(
                c.getId(),
                c.getWorkspace().getId(),
                c.getProject().getId(),
                c.getProject().getName(),
                c.getEnvironment().getId(),
                c.getEnvironment().getName(),
                c.getEnvironment().getEnvironmentType(),
                c.getClusterConnection().getId(),
                c.getClusterConnection().getName(),
                c.getNamespaceBinding().getId(),
                c.getNamespaceName(),
                c.getDeploymentName(),
                c.getServiceName(),
                c.getReplicaCount(),
                c.getContainerPort(),
                c.getServicePort(),
                c.getCpuRequest(),
                c.getCpuLimit(),
                c.getMemoryRequest(),
                c.getMemoryLimit(),
                readProbe(c.getReadinessProbeJson(), KubernetesProbeConfig.defaultReadiness(c.getContainerPort())),
                readProbe(c.getLivenessProbeJson(), KubernetesProbeConfig.defaultLiveness(c.getContainerPort())),
                StringUtils.hasText(c.getStartupProbeJson()) ? readProbe(c.getStartupProbeJson(), null) : null,
                c.getRollingMaxSurge(),
                c.getRollingMaxUnavailable(),
                c.getProgressDeadlineSeconds(),
                c.getDeploymentTimeoutSeconds(),
                c.isReadOnlyRootFilesystem(),
                c.isRunAsNonRoot(),
                c.getRunAsUser(),
                c.getConfigChecksumSha256(),
                c.getUpdatedAt()
        );
    }

    private KubernetesProbeConfig readProbe(String json, KubernetesProbeConfig fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, KubernetesProbeConfig.class);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    public record CreateDeploymentResult(
            KubernetesDeploymentOperationDto deployment,
            boolean idempotentReplay
    ) {
    }
}
