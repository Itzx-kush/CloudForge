package com.cloudforge.backend.service;

import com.cloudforge.backend.dto.CreateProjectRequest;
import com.cloudforge.backend.dto.LinkedRepositoryDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.dto.ProjectDetailDto;
import com.cloudforge.backend.dto.ProjectDto;
import com.cloudforge.backend.dto.ProjectEnvironmentDto;
import com.cloudforge.backend.dto.UpdateProjectRequest;
import com.cloudforge.backend.entity.LinkedRepositoryEntity;
import com.cloudforge.backend.entity.ProjectEntity;
import com.cloudforge.backend.entity.ProjectEnvironmentEntity;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectEnvironmentRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectEnvironmentRepository environmentRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final GitHubIntegrationService gitHubIntegrationService;
    private final ProjectEnvironmentService environmentService;
    private final AuditService auditService;

    public ProjectService(
            ProjectRepository projectRepository,
            ProjectEnvironmentRepository environmentRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            WorkspaceAuthorizationService authorizationService,
            GitHubIntegrationService gitHubIntegrationService,
            ProjectEnvironmentService environmentService,
            AuditService auditService
    ) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.authorizationService = authorizationService;
        this.gitHubIntegrationService = gitHubIntegrationService;
        this.environmentService = environmentService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PagedResponse<ProjectDto> listProjects(
            UUID workspaceId,
            String query,
            ProjectStatus status,
            Pageable pageable,
            UserEntity user
    ) {
        authorizationService.requireMembership(workspaceId, user.getId());
        String normalizedQuery = StringUtils.hasText(query) ? query.trim() : null;
        Page<ProjectEntity> page = projectRepository.searchByWorkspace(workspaceId, normalizedQuery, status, pageable);
        return PagedResponse.from(page, this::toDto);
    }

    @Transactional(readOnly = true)
    public ProjectDetailDto getProjectDetail(UUID workspaceId, UUID projectId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));
        List<ProjectEnvironmentDto> environments = environmentRepository
                .findAllByProjectIdOrderByCreatedAtAsc(project.getId())
                .stream()
                .map(environmentService::toDto)
                .toList();
        return toDetailDto(project, environments);
    }

    @Transactional
    public ProjectDetailDto createProject(UUID workspaceId, CreateProjectRequest request, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        LinkedRepositoryEntity linkedRepo = resolveAuthorizedRepository(workspaceId, request.linkedRepositoryId());

        String name = request.name().trim();
        String slug = generateUniqueProjectSlug(workspaceId, SlugUtils.toSlug(name, "project"), null);

        String defaultBranch = StringUtils.hasText(request.defaultBranch())
                ? request.defaultBranch().trim()
                : (linkedRepo != null && StringUtils.hasText(linkedRepo.getDefaultBranch())
                ? linkedRepo.getDefaultBranch()
                : "main");

        ProjectEntity project = new ProjectEntity();
        project.setWorkspace(workspace);
        project.setName(name);
        project.setSlug(slug);
        project.setDescription(trimToNull(request.description()));
        project.setLinkedRepository(linkedRepo);
        project.setDefaultBranch(defaultBranch);
        project.setStatus(request.status() != null ? request.status() : ProjectStatus.ACTIVE);
        project.setCreatedBy(user);

        ProjectEntity saved = projectRepository.save(project);
        List<ProjectEnvironmentEntity> seededEnvs = environmentService.initializeDefaultEnvironments(saved);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("slug", saved.getSlug());
        metadata.put("defaultBranch", saved.getDefaultBranch());
        metadata.put("status", saved.getStatus().name());
        if (linkedRepo != null) {
            metadata.put("linkedRepository", linkedRepo.getFullName());
        }

        auditService.record(
                workspace,
                user,
                "PROJECT_CREATED",
                "PROJECT",
                saved.getId().toString(),
                "Created project \"" + saved.getName() + "\""
                        + (linkedRepo != null ? " linked to " + linkedRepo.getFullName() : ""),
                metadata
        );

        return toDetailDto(saved, seededEnvs.stream().map(environmentService::toDto).toList());
    }

    @Transactional
    public ProjectDetailDto updateProject(
            UUID workspaceId,
            UUID projectId,
            UpdateProjectRequest request,
            UserEntity user
    ) {
        WorkspaceMemberEntity membership = authorizationService.requireDeveloperOrAbove(workspaceId, user.getId());
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));

        LinkedRepositoryEntity linkedRepo = resolveAuthorizedRepository(workspaceId, request.linkedRepositoryId());

        String trimmedName = request.name().trim();
        if (!trimmedName.equals(project.getName())) {
            project.setSlug(generateUniqueProjectSlug(workspaceId, SlugUtils.toSlug(trimmedName, "project"), project.getId()));
        }
        project.setName(trimmedName);
        project.setDescription(trimToNull(request.description()));
        project.setLinkedRepository(linkedRepo);
        project.setDefaultBranch(request.defaultBranch().trim());
        if (request.status() != null) {
            project.setStatus(request.status());
        }

        ProjectEntity saved = projectRepository.save(project);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("slug", saved.getSlug());
        metadata.put("defaultBranch", saved.getDefaultBranch());
        metadata.put("status", saved.getStatus().name());
        if (linkedRepo != null) {
            metadata.put("linkedRepository", linkedRepo.getFullName());
        }

        auditService.record(
                membership.getWorkspace(),
                user,
                "PROJECT_UPDATED",
                "PROJECT",
                saved.getId().toString(),
                "Updated project \"" + saved.getName() + "\"",
                metadata
        );

        List<ProjectEnvironmentDto> environments = environmentRepository
                .findAllByProjectIdOrderByCreatedAtAsc(saved.getId())
                .stream()
                .map(environmentService::toDto)
                .toList();
        return toDetailDto(saved, environments);
    }

    @Transactional
    public void deleteProject(UUID workspaceId, UUID projectId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        ProjectEntity project = projectRepository.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found in this workspace"));

        String projectName = project.getName();
        String projectSlug = project.getSlug();
        environmentRepository.deleteAllByProjectId(project.getId());
        environmentRepository.flush();
        projectRepository.delete(project);
        projectRepository.flush();

        auditService.record(
                membership.getWorkspace(),
                user,
                "PROJECT_DELETED",
                "PROJECT",
                projectId.toString(),
                "Deleted project \"" + projectName + "\"",
                Map.of("slug", projectSlug)
        );
    }

    private LinkedRepositoryEntity resolveAuthorizedRepository(UUID workspaceId, UUID linkedRepositoryId) {
        if (linkedRepositoryId == null) {
            return null;
        }
        LinkedRepositoryEntity repo = linkedRepositoryRepository.findByIdAndWorkspaceId(linkedRepositoryId, workspaceId)
                .orElseThrow(() -> new BadRequestException(
                        "Selected repository is not authorized for this workspace",
                        Map.of("linkedRepositoryId", "Repository does not exist in this workspace")
                ));
        if (repo.getConnectionStatus() == RepositoryConnectionStatus.DISCONNECTED) {
            throw new BadRequestException(
                    "Cannot link a disconnected repository to a project",
                    Map.of("linkedRepositoryId", "Repository connection status is DISCONNECTED")
            );
        }
        return repo;
    }

    private String generateUniqueProjectSlug(UUID workspaceId, String baseSlug, UUID excludeProjectId) {
        String candidate = baseSlug;
        int suffix = 1;
        while (slugTaken(workspaceId, candidate, excludeProjectId)) {
            candidate = baseSlug + "-" + suffix;
            suffix++;
            if (suffix > 100) {
                candidate = baseSlug + "-" + UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return candidate;
    }

    private boolean slugTaken(UUID workspaceId, String slug, UUID excludeProjectId) {
        return excludeProjectId == null
                ? projectRepository.existsByWorkspaceIdAndSlug(workspaceId, slug)
                : projectRepository.existsByWorkspaceIdAndSlugAndIdNot(workspaceId, slug, excludeProjectId);
    }

    private ProjectDto toDto(ProjectEntity entity) {
        LinkedRepositoryDto repoDto = entity.getLinkedRepository() != null
                ? gitHubIntegrationService.toRepositoryDto(entity.getLinkedRepository())
                : null;
        return new ProjectDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getName(),
                entity.getSlug(),
                entity.getDescription(),
                repoDto,
                entity.getDefaultBranch(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ProjectDetailDto toDetailDto(ProjectEntity entity, List<ProjectEnvironmentDto> environments) {
        LinkedRepositoryDto repoDto = entity.getLinkedRepository() != null
                ? gitHubIntegrationService.toRepositoryDto(entity.getLinkedRepository())
                : null;
        return new ProjectDetailDto(
                entity.getId(),
                entity.getWorkspace().getId(),
                entity.getWorkspace().getName(),
                entity.getName(),
                entity.getSlug(),
                entity.getDescription(),
                repoDto,
                entity.getDefaultBranch(),
                entity.getStatus(),
                entity.getCreatedBy() != null ? entity.getCreatedBy().getUsername() : null,
                environments,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
