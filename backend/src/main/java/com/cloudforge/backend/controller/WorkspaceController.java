package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.AddWorkspaceMemberRequest;
import com.cloudforge.backend.dto.CreateWorkspaceRequest;
import com.cloudforge.backend.dto.UpdateWorkspaceMemberRoleRequest;
import com.cloudforge.backend.dto.UpdateWorkspaceRequest;
import com.cloudforge.backend.dto.WorkspaceMemberDto;
import com.cloudforge.backend.dto.WorkspaceSummaryDto;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final CurrentUserResolver currentUserResolver;

    public WorkspaceController(WorkspaceService workspaceService, CurrentUserResolver currentUserResolver) {
        this.workspaceService = workspaceService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WorkspaceSummaryDto>> listWorkspaces(Authentication authentication) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workspaceService.listWorkspaces(user));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkspaceSummaryDto> createWorkspace(
            @Valid @RequestBody CreateWorkspaceRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaceService.createWorkspace(request, user));
    }

    @GetMapping(value = "/{workspaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkspaceSummaryDto> getWorkspace(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workspaceService.getWorkspace(workspaceId, user));
    }

    @PatchMapping(
            value = "/{workspaceId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkspaceSummaryDto> updateWorkspace(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody UpdateWorkspaceRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workspaceService.updateWorkspace(workspaceId, request, user));
    }

    @GetMapping(value = "/{workspaceId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WorkspaceMemberDto>> listMembers(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workspaceService.listMembers(workspaceId, user));
    }

    @PostMapping(
            value = "/{workspaceId}/members",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkspaceMemberDto> addMember(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody AddWorkspaceMemberRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workspaceService.addMember(workspaceId, request, user));
    }

    @PatchMapping(
            value = "/{workspaceId}/members/{memberId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkspaceMemberDto> updateMemberRole(
            @PathVariable UUID workspaceId,
            @PathVariable UUID memberId,
            @Valid @RequestBody UpdateWorkspaceMemberRoleRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workspaceService.updateMemberRole(workspaceId, memberId, request, user));
    }

    @DeleteMapping("/{workspaceId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID workspaceId,
            @PathVariable UUID memberId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        workspaceService.removeMember(workspaceId, memberId, user);
        return ResponseEntity.noContent().build();
    }
}
