package com.cloudforge.backend.k8s.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.k8s.dto.CancelDeploymentRequest;
import com.cloudforge.backend.k8s.dto.CreateDeploymentRequest;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentConfigDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentEventDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentOperationDto;
import com.cloudforge.backend.k8s.dto.KubernetesDeploymentStatusDto;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped REST endpoints for declarative Kubernetes deployment operations,
 * live rollout status inspection, lifecycle events, cancellation, and saved configs.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class KubernetesDeploymentController {

    private final CurrentUserResolver currentUserResolver;
    private final KubernetesDeploymentService deploymentService;

    public KubernetesDeploymentController(
            CurrentUserResolver currentUserResolver,
            KubernetesDeploymentService deploymentService
    ) {
        this.currentUserResolver = currentUserResolver;
        this.deploymentService = deploymentService;
    }

    @GetMapping("/deployments")
    public ResponseEntity<PagedResponse<KubernetesDeploymentOperationDto>> listDeployments(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId,
            @RequestParam(required = false) UUID clusterId,
            @RequestParam(required = false) KubernetesDeploymentStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.listDeployments(
                        workspaceId,
                        projectId,
                        environmentId,
                        clusterId,
                        status,
                        query,
                        PageRequest.of(boundedPage, boundedSize),
                        user.getId()
                ));
    }

    @PostMapping("/deployments")
    public ResponseEntity<KubernetesDeploymentOperationDto> createDeployment(
            @PathVariable UUID workspaceId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDeploymentRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        KubernetesDeploymentService.CreateDeploymentResult result =
                deploymentService.createDeployment(workspaceId, request, idempotencyKey, user);
        ResponseEntity.BodyBuilder builder = result.idempotentReplay()
                ? ResponseEntity.ok().header("X-Idempotent-Replay", "true")
                : ResponseEntity.status(HttpStatus.CREATED);
        return builder.cacheControl(CacheControl.noStore()).body(result.deployment());
    }

    @GetMapping("/deployments/{deploymentId}")
    public ResponseEntity<KubernetesDeploymentOperationDto> getDeployment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID deploymentId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.getDeployment(workspaceId, deploymentId, user.getId()));
    }

    @GetMapping("/deployments/{deploymentId}/events")
    public ResponseEntity<List<KubernetesDeploymentEventDto>> listDeploymentEvents(
            @PathVariable UUID workspaceId,
            @PathVariable UUID deploymentId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.listDeploymentEvents(workspaceId, deploymentId, user.getId()));
    }

    @GetMapping("/deployments/{deploymentId}/status")
    public ResponseEntity<KubernetesDeploymentStatusDto> getDeploymentStatus(
            @PathVariable UUID workspaceId,
            @PathVariable UUID deploymentId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.getDeploymentStatus(workspaceId, deploymentId, user.getId()));
    }

    @PostMapping("/deployments/{deploymentId}/cancel")
    public ResponseEntity<KubernetesDeploymentOperationDto> cancelDeployment(
            @PathVariable UUID workspaceId,
            @PathVariable UUID deploymentId,
            @Valid @RequestBody(required = false) CancelDeploymentRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.cancelDeployment(workspaceId, deploymentId, request, user));
    }

    @GetMapping("/deployment-configs")
    public ResponseEntity<List<KubernetesDeploymentConfigDto>> listDeploymentConfigs(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.listDeploymentConfigs(workspaceId, user.getId()));
    }
}
