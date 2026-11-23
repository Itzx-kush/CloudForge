package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.CreateEnvironmentRequest;
import com.cloudforge.backend.dto.CreateProjectRequest;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.dto.ProjectDetailDto;
import com.cloudforge.backend.dto.ProjectDto;
import com.cloudforge.backend.dto.ProjectEnvironmentDto;
import com.cloudforge.backend.dto.UpdateEnvironmentRequest;
import com.cloudforge.backend.dto.UpdateProjectRequest;
import com.cloudforge.backend.entity.ProjectStatus;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.service.ProjectEnvironmentService;
import com.cloudforge.backend.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectEnvironmentService environmentService;
    private final CurrentUserResolver currentUserResolver;

    public ProjectController(
            ProjectService projectService,
            ProjectEnvironmentService environmentService,
            CurrentUserResolver currentUserResolver
    ) {
        this.projectService = projectService;
        this.environmentService = environmentService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<ProjectDto>> listProjects(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "status", required = false) ProjectStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return ResponseEntity.ok(projectService.listProjects(workspaceId, query, status, pageable, user));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectDetailDto> createProject(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createProject(workspaceId, request, user));
    }

    @GetMapping(value = "/{projectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProjectDetailDto> getProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(projectService.getProjectDetail(workspaceId, projectId, user));
    }

    @PutMapping(
            value = "/{projectId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ProjectDetailDto> updateProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(projectService.updateProject(workspaceId, projectId, request, user));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        projectService.deleteProject(workspaceId, projectId, user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{projectId}/environments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ProjectEnvironmentDto>> listEnvironments(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(environmentService.listEnvironments(workspaceId, projectId, user));
    }

    @PostMapping(
            value = "/{projectId}/environments",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ProjectEnvironmentDto> createEnvironment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateEnvironmentRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(environmentService.createEnvironment(workspaceId, projectId, request, user));
    }

    @PutMapping(
            value = "/{projectId}/environments/{environmentId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ProjectEnvironmentDto> updateEnvironment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @Valid @RequestBody UpdateEnvironmentRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(
                environmentService.updateEnvironment(workspaceId, projectId, environmentId, request, user)
        );
    }

    @DeleteMapping("/{projectId}/environments/{environmentId}")
    public ResponseEntity<Void> deleteEnvironment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        environmentService.deleteEnvironment(workspaceId, projectId, environmentId, user);
        return ResponseEntity.noContent().build();
    }
}
