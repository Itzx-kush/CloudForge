package com.cloudforge.backend.k8s.service;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.k8s.dto.CreateClusterConnectionRequest;
import com.cloudforge.backend.k8s.dto.CreateNamespaceBindingRequest;
import com.cloudforge.backend.k8s.dto.KubernetesClusterConnectionDto;
import com.cloudforge.backend.k8s.dto.KubernetesNamespaceBindingDto;
import com.cloudforge.backend.k8s.dto.UpdateClusterConnectionRequest;
import com.cloudforge.backend.k8s.entity.KubernetesClusterConnectionEntity;
import com.cloudforge.backend.k8s.entity.KubernetesClusterStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingEntity;
import com.cloudforge.backend.k8s.entity.KubernetesNamespaceBindingStatus;
import com.cloudforge.backend.k8s.entity.KubernetesNetworkPolicyMode;
import com.cloudforge.backend.k8s.entity.KubernetesPodSecurityStandard;
import com.cloudforge.backend.k8s.repository.KubernetesClusterConnectionRepository;
import com.cloudforge.backend.k8s.repository.KubernetesNamespaceBindingRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.SlugUtils;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace-scoped Kubernetes cluster connection and approved namespace binding management.
 *
 * <p>Strictly enforces OWNER RBAC on mutations, SSRF/HTTPS/CA validation, and Phase 3.4
 * encrypted secret references for deployment bearer tokens. Never executes Kubernetes API
 * calls directly inside the Spring Boot control plane — cluster validation is dispatched
 * asynchronously to allowlisted {@code KUBERNETES_DEPLOY} workers.</p>
 */
@Service
public class KubernetesClusterService {

