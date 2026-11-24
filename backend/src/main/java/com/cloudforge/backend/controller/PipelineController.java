package com.cloudforge.backend.controller;

import com.cloudforge.backend.dto.GitHubActionsSyncResponse;
import com.cloudforge.backend.dto.GitHubWorkflowDto;
import com.cloudforge.backend.dto.GitHubWorkflowRunDto;
import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.dto.PipelineOverviewResponse;
import com.cloudforge.backend.dto.WorkflowRunJobsResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.entity.WorkflowRunNormalizedStatus;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.service.GitHubActionsSyncService;
import com.cloudforge.backend.service.PipelineService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class PipelineController {

    private final PipelineService pipelineService;
    private final GitHubActionsSyncService syncService;
    private final CurrentUserResolver currentUserResolver;

    public PipelineController(
            PipelineService pipelineService,
            GitHubActionsSyncService syncService,
            CurrentUserResolver currentUserResolver
    ) {
        this.pipelineService = pipelineService;
        this.syncService = syncService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/pipelines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineOverviewResponse> getPipelineOverview(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(pipelineService.getOverview(workspaceId, user));
    }

    @GetMapping(value = "/repositories/{repositoryId}/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<GitHubWorkflowDto>> listRepositoryWorkflows(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "name"));
        return ResponseEntity.ok(
                pipelineService.listRepositoryWorkflows(workspaceId, repositoryId, query, state, pageable, user)
        );
    }

    @PostMapping(value = "/repositories/{repositoryId}/workflows/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GitHubActionsSyncResponse> syncRepositoryWorkflows(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(syncService.syncRepositoryWorkflows(workspaceId, repositoryId, user));
    }

    @GetMapping(value = "/repositories/{repositoryId}/workflow-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<GitHubWorkflowRunDto>> listRepositoryWorkflowRuns(
            @PathVariable UUID workspaceId,
            @PathVariable UUID repositoryId,
            @RequestParam(name = "workflowId", required = false) UUID workflowId,
            @RequestParam(name = "status", required = false) WorkflowRunNormalizedStatus status,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = buildRunPageable(page, size);
        return ResponseEntity.ok(
                pipelineService.listRepositoryWorkflowRuns(
                        workspaceId, repositoryId, workflowId, status, branch, query, pageable, user
                )
        );
    }

    @GetMapping(value = "/workflow-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<GitHubWorkflowRunDto>> listWorkspaceWorkflowRuns(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "repositoryId", required = false) UUID repositoryId,
            @RequestParam(name = "workflowId", required = false) UUID workflowId,
            @RequestParam(name = "status", required = false) WorkflowRunNormalizedStatus status,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = buildRunPageable(page, size);
        return ResponseEntity.ok(
                pipelineService.listWorkspaceWorkflowRuns(
                        workspaceId, repositoryId, workflowId, status, branch, query, pageable, user
                )
        );
    }

    @GetMapping(value = "/workflow-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GitHubWorkflowRunDto> getWorkflowRun(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(pipelineService.getWorkflowRun(workspaceId, runId, user));
    }

    @GetMapping(value = "/workflow-runs/{runId}/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowRunJobsResponse> listWorkflowRunJobs(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(syncService.fetchWorkflowRunJobs(workspaceId, runId, user));
    }

    private Pageable buildRunPageable(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return PageRequest.of(
                safePage,
                safeSize,
                Sort.by(
                        Sort.Order.desc("githubCreatedAt"),
                        Sort.Order.desc("runNumber"),
                        Sort.Order.desc("runAttempt")
                )
        );
    }
}
