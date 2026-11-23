package com.cloudforge.backend.service;

import com.cloudforge.backend.entity.EnvironmentType;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceAuthorizationService {

    private final WorkspaceMemberRepository workspaceMemberRepository;

    public WorkspaceAuthorizationService(WorkspaceMemberRepository workspaceMemberRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional(readOnly = true)
    public WorkspaceMemberEntity requireMembership(UUID workspaceId, UUID userId) {
        return workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new ForbiddenOperationException(
                        "You do not have access to this workspace"
                ));
    }

    @Transactional(readOnly = true)
    public WorkspaceMemberEntity requireRole(UUID workspaceId, UUID userId, WorkspaceRole first, WorkspaceRole... rest) {
        WorkspaceMemberEntity membership = requireMembership(workspaceId, userId);
        Set<WorkspaceRole> allowed = EnumSet.of(first, rest);
        if (!allowed.contains(membership.getRole())) {
            throw new ForbiddenOperationException(
                    "Insufficient workspace role (" + membership.getRole() + ") for this operation"
            );
        }
        return membership;
    }

    @Transactional(readOnly = true)
    public WorkspaceMemberEntity requireOwner(UUID workspaceId, UUID userId) {
        return requireRole(workspaceId, userId, WorkspaceRole.OWNER);
    }

    @Transactional(readOnly = true)
    public WorkspaceMemberEntity requireDeveloperOrAbove(UUID workspaceId, UUID userId) {
        return requireRole(workspaceId, userId, WorkspaceRole.OWNER, WorkspaceRole.DEVELOPER);
    }

    @Transactional(readOnly = true)
    public WorkspaceMemberEntity requireEnvironmentWriteAccess(
            UUID workspaceId,
            UUID userId,
            EnvironmentType environmentType
    ) {
        WorkspaceMemberEntity membership = requireMembership(workspaceId, userId);
        if (membership.getRole() == WorkspaceRole.OWNER) {
            return membership;
        }
        if (membership.getRole() == WorkspaceRole.DEVELOPER && environmentType == EnvironmentType.DEVELOPMENT) {
            return membership;
        }
        throw new ForbiddenOperationException(
                "Role " + membership.getRole() + " is not authorized to modify " + environmentType + " environments"
        );
    }
}
