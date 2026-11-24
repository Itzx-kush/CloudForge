package com.cloudforge.backend.worker.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.security.CurrentUserResolver;
import com.cloudforge.backend.worker.dto.StageExecutionAttemptDto;
import com.cloudforge.backend.worker.dto.StageExecutionLogDto;
import com.cloudforge.backend.worker.dto.WorkerPoolStatusDto;
import com.cloudforge.backend.worker.service.WorkerExecutionLogService;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.cloudforge.backend.worker.service.WorkerResultProcessorService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only workspace-scoped REST API exposing real persisted worker pool telemetry,
 * stage execution attempts, and bounded sanitized execution logs to authorized workspace members.
 *
 * <p>Deliberately read-only: ordinary workspace users cannot mutate worker state, claim leases,
 * or submit completion results.</p>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/pipelines")
public class WorkspaceExecutionController {

    private final WorkerRegistryService workerRegistryService;
    private final WorkerResultProcessorService resultProcessorService;
    private final WorkerExecutionLogService executionLogService;
    private final CurrentUserResolver currentUserResolver;

    public WorkspaceExecutionController(
            WorkerRegistryService workerRegistryService,
            WorkerResultProcessorService resultProcessorService,
            WorkerExecutionLogService executionLogService,
            CurrentUserResolver currentUserResolver
    ) {
        this.workerRegistryService = workerRegistryService;
        this.resultProcessorService = resultProcessorService;
        this.executionLogService = executionLogService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/workers/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkerPoolStatusDto> getWorkerPoolStatus(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(workerRegistryService.getWorkspacePoolStatus(workspaceId, user.getId()));
    }

    @GetMapping(
            value = "/runs/{runId}/stages/{stageRunId}/attempts",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<List<StageExecutionAttemptDto>> listStageAttempts(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            @PathVariable UUID stageRunId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(
                resultProcessorService.listWorkspaceStageAttempts(workspaceId, runId, stageRunId, user.getId())
        );
    }

    @GetMapping(
            value = "/runs/{runId}/stages/{stageRunId}/logs",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PagedResponse<StageExecutionLogDto>> listStageLogs(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            @PathVariable UUID stageRunId,
            @RequestParam(name = "attemptNumber", required = false) Integer attemptNumber,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "100") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        return ResponseEntity.ok(
                executionLogService.listWorkspaceStageLogs(
                        workspaceId, runId, stageRunId, attemptNumber, pageable, user.getId()
                )
        );
    }
}