    private final WorkspaceAuthorizationService authorizationService;
    private final KubernetesClusterConnectionRepository clusterRepository;
    private final KubernetesNamespaceBindingRepository namespaceBindingRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final EnvironmentSecretRepository secretRepository;
    private final ClusterSecurityValidator securityValidator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public KubernetesClusterService(
            WorkspaceAuthorizationService authorizationService,
            KubernetesClusterConnectionRepository clusterRepository,
            KubernetesNamespaceBindingRepository namespaceBindingRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            EnvironmentSecretRepository secretRepository,
            ClusterSecurityValidator securityValidator,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.authorizationService = authorizationService;
        this.clusterRepository = clusterRepository;
        this.namespaceBindingRepository = namespaceBindingRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.secretRepository = secretRepository;
        this.securityValidator = securityValidator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<KubernetesClusterConnectionDto> listClusters(UUID workspaceId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        List<KubernetesClusterConnectionEntity> clusters = clusterRepository.findAllByWorkspaceIdWithDetails(workspaceId);
        List<KubernetesNamespaceBindingEntity> bindings = namespaceBindingRepository.findAllByWorkspaceIdWithDetails(workspaceId);
        Map<UUID, List<KubernetesNamespaceBindingDto>> byCluster = new HashMap<>();
        for (KubernetesNamespaceBindingEntity b : bindings) {
            byCluster.computeIfAbsent(b.getClusterConnection().getId(), k -> new ArrayList<>())
                    .add(toNamespaceDto(b));
        }
        return clusters.stream()
                .map(c -> toClusterDto(c, byCluster.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public KubernetesClusterConnectionDto getCluster(UUID workspaceId, UUID clusterId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        KubernetesClusterConnectionEntity cluster = clusterRepository
                .findByIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));
        List<KubernetesNamespaceBindingDto> bindings = namespaceBindingRepository
                .findAllByClusterConnectionIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
        return toClusterDto(cluster, bindings);
    }

    @Transactional
    public KubernetesClusterConnectionDto createCluster(
            UUID workspaceId,
            CreateClusterConnectionRequest request,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, actor.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        String identifier = securityValidator.validateClusterIdentifier(request.clusterIdentifier());
        if (clusterRepository.existsByWorkspaceIdAndClusterIdentifier(workspaceId, identifier)) {
            throw new ConflictException("Cluster identifier \"" + identifier + "\" already exists in this workspace");
        }

        ClusterSecurityValidator.ValidatedClusterEndpoint endpoint = securityValidator.validateClusterEndpoint(
                request.apiServerUrl(),
                request.clusterType(),
                Boolean.TRUE.equals(request.allowLocalLoopback())
        );
        ClusterSecurityValidator.ValidatedCaCertificate ca = securityValidator.validateCaCertificatePem(
                request.caCertificatePem()
        );
        String serviceAccount = securityValidator.validateServiceAccountName(
                request.serviceAccountName(), "serviceAccountName"
        );
        ResolvedClusterCredentialRef credRef = resolveActiveBearerSecret(
                workspaceId,
                request.credentialProjectId(),
                request.credentialEnvironmentId(),
                request.bearerTokenSecretId()
        );

        String name = request.name().trim();
        String slug = resolveUniqueSlug(workspaceId, name, null);

        KubernetesClusterConnectionEntity entity = new KubernetesClusterConnectionEntity();
        entity.setWorkspace(workspace);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setClusterIdentifier(identifier);
        entity.setClusterType(request.clusterType());
        entity.setEnvironmentCategory(request.environmentCategory());
        entity.setApiServerUrl(endpoint.apiServerUrl());
        entity.setCaCertificatePem(ca.pem());
        entity.setCaCertificateFingerprintSha256(ca.fingerprintSha256());
        entity.setCredentialProject(credRef.project());
        entity.setCredentialEnvironment(credRef.environment());
        entity.setBearerTokenSecret(credRef.secret());
        entity.setServiceAccountName(serviceAccount);
        entity.setAllowLocalLoopback(endpoint.allowLocalLoopback());
        entity.setProductionApprovalRequired(request.productionApprovalRequired() == null || request.productionApprovalRequired());
        entity.setConnectionStatus(KubernetesClusterStatus.PENDING_VALIDATION);
        entity.setDescription(trimToNull(request.description()));
        entity.setCreatedBy(actor);
        entity.setUpdatedBy(actor);

        KubernetesClusterConnectionEntity saved = clusterRepository.saveAndFlush(entity);

        auditService.record(
                workspace,
                actor,
                "KUBERNETES_CLUSTER_REGISTERED",
                "KUBERNETES_CLUSTER",
                saved.getId().toString(),
                "Registered Kubernetes cluster \"" + saved.getName() + "\" (" + saved.getClusterType() + ")",
                Map.of(
                        "clusterIdentifier", saved.getClusterIdentifier(),
                        "clusterType", saved.getClusterType().name(),
                        "environmentCategory", saved.getEnvironmentCategory().name(),
                        "apiServerUrl", saved.getApiServerUrl(),
                        "caFingerprint", saved.getCaCertificateFingerprintSha256(),
                        "bearerTokenSecretName", credRef.secret().getSecretName()
                )
        );

        return toClusterDto(saved, List.of());
    }

    @Transactional
    public KubernetesClusterConnectionDto updateCluster(
            UUID workspaceId,
            UUID clusterId,
            UpdateClusterConnectionRequest request,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, actor.getId());
        KubernetesClusterConnectionEntity entity = clusterRepository
                .findByIdAndWorkspaceIdForUpdate(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));

        ClusterSecurityValidator.ValidatedClusterEndpoint endpoint = securityValidator.validateClusterEndpoint(
                request.apiServerUrl(),
                request.clusterType(),
                Boolean.TRUE.equals(request.allowLocalLoopback())
        );
        ClusterSecurityValidator.ValidatedCaCertificate ca = securityValidator.validateCaCertificatePem(
                request.caCertificatePem()
        );
        String serviceAccount = securityValidator.validateServiceAccountName(
                request.serviceAccountName(), "serviceAccountName"
        );
        ResolvedClusterCredentialRef credRef = resolveActiveBearerSecret(
                workspaceId,
                request.credentialProjectId(),
                request.credentialEnvironmentId(),
                request.bearerTokenSecretId()
        );

        String name = request.name().trim();
        entity.setName(name);
        entity.setSlug(resolveUniqueSlug(workspaceId, name, entity.getId()));
        entity.setClusterType(request.clusterType());
        entity.setEnvironmentCategory(request.environmentCategory());
        entity.setApiServerUrl(endpoint.apiServerUrl());
        entity.setCaCertificatePem(ca.pem());
        entity.setCaCertificateFingerprintSha256(ca.fingerprintSha256());
        entity.setCredentialProject(credRef.project());
        entity.setCredentialEnvironment(credRef.environment());
        entity.setBearerTokenSecret(credRef.secret());
        entity.setServiceAccountName(serviceAccount);
        entity.setAllowLocalLoopback(endpoint.allowLocalLoopback());
        entity.setProductionApprovalRequired(request.productionApprovalRequired() == null || request.productionApprovalRequired());
        entity.setConnectionStatus(KubernetesClusterStatus.PENDING_VALIDATION);
        entity.setLastValidationError(null);
        entity.setDescription(trimToNull(request.description()));
        entity.setUpdatedBy(actor);

        KubernetesClusterConnectionEntity saved = clusterRepository.saveAndFlush(entity);

        auditService.record(
                membership.getWorkspace(),
                actor,
                "KUBERNETES_CLUSTER_UPDATED",
                "KUBERNETES_CLUSTER",
                saved.getId().toString(),
                "Updated Kubernetes cluster \"" + saved.getName() + "\" (re-validation required)",
                Map.of("apiServerUrl", saved.getApiServerUrl(), "caFingerprint", saved.getCaCertificateFingerprintSha256())
        );

        List<KubernetesNamespaceBindingDto> bindings = namespaceBindingRepository
                .findAllByClusterConnectionIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
        return toClusterDto(saved, bindings);
    }

