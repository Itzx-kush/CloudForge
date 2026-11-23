package com.cloudforge.backend.service;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkspaceEntity;
import com.cloudforge.backend.entity.WorkspaceMemberEntity;
import com.cloudforge.backend.entity.WorkspaceRole;
import com.cloudforge.backend.repository.UserRepository;
import com.cloudforge.backend.repository.WorkspaceMemberRepository;
import com.cloudforge.backend.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAccountService {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AuditService auditService;

    public UserAccountService(
            UserRepository userRepository,
            WorkspaceRepository workspaceRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            AuditService auditService
    ) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.auditService = auditService;
    }

    @Transactional
    public UserEntity upsertFromGitHubOAuth(
            Long githubUserId,
            String username,
            String displayName,
            String email,
            String avatarUrl,
            String profileUrl
    ) {
        Instant now = Instant.now();
        boolean isNewUser = false;

        UserEntity user = userRepository.findByGithubUserId(githubUserId).orElse(null);
        if (user == null) {
            user = new UserEntity();
            user.setGithubUserId(githubUserId);
            isNewUser = true;
        }

        user.setUsername(username);
        user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username);
        if (email != null && !email.isBlank()) {
            user.setEmail(email);
        }
        user.setAvatarUrl(avatarUrl);
        user.setProfileUrl(profileUrl);
        user.setLastLoginAt(now);

        UserEntity savedUser = userRepository.save(user);

        List<WorkspaceMemberEntity> memberships = workspaceMemberRepository.findAllByUserIdWithWorkspace(savedUser.getId());
        WorkspaceEntity auditWorkspace;
        if (memberships.isEmpty()) {
            auditWorkspace = provisionPersonalWorkspace(savedUser);
        } else {
            auditWorkspace = memberships.get(0).getWorkspace();
        }

        auditService.record(
                auditWorkspace,
                savedUser,
                isNewUser ? "USER_REGISTERED" : "USER_LOGIN",
                "USER",
                savedUser.getId().toString(),
                isNewUser
                        ? "Created CloudForge account and personal workspace via GitHub OAuth for @" + savedUser.getUsername()
                        : "Authenticated via GitHub OAuth as @" + savedUser.getUsername(),
                Map.of(
                        "githubUserId", savedUser.getGithubUserId(),
                        "username", savedUser.getUsername(),
                        "firstLogin", isNewUser
                )
        );

        return savedUser;
    }

    private WorkspaceEntity provisionPersonalWorkspace(UserEntity user) {
        String baseSlug = SlugUtils.toSlug(user.getUsername(), "workspace");
        String uniqueSlug = ensureUniqueWorkspaceSlug(baseSlug);

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(user.getUsername() + "'s Workspace");
        workspace.setSlug(uniqueSlug);
        workspace.setDescription("Personal CloudForge workspace for @" + user.getUsername());
        workspace.setPersonal(true);
        workspace.setCreatedBy(user);
        WorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        WorkspaceMemberEntity membership = new WorkspaceMemberEntity();
        membership.setWorkspace(savedWorkspace);
        membership.setUser(user);
        membership.setRole(WorkspaceRole.OWNER);
        workspaceMemberRepository.save(membership);

        return savedWorkspace;
    }

    public String ensureUniqueWorkspaceSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 1;
        while (workspaceRepository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + suffix;
            suffix++;
            if (suffix > 100) {
                candidate = baseSlug + "-" + UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return candidate;
    }
}
