package com.cloudforge.backend.worker.controller;

import com.cloudforge.backend.orchestration.engine.WorkerAssignmentContract;
import com.cloudforge.backend.worker.dto.AppendExecutionLogsRequest;
import com.cloudforge.backend.worker.dto.AppendExecutionLogsResponse;
import com.cloudforge.backend.worker.dto.AttemptRunningAckRequest;
import com.cloudforge.backend.worker.dto.CompleteAttemptRequest;
import com.cloudforge.backend.worker.dto.StageExecutionAttemptDto;
import com.cloudforge.backend.worker.dto.WorkerHeartbeatRequest;
import com.cloudforge.backend.worker.dto.WorkerHeartbeatResponse;
import com.cloudforge.backend.worker.dto.WorkerNodeDto;
import com.cloudforge.backend.worker.dto.WorkerRegistrationRequest;
import com.cloudforge.backend.worker.dto.WorkerStatusUpdateRequest;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.WorkerExecutionLogService;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.cloudforge.backend.worker.service.WorkerResultProcessorService;
import com.cloudforge.backend.worker.service.WorkerSchedulerService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal control-plane API exclusively for authenticated trusted workers
 * ({@code ROLE_TRUSTED_WORKER} via dedicated stateless {@code WorkerAuthenticationFilter}).
 *
 * <p>Never accessible to browser sessions or ordinary workspace users.</p>
 */
@RestController
@RequestMapping("/api/internal/workers")
public class InternalWorkerController {

    private final WorkerRegistryService registryService;
    private final WorkerSchedulerService schedulerService;
    private final WorkerExecutionLogService logService;
    private final WorkerResultProcessorService resultProcessorService;

    public InternalWorkerController(
            WorkerRegistryService registryService,
            WorkerSchedulerService schedulerService,
            WorkerExecutionLogService logService,
            WorkerResultProcessorService resultProcessorService
    ) {
        this.registryService = registryService;
        this.schedulerService = schedulerService;
        this.logService = logService;
        this.resultProcessorService = resultProcessorService;
    }

    @PostMapping(
            value = "/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerNodeDto> register(
            @Valid @RequestBody WorkerRegistrationRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(registryService.registerWorker(request, principal));
    }

    @PostMapping(
            value = "/{workerId}/heartbeat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerHeartbeatResponse> heartbeat(
            @PathVariable String workerId,
            @Valid @RequestBody(required = false) WorkerHeartbeatRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(registryService.processHeartbeat(workerId, request, principal));
    }

    @PostMapping(value = "/{workerId}/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkerAssignmentContract.LeasedStageWork> claimNextStage(
            @PathVariable String workerId,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        Optional<WorkerAssignmentContract.LeasedStageWork> claimed =
                schedulerService.claimNextEligibleStage(workerId, principal);
        return claimed.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(
            value = "/{workerId}/attempts/{attemptId}/running",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<StageExecutionAttemptDto> acknowledgeRunning(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody AttemptRunningAckRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(
                resultProcessorService.acknowledgeAttemptRunning(workerId, attemptId, request, principal)
        );
    }

    @PostMapping(
            value = "/{workerId}/attempts/{attemptId}/logs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<AppendExecutionLogsResponse> appendLogs(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody AppendExecutionLogsRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(logService.appendWorkerLogs(workerId, attemptId, request, principal));
    }

    @PostMapping(
            value = "/{workerId}/attempts/{attemptId}/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<StageExecutionAttemptDto> completeAttempt(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody CompleteAttemptRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(
                resultProcessorService.completeAttempt(workerId, attemptId, request, principal)
        );
    }

    @PostMapping(
            value = "/{workerId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerNodeDto> updateStatus(
            @PathVariable String workerId,
            @Valid @RequestBody WorkerStatusUpdateRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorkerPrincipal(authentication);
        return ResponseEntity.ok(registryService.updateWorkerStatus(workerId, request.status(), principal));
    }

    private static WorkerPrincipal requireWorkerPrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WorkerPrincipal principal)) {
            throw new IllegalStateException("Trusted WorkerPrincipal missing from security context");
        }
        return principal;
    }
}
