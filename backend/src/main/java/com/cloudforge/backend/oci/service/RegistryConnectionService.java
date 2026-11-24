package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.oci.dto.CreateRegistryConnectionRequest;
import com.cloudforge.backend.oci.dto.OciRegistryConnectionDto;
import com.cloudforge.backend.oci.dto.UpdateRegistryConnectionRequest;
import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciRegistryStatus;
import com.cloudforge.backend.oci.repository.OciRegistryConnectionRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretEntity;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.service.SlugUtils;
import com.cloudforge.backend.service.WorkspaceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages workspace-scoped OCI registry connections with OWNER-only mutations,
 * strict host/namespace/SSRF validation, and encrypted Phase 3.4 secret references only.
 */
@Service
public class RegistryConnectionService {

    private final OciRegistryConnectionRepository registryRepository;
    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final EnvironmentSecretRepository secretRepository;
    private final RegistrySecurityValidator securityValidator;
    private final WorkspaceAuthorizationService authorizationService;
    private final AuditService auditService;

    public RegistryConnectionService(
            OciRegistryConnectionRepository registryRepository,
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            EnvironmentSecretRepository secretRepository,
            RegistrySecurityValidator securityValidator,
            WorkspaceAuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.registryRepository = registryRepository;
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.secretRepository = secretRepository;
        this.securityValidator = securityValidator;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<OciRegistryConnectionDto> listConnections(UUID workspaceId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        return registryRepository.findAllByWorkspaceIdWithDetails(workspaceId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public OciRegistryConnectionDto getConnection(UUID workspaceId, UUID registryId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        OciRegistryConnectionEntity entity = registryRepository
                .findByIdAndWorkspaceIdWithDetails(registryId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("OCI registry connection not found in this workspace"));
        return toDto(entity);
    }

    @Transactional
    public OciRegistryConnectionDto createConnection(
            UUID workspaceId,
            CreateRegistryConnectionRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        RegistrySecurityValidator.ValidatedRegistryDestination dest = securityValidator.validateRegistryDestination(
                request.registryHost(),
                request.scheme(),
                request.registryType(),
                request.allowedNamespace(),
                Boolean.TRUE.equals(request.allowInsecureLocal())
        );

        CredentialRefs refs = resolveCredentialReferences(
                workspaceId,
                request.credentialProjectId(),
                request.credentialEnvironmentId(),
                request.usernameSecretId(),
                request.passwordSecretId()
        );

        String name = request.name().trim();
        String slug = resolveUniqueSlug(workspaceId, name, null);

        OciRegistryConnectionEntity entity = new OciRegistryConnectionEntity();
        entity.setWorkspace(workspace);
        entity.setName(name);
        entity.setSlug(slug);
        entity.setRegistryHost(dest.registryHost());
        entity.setScheme(dest.scheme());
        entity.setRegistryType(request.registryType());
        entity.setAllowedNamespace(dest.allowedNamespace());
        entity.setCredentialProject(refs.project());
        entity.setCredentialEnvironment(refs.environment());
        entity.setUsernameSecret(refs.usernameSecret());
        entity.setPasswordSecret(refs.passwordSecret());
        entity.setAllowInsecureLocal(dest.allowInsecureLocal());
        entity.setStatus(OciRegistryStatus.ACTIVE);
        entity.setDescription(trimToNull(request.description()));
        entity.setCreatedBy(user);
        entity.setUpdatedBy(user);

        OciRegistryConnectionEntity saved = registryRepository.saveAndFlush(entity);

        auditService.record(
                workspace,
                user,
                "OCI_REGISTRY_CREATED",
                "OCI_REGISTRY_CONNECTION",
                saved.getId().toString(),
                "Configured OCI registry \"" + saved.getName() + "\" (" + saved.getRegistryHost()
                        + "/" + saved.getAllowedNamespace() + ")",
                Map.of(
                        "registryHost", saved.getRegistryHost(),
                        "scheme", saved.getScheme(),
                        "registryType", saved.getRegistryType().name(),
                        "allowedNamespace", saved.getAllowedNamespace(),
                        "credentialsConfigured", saved.getPasswordSecret() != null
                )
        );

        return toDto(saved);
    }

    @Transactional
    public OciRegistryConnectionDto updateConnection(
            UUID workspaceId,
            UUID registryId,
            UpdateRegistryConnectionRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        OciRegistryConnectionEntity entity = registryRepository
                .findByIdAndWorkspaceIdForUpdate(registryId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("OCI registry connection not found in this workspace"));

        String namespace = securityValidator.validateNamespace(request.allowedNamespace());
        CredentialRefs refs = resolveCredentialReferences(
                workspaceId,
                request.credentialProjectId(),
                request.credentialEnvironmentId(),
                request.usernameSecretId(),
                request.passwordSecretId()
        );

        String name = request.name().trim();
        entity.setName(name);
        entity.setSlug(resolveUniqueSlug(workspaceId, name, entity.getId()));
        entity.setAllowedNamespace(namespace);
        entity.setCredentialProject(refs.project());
        entity.setCredentialEnvironment(refs.environment());
        entity.setUsernameSecret(refs.usernameSecret());
        entity.setPasswordSecret(refs.passwordSecret());
        if (request.status() != null) {
            entity.setStatus(request.status());
        }
        entity.setDescription(trimToNull(request.description()));
        entity.setUpdatedBy(user);

        OciRegistryConnectionEntity saved = registryRepository.saveAndFlush(entity);
        auditService.record(
                membership.getWorkspace(),
                user,
                "OCI_REGISTRY_UPDATED",
                "OCI_REGISTRY_CONNECTION",
                saved.getId().toString(),
                "Updated OCI registry connection \"" + saved.getName() + "\"",
                Map.of(
                        "registryHost", saved.getRegistryHost(),
                        "allowedNamespace", saved.getAllowedNamespace(),
                        "status", saved.getStatus().name()
                )
        );
        return toDto(saved);
    }

    @Transactional
    public OciRegistryConnectionDto disableConnection(UUID workspaceId, UUID registryId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        OciRegistryConnectionEntity entity = registryRepository
                .findByIdAndWorkspaceIdForUpdate(registryId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("OCI registry connection not found in this workspace"));

        entity.setStatus(OciRegistryStatus.DISABLED);
        entity.setUpdatedBy(user);
        OciRegistryConnectionEntity saved = registryRepository.saveAndFlush(entity);

        auditService.record(
                membership.getWorkspace(),
                user,
                "OCI_REGISTRY_DISABLED",
                "OCI_REGISTRY_CONNECTION",
                saved.getId().toString(),
                "Disabled OCI registry connection \"" + saved.getName() + "\"",
                Map.of("registryHost", saved.getRegistryHost())
        );
        return toDto(saved);
    }

    private CredentialRefs resolveCredentialReferences(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID usernameSecretId,
            UUID passwordSecretId
    ) {
        boolean anySpecified = projectId != null || environmentId != null
                || usernameSecretId != null || passwordSecretId != null;
        if (!anySpecified) {
            return new CredentialRefs(null, null, null, null);
        }
        if (projectId == null || environmentId == null || passwordSecretId == null) {
            throw new BadRequestException(
                    "Registry credential configuration requires credentialProjectId, credentialEnvironmentId, and passwordSecretId"
            );
        }
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new BadRequestException("Credential project does not belong to this workspace"));
        ProjectEnvironmentEntity environment = environmentRepository.findByIdAndProjectId(environmentId, project.getId())
                .orElseThrow(() -> new BadRequestException("Credential environment does not belong to the selected project"));

        EnvironmentSecretEntity passwordSecret = secretRepository
                .findByScopedIdWithDetails(passwordSecretId, environment.getId(), project.getId(), workspaceId)
                .filter(s -> s.getStatus() == SecretLifecycleStatus.ACTIVE)
                .orElseThrow(() -> new BadRequestException(
                        "Referenced password secret must be ACTIVE within the selected workspace project environment"
                ));

        EnvironmentSecretEntity usernameSecret = null;
        if (usernameSecretId != null) {
            usernameSecret = secretRepository
                    .findByScopedIdWithDetails(usernameSecretId, environment.getId(), project.getId(), workspaceId)
                    .filter(s -> s.getStatus() == SecretLifecycleStatus.ACTIVE)
                    .orElseThrow(() -> new BadRequestException(
                            "Referenced username secret must be ACTIVE within the selected workspace project environment"
                    ));
        }

        return new CredentialRefs(project, environment, usernameSecret, passwordSecret);
    }

    private String resolveUniqueSlug(UUID workspaceId, String name, UUID excludeId) {
        String base = SlugUtils.toSlug(name, "registry");
        String candidate = base;
        int suffix = 2;
        while (excludeId == null
                ? registryRepository.existsByWorkspaceIdAndSlug(workspaceId, candidate)
                : registryRepository.existsByWorkspaceIdAndSlugAndIdNot(workspaceId, candidate, excludeId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    public OciRegistryConnectionDto toDto(OciRegistryConnectionEntity e) {
        return new OciRegistryConnectionDto(
                e.getId(),
                e.getWorkspace().getId(),
                e.getName(),
                e.getSlug(),
                e.getRegistryHost(),
                e.getScheme(),
                e.getRegistryType(),
                e.getAllowedNamespace(),
                e.getCredentialProject() != null ? e.getCredentialProject().getId() : null,
                e.getCredentialProject() != null ? e.getCredentialProject().getName() : null,
                e.getCredentialEnvironment() != null ? e.getCredentialEnvironment().getId() : null,
                e.getCredentialEnvironment() != null ? e.getCredentialEnvironment().getName() : null,
                e.getUsernameSecret() != null ? e.getUsernameSecret().getId() : null,
                e.getUsernameSecret() != null ? e.getUsernameSecret().getSecretName() : null,
                e.getPasswordSecret() != null ? e.getPasswordSecret().getId() : null,
                e.getPasswordSecret() != null ? e.getPasswordSecret().getSecretName() : null,
                e.getPasswordSecret() != null,
                e.isAllowInsecureLocal(),
                e.getStatus(),
                e.getDescription(),
                e.getCreatedBy() != null ? e.getCreatedBy().getUsername() : null,
                e.getUpdatedBy() != null ? e.getUpdatedBy().getUsername() : null,
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record CredentialRefs(
            ProjectEntity project,
            ProjectEnvironmentEntity environment,
            EnvironmentSecretEntity usernameSecret,
            EnvironmentSecretEntity passwordSecret
    ) {
    }
}
