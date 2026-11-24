package com.cloudforge.backend.orchestration.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.orchestration.dto.CancelPipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.dto.CreatePipelineRunRequest;
import com.cloudforge.backend.orchestration.dto.PipelineDefinitionDto;
import com.cloudforge.backend.orchestration.dto.PipelineRunDto;
import com.cloudforge.backend.orchestration.dto.PipelineRunEventDto;
import com.cloudforge.backend.orchestration.dto.UpdatePipelineDefinitionRequest;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.service.PipelineDefinitionService;
import com.cloudforge.backend.orchestration.service.PipelineOrchestrationService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Secure REST API for CloudForge Phase 3.2 pipeline definitions, DAG runs,
 * idempotent queueing, cancellation, and lifecycle transition history.
 *
 * <p>Deliberately exposes no user-facing endpoint to force stage/run completion
 * or bypass the orchestration state machine.</p>
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/pipelines")
public class PipelineOrchestrationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final PipelineDefinitionService definitionService;
    private final PipelineOrchestrationService orchestrationService;
    private final CurrentUserResolver currentUserResolver;

    public PipelineOrchestrationController(
            PipelineDefinitionService definitionService,
            PipelineOrchestrationService orchestrationService,
            CurrentUserResolver currentUserResolver
    ) {
        this.definitionService = definitionService;
        this.orchestrationService = orchestrationService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/definitions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<PipelineDefinitionDto>> listDefinitions(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "enabled", required = false) Boolean enabled,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt")
        );
        return ResponseEntity.ok(
                definitionService.listDefinitions(workspaceId, projectId, enabled, query, pageable, user)
        );
    }

    @PostMapping(
            value = "/definitions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PipelineDefinitionDto> createDefinition(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreatePipelineDefinitionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(definitionService.createDefinition(workspaceId, request, user));
    }

    @GetMapping(value = "/definitions/{definitionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineDefinitionDto> getDefinition(
            @PathVariable UUID workspaceId,
            @PathVariable UUID definitionId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(definitionService.getDefinition(workspaceId, definitionId, user));
    }

    @PutMapping(
            value = "/definitions/{definitionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PipelineDefinitionDto> updateDefinition(
            @PathVariable UUID workspaceId,
            @PathVariable UUID definitionId,
            @Valid @RequestBody UpdatePipelineDefinitionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(definitionService.updateDefinition(workspaceId, definitionId, request, user));
    }

    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<PipelineRunDto>> listRuns(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "definitionId", required = false) UUID definitionId,
            @RequestParam(name = "status", required = false) PipelineRunStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("runNumber"))
        );
        return ResponseEntity.ok(
                orchestrationService.listRuns(workspaceId, projectId, definitionId, status, query, pageable, user)
        );
    }

    @PostMapping(
            value = "/runs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PipelineRunDto> createRun(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKeyHeader,
            @Valid @RequestBody CreatePipelineRunRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        PipelineOrchestrationService.CreatePipelineRunResult result = orchestrationService.createRun(
                workspaceId,
                request,
                idempotencyKeyHeader,
                user
        );
        if (result.idempotentReplay()) {
            return ResponseEntity.ok()
                    .header(IDEMPOTENT_REPLAY_HEADER, "true")
                    .body(result.run());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IDEMPOTENT_REPLAY_HEADER, "false")
                .body(result.run());
    }

    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineRunDto> getRun(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(orchestrationService.getRun(workspaceId, runId, user));
    }

    @PostMapping(value = "/runs/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineRunDto> cancelRun(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) CancelPipelineRunRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(orchestrationService.cancelRun(workspaceId, runId, request, user));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PipelineRunEventDto>> listRunEvents(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(orchestrationService.listRunEvents(workspaceId, runId, user));
    }
}
