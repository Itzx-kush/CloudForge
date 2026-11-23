package com.cloudforge.backend.service;

import com.cloudforge.backend.dto.AddWorkspaceMemberRequest;
import com.cloudforge.backend.dto.CreateWorkspaceRequest;
import com.cloudforge.backend.dto.UpdateWorkspaceMemberRoleRequest;
import com.cloudforge.backend.dto.UpdateWorkspaceRequest;
import com.cloudforge.backend.dto.WorkspaceMemberDto;
import com.cloudforge.backend.dto.WorkspaceSummaryDto;
import com.cloudforge.backend.entity.RepositoryConnectionStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.repository.LinkedRepositoryRepository;
import com.cloudforge.backend.repository.ProjectRepository;
import com.cloudforge.backend.repository.UserRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final LinkedRepositoryRepository linkedRepositoryRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final UserAccountService userAccountService;
    private final AuditService auditService;

    public WorkspaceService(
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            UserRepository userRepository,
            ProjectRepository projectRepository,
            LinkedRepositoryRepository linkedRepositoryRepository,
            WorkspaceAuthorizationService authorizationService,
            UserAccountService userAccountService,
            AuditService auditService
    ) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.linkedRepositoryRepository = linkedRepositoryRepository;
        this.authorizationService = authorizationService;
        this.userAccountService = userAccountService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummaryDto> listWorkspaces(UserEntity user) {
        return workspaceMemberRepository.findAllByUserIdWithWorkspace(user.getId())
                .stream()
                .map(this::toSummaryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceSummaryDto getWorkspace(UUID workspaceId, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireMembership(workspaceId, user.getId());
        return toSummaryDto(membership);
    }

    @Transactional
    public WorkspaceSummaryDto createWorkspace(CreateWorkspaceRequest request, UserEntity user) {
        String name = request.name().trim();
        String baseSlug = SlugUtils.toSlug(name, "workspace");
        String slug = userAccountService.ensureUniqueWorkspaceSlug(baseSlug);

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(name);
        workspace.setSlug(slug);
        workspace.setDescription(trimToNull(request.description()));
        workspace.setPersonal(false);
        workspace.setCreatedBy(user);
        WorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        WorkspaceMemberEntity ownerMembership = new WorkspaceMemberEntity();
        ownerMembership.setWorkspace(savedWorkspace);
        ownerMembership.setUser(user);
        ownerMembership.setRole(WorkspaceRole.OWNER);
        WorkspaceMemberEntity savedMembership = workspaceMemberRepository.save(ownerMembership);

        auditService.record(
                savedWorkspace,
                user,
                "WORKSPACE_CREATED",
                "WORKSPACE",
                savedWorkspace.getId().toString(),
                "Created workspace \"" + savedWorkspace.getName() + "\"",
                Map.of("slug", savedWorkspace.getSlug())
        );

        return toSummaryDto(savedMembership);
    }

    @Transactional
    public WorkspaceSummaryDto updateWorkspace(UUID workspaceId, UpdateWorkspaceRequest request, UserEntity user) {
        WorkspaceMemberEntity membership = authorizationService.requireOwner(workspaceId, user.getId());
        WorkspaceEntity workspace = membership.getWorkspace();

        workspace.setName(request.name().trim());
        workspace.setDescription(trimToNull(request.description()));
        workspaceRepository.save(workspace);

        auditService.record(
                workspace,
                user,
                "WORKSPACE_UPDATED",
                "WORKSPACE",
                workspace.getId().toString(),
                "Updated workspace settings for \"" + workspace.getName() + "\"",
                Map.of("name", workspace.getName())
        );

        return toSummaryDto(membership);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberDto> listMembers(UUID workspaceId, UserEntity user) {
        authorizationService.requireMembership(workspaceId, user.getId());
        return workspaceMemberRepository.findAllByWorkspaceIdWithUser(workspaceId)
                .stream()
                .map(this::toMemberDto)
                .toList();
    }

    @Transactional
    public WorkspaceMemberDto addMember(UUID workspaceId, AddWorkspaceMemberRequest request, UserEntity actor) {
        WorkspaceMemberEntity actorMembership = authorizationService.requireOwner(workspaceId, actor.getId());
        WorkspaceEntity workspace = actorMembership.getWorkspace();

        String normalizedUsername = request.username().trim().replaceFirst("^@", "");
        UserEntity targetUser = userRepository.findByUsernameIgnoreCase(normalizedUsername)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No CloudForge user found with GitHub username @" + normalizedUsername
                                + ". The user must sign in to CloudForge at least once before being added."
                ));

        if (workspaceMemberRepository.existsByWorkspaceIdAndUserId(workspaceId, targetUser.getId())) {
            throw new ConflictException("@" + targetUser.getUsername() + " is already a member of this workspace");
        }

        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspace(workspace);
        member.setUser(targetUser);
        member.setRole(request.role());
        WorkspaceMemberEntity saved = workspaceMemberRepository.save(member);

        auditService.record(
                workspace,
                actor,
                "MEMBER_ADDED",
                "WORKSPACE_MEMBER",
                saved.getId().toString(),
                "Added @" + targetUser.getUsername() + " as " + request.role(),
                Map.of("targetUsername", targetUser.getUsername(), "role", request.role().name())
        );

        return toMemberDto(saved);
    }

    @Transactional
    public WorkspaceMemberDto updateMemberRole(
            UUID workspaceId,
            UUID memberId,
            UpdateWorkspaceMemberRoleRequest request,
            UserEntity actor
    ) {
        WorkspaceMemberEntity actorMembership = authorizationService.requireOwner(workspaceId, actor.getId());
        WorkspaceMemberEntity target = workspaceMemberRepository.findByIdAndWorkspaceId(memberId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace member not found"));

        if (target.getRole() == WorkspaceRole.OWNER && request.role() != WorkspaceRole.OWNER) {
            long ownerCount = workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.OWNER);
            if (ownerCount <= 1) {
                throw new BadRequestException("Cannot demote the last OWNER of a workspace");
            }
        }

        WorkspaceRole previousRole = target.getRole();
        target.setRole(request.role());
        WorkspaceMemberEntity saved = workspaceMemberRepository.save(target);

        auditService.record(
                actorMembership.getWorkspace(),
                actor,
                "MEMBER_ROLE_UPDATED",
                "WORKSPACE_MEMBER",
                saved.getId().toString(),
                "Changed @" + target.getUser().getUsername() + " role from " + previousRole + " to " + request.role(),
                Map.of(
                        "targetUsername", target.getUser().getUsername(),
                        "previousRole", previousRole.name(),
                        "newRole", request.role().name()
                )
        );

        return toMemberDto(saved);
    }

    @Transactional
    public void removeMember(UUID workspaceId, UUID memberId, UserEntity actor) {
        WorkspaceMemberEntity actorMembership = authorizationService.requireOwner(workspaceId, actor.getId());
        WorkspaceMemberEntity target = workspaceMemberRepository.findByIdAndWorkspaceId(memberId, workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workspace member not found"));

        if (target.getRole() == WorkspaceRole.OWNER) {
            long ownerCount = workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.OWNER);
            if (ownerCount <= 1) {
                throw new BadRequestException("Cannot remove the last OWNER of a workspace");
            }
        }

        String removedUsername = target.getUser().getUsername();
        String removedId = target.getId().toString();
        workspaceMemberRepository.delete(target);

        auditService.record(
                actorMembership.getWorkspace(),
                actor,
                "MEMBER_REMOVED",
                "WORKSPACE_MEMBER",
                removedId,
                "Removed @" + removedUsername + " from workspace",
                Map.of("removedUsername", removedUsername)
        );
    }

    private WorkspaceSummaryDto toSummaryDto(WorkspaceMemberEntity membership) {
        WorkspaceEntity ws = membership.getWorkspace();
        return new WorkspaceSummaryDto(
                ws.getId(),
                ws.getName(),
                ws.getSlug(),
                ws.getDescription(),
                ws.isPersonal(),
                membership.getRole(),
                workspaceMemberRepository.countByWorkspaceId(ws.getId()),
                projectRepository.countByWorkspaceId(ws.getId()),
                linkedRepositoryRepository.countByWorkspaceIdAndConnectionStatus(
                        ws.getId(),
                        RepositoryConnectionStatus.CONNECTED
                ),
                ws.getCreatedAt(),
                ws.getUpdatedAt()
        );
    }

    private WorkspaceMemberDto toMemberDto(WorkspaceMemberEntity entity) {
        UserEntity user = entity.getUser();
        return new WorkspaceMemberDto(
                entity.getId(),
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getAvatarUrl(),
                entity.getRole(),
                entity.getCreatedAt()
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