    @Transactional
    public KubernetesClusterConnectionDto disableCluster(UUID workspaceId, UUID clusterId, UserEntity actor) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, actor.getId());
        KubernetesClusterConnectionEntity entity = clusterRepository
                .findByIdAndWorkspaceIdForUpdate(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));

        entity.setConnectionStatus(KubernetesClusterStatus.DISABLED);
        entity.setUpdatedBy(actor);
        KubernetesClusterConnectionEntity saved = clusterRepository.saveAndFlush(entity);

        auditService.record(
                membership.getWorkspace(),
                actor,
                "KUBERNETES_CLUSTER_DISABLED",
                "KUBERNETES_CLUSTER",
                saved.getId().toString(),
                "Disabled Kubernetes cluster \"" + saved.getName() + "\"",
                Map.of("clusterIdentifier", saved.getClusterIdentifier())
        );

        List<KubernetesNamespaceBindingDto> bindings = namespaceBindingRepository
                .findAllByClusterConnectionIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
        return toClusterDto(saved, bindings);
    }

    /**
     * Re-verifies static security invariants (SSRF, HTTPS, CA PEM, active secret) and marks
     * the cluster ready for worker-side live Kubernetes API, TLS, RBAC, and namespace validation.
     * Never opens Kubernetes sockets inside the Spring Boot control plane.
     */
    @Transactional
    public KubernetesClusterConnectionDto requestClusterValidation(
            UUID workspaceId,
            UUID clusterId,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, actor.getId());
        KubernetesClusterConnectionEntity cluster = clusterRepository
                .findByIdAndWorkspaceIdForUpdate(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));

        if (cluster.getConnectionStatus() == KubernetesClusterStatus.DISABLED) {
            throw new ConflictException("Disabled cluster connection cannot be validated");
        }

        // Fail closed if endpoint policy or encrypted secret status degraded since registration
        securityValidator.validateClusterEndpoint(
                cluster.getApiServerUrl(),
                cluster.getClusterType(),
                cluster.isAllowLocalLoopback()
        );
        securityValidator.validateCaCertificatePem(cluster.getCaCertificatePem());
        resolveActiveBearerSecret(
                workspaceId,
                cluster.getCredentialProject().getId(),
                cluster.getCredentialEnvironment().getId(),
                cluster.getBearerTokenSecret().getId()
        );

        Instant now = Instant.now();
        cluster.setValidationNonce(cluster.getValidationNonce() + 1);
        cluster.setValidationRequestedAt(now);
        cluster.setConnectionStatus(KubernetesClusterStatus.VALIDATING);
        cluster.setLastValidationError(null);
        cluster.setUpdatedBy(actor);
        KubernetesClusterConnectionEntity saved = clusterRepository.saveAndFlush(cluster);

        auditService.record(
                membership.getWorkspace(),
                actor,
                "KUBERNETES_CLUSTER_VALIDATION_REQUESTED",
                "KUBERNETES_CLUSTER",
                saved.getId().toString(),
                "Requested worker-side TLS, RBAC, and namespace validation for cluster \"" + saved.getName() + "\"",
                Map.of("validationNonce", saved.getValidationNonce())
        );

        List<KubernetesNamespaceBindingDto> bindings = namespaceBindingRepository
                .findAllByClusterConnectionIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
        return toClusterDto(saved, bindings);
    }

    @Transactional(readOnly = true)
    public List<KubernetesNamespaceBindingDto> listClusterNamespaces(UUID workspaceId, UUID clusterId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        clusterRepository.findByIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));
        return namespaceBindingRepository
                .findAllByClusterConnectionIdAndWorkspaceIdWithDetails(clusterId, workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KubernetesNamespaceBindingDto> listWorkspaceNamespaces(UUID workspaceId, UUID userId) {
        authorizationService.requireMembership(workspaceId, userId);
        return namespaceBindingRepository.findAllByWorkspaceIdWithDetails(workspaceId)
                .stream()
                .map(this::toNamespaceDto)
                .toList();
    }

    @Transactional
    public KubernetesNamespaceBindingDto createNamespaceBinding(
            UUID workspaceId,
            UUID clusterId,
            CreateNamespaceBindingRequest request,
            UserEntity actor
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, actor.getId());
        KubernetesClusterConnectionEntity cluster = clusterRepository
                .findByIdAndWorkspaceIdForUpdate(clusterId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Kubernetes cluster connection not found in this workspace"));

        if (cluster.getConnectionStatus() == KubernetesClusterStatus.DISABLED) {
            throw new ConflictException("Cannot bind namespaces to a disabled Kubernetes cluster");
        }

        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(request.projectId(), workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        ProjectEnvironmentEntity environment = environmentRepository
                .findByIdAndProjectId(request.environmentId(), project.getId())
                .orElseThrow(() -> new BadRequestException("Target environment does not belong to the selected project"));

        if (namespaceBindingRepository.findByWorkspaceIdAndProjectIdAndEnvironmentIdAndClusterConnectionId(
                workspaceId, project.getId(), environment.getId(), cluster.getId()
        ).isPresent()) {
            throw new ConflictException(
                    "An approved namespace binding already exists for project \"" + project.getName()
                            + "\" environment \"" + environment.getName() + "\" on cluster \"" + cluster.getName() + "\""
            );
        }

        String namespaceName = StringUtils.hasText(request.namespaceName())
                ? securityValidator.validateNamespaceName(request.namespaceName())
                : securityValidator.generateDeterministicNamespace(workspaceId, project.getSlug(), environment.getEnvironmentType());

        if (namespaceBindingRepository.existsByClusterConnectionIdAndNamespaceName(cluster.getId(), namespaceName)) {
            throw new ConflictException(
                    "Namespace \"" + namespaceName + "\" is already bound on cluster \"" + cluster.getName() + "\""
            );
        }

        String workloadSa = StringUtils.hasText(request.workloadServiceAccountName())
                ? securityValidator.validateServiceAccountName(request.workloadServiceAccountName(), "workloadServiceAccountName")
                : "cloudforge-workload";
        String pullSecret = securityValidator.validateOptionalPullSecretName(request.imagePullSecretName());

        KubernetesNamespaceBindingEntity binding = new KubernetesNamespaceBindingEntity();
        binding.setWorkspace(membership.getWorkspace());
        binding.setClusterConnection(cluster);
        binding.setProject(project);
        binding.setEnvironment(environment);
        binding.setNamespaceName(namespaceName);
        binding.setWorkloadServiceAccountName(workloadSa);
        binding.setImagePullSecretName(pullSecret);
        binding.setPodSecurityStandard(
                request.podSecurityStandard() != null ? request.podSecurityStandard() : KubernetesPodSecurityStandard.RESTRICTED
        );
        binding.setNetworkPolicyMode(
                request.networkPolicyMode() != null ? request.networkPolicyMode() : KubernetesNetworkPolicyMode.DEFAULT_DENY
        );
        binding.setResourceQuotaConfigured(request.resourceQuotaConfigured() == null || request.resourceQuotaConfigured());
        binding.setLimitRangeConfigured(request.limitRangeConfigured() == null || request.limitRangeConfigured());
        binding.setStatus(KubernetesNamespaceBindingStatus.ACTIVE);
        binding.setCreatedBy(actor);
        binding.setUpdatedBy(actor);

        KubernetesNamespaceBindingEntity saved = namespaceBindingRepository.saveAndFlush(binding);

        auditService.record(
                membership.getWorkspace(),
                actor,
                "KUBERNETES_NAMESPACE_BOUND",
                "KUBERNETES_NAMESPACE",
                saved.getId().toString(),
                "Bound approved namespace \"" + saved.getNamespaceName() + "\" on cluster \"" + cluster.getName()
                        + "\" for " + project.getName() + " (" + environment.getEnvironmentType() + ")",
                Map.of(
                        "clusterId", cluster.getId().toString(),
                        "projectId", project.getId().toString(),
                        "environmentId", environment.getId().toString(),
                        "namespaceName", saved.getNamespaceName(),
                        "podSecurityStandard", saved.getPodSecurityStandard().name(),
                        "networkPolicyMode", saved.getNetworkPolicyMode().name()
                )
        );

        return toNamespaceDto(saved);
    }

    private ResolvedClusterCredentialRef resolveActiveBearerSecret(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID secretId
    ) {
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new BadRequestException("Credential project does not belong to this workspace"));
        ProjectEnvironmentEntity environment = environmentRepository.findByIdAndProjectId(environmentId, project.getId())
                .orElseThrow(() -> new BadRequestException("Credential environment does not belong to the selected project"));
        EnvironmentSecretEntity secret = secretRepository
                .findByScopedIdWithDetails(secretId, environment.getId(), project.getId(), workspaceId)
                .orElseThrow(() -> new BadRequestException(
                        "Referenced cluster bearer token secret does not exist in the selected project/environment"
                ));
        if (secret.getStatus() != SecretLifecycleStatus.ACTIVE || secret.getCurrentVersionId() == null) {
            throw new BadRequestException(
                    "Referenced cluster bearer token secret \"" + secret.getSecretName() + "\" must be ACTIVE"
            );
        }
        return new ResolvedClusterCredentialRef(project, environment, secret);
    }

    private String resolveUniqueSlug(UUID workspaceId, String name, UUID excludeId) {
        String base = SlugUtils.toSlug(name, "cluster");
        String candidate = base;
        int suffix = 2;
        while (excludeId == null
                ? clusterRepository.existsByWorkspaceIdAndSlug(workspaceId, candidate)
                : clusterRepository.existsByWorkspaceIdAndSlugAndIdNot(workspaceId, candidate, excludeId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    public KubernetesClusterConnectionDto toClusterDto(
            KubernetesClusterConnectionEntity c,
            List<KubernetesNamespaceBindingDto> bindings
    ) {
        return new KubernetesClusterConnectionDto(
                c.getId(),
                c.getWorkspace().getId(),
                c.getName(),
                c.getSlug(),
                c.getClusterIdentifier(),
                c.getClusterType(),
                c.getEnvironmentCategory(),
                c.getApiServerUrl(),
                c.getCaCertificateFingerprintSha256(),
                c.getCredentialProject().getId(),
                c.getCredentialProject().getName(),
                c.getCredentialEnvironment().getId(),
                c.getCredentialEnvironment().getName(),
                c.getBearerTokenSecret().getId(),
                c.getBearerTokenSecret().getSecretName(),
                c.getServiceAccountName(),
                c.isAllowLocalLoopback(),
                c.isProductionApprovalRequired(),
                c.getConnectionStatus(),
                c.getValidationRequestedAt(),
                c.getLastValidatedAt(),
                c.getLastValidationWorkerId(),
                c.getLastValidationError(),
                c.getKubernetesVersion(),
                readObjectMap(c.getSupportedCapabilitiesJson()),
                bindings,
                c.getDescription(),
                c.getCreatedBy() != null ? c.getCreatedBy().getUsername() : null,
                c.getUpdatedBy() != null ? c.getUpdatedBy().getUsername() : null,
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public KubernetesNamespaceBindingDto toNamespaceDto(KubernetesNamespaceBindingEntity n) {
        return new KubernetesNamespaceBindingDto(
                n.getId(),
                n.getWorkspace().getId(),
                n.getClusterConnection().getId(),
                n.getClusterConnection().getName(),
                n.getProject().getId(),
                n.getProject().getName(),
                n.getEnvironment().getId(),
                n.getEnvironment().getName(),
                n.getEnvironment().getEnvironmentType(),
                n.getNamespaceName(),
                n.getWorkloadServiceAccountName(),
                n.getImagePullSecretName(),
                n.getPodSecurityStandard(),
                n.getNetworkPolicyMode(),
                n.isResourceQuotaConfigured(),
                n.isLimitRangeConfigured(),
                n.getStatus(),
                n.getVerifiedAt(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        );
    }

    private Map<String, Object> readObjectMap(String json) {
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

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record ResolvedClusterCredentialRef(
            ProjectEntity project,
            ProjectEnvironmentEntity environment,
            EnvironmentSecretEntity secret
    ) {
    }
}
