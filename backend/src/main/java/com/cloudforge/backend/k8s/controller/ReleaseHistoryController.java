package com.cloudforge.backend.k8s.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
import com.cloudforge.backend.k8s.entity.KubernetesOperationKind;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentService;
import com.cloudforge.backend.k8s.service.ReleaseHistoryService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class ReleaseHistoryController {
    private final CurrentUserResolver users;
    private final ReleaseHistoryService releases;
    private final KubernetesDeploymentService deployments;

    public ReleaseHistoryController(CurrentUserResolver users, ReleaseHistoryService releases, KubernetesDeploymentService deployments) {
        this.users = users;
        this.releases = releases;
        this.deployments = deployments;
    }

    @GetMapping("/releases")
    public ResponseEntity<PagedResponse<ReleaseHistoryService.Release>> list(
            @PathVariable UUID workspaceId, @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId, @RequestParam(required = false) UUID clusterId,
            @RequestParam(required = false) String deployment, @RequestParam(required = false) KubernetesDeploymentStatus status,
            @RequestParam(required = false) KubernetesOperationKind kind, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth
    ) {
        return response(releases.list(workspaceId, users.requireUserEntity(auth).getId(), projectId, environmentId,
                clusterId, deployment, status, kind, page, size));
    }

    @GetMapping({"/releases/{id}", "/rollbacks/{id}"})
    public ResponseEntity<ReleaseHistoryService.Release> detail(
            @PathVariable UUID workspaceId, @PathVariable UUID id, Authentication auth
    ) {
        return response(releases.get(workspaceId, id, users.requireUserEntity(auth).getId()));
    }

    @GetMapping("/rollbacks")
    public ResponseEntity<PagedResponse<ReleaseHistoryService.Release>> rollbacks(
            @PathVariable UUID workspaceId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth
    ) {
        return response(releases.list(workspaceId, users.requireUserEntity(auth).getId(), null, null, null, null,
                null, KubernetesOperationKind.ROLLBACK, page, size));
    }

    @GetMapping("/rollbacks/{id}/events")
    public ResponseEntity<?> events(@PathVariable UUID workspaceId, @PathVariable UUID id, Authentication auth) {
        return response(deployments.listDeploymentEvents(workspaceId, id, users.requireUserEntity(auth).getId()));
    }

    @GetMapping("/releases/compare")
    public ResponseEntity<ReleaseHistoryService.Comparison> compare(
            @PathVariable UUID workspaceId, @RequestParam UUID first, @RequestParam UUID second, Authentication auth
    ) {
        return response(releases.compare(workspaceId, first, second, users.requireUserEntity(auth).getId()));
    }

    @GetMapping("/releases/{id}/rollback-eligibility")
    public ResponseEntity<ReleaseHistoryService.Preview> eligibility(
            @PathVariable UUID workspaceId, @PathVariable UUID id, Authentication auth
    ) {
        return preview(workspaceId, id, auth);
    }

    @PostMapping("/releases/{id}/rollback-preview")
    public ResponseEntity<ReleaseHistoryService.Preview> preview(
            @PathVariable UUID workspaceId, @PathVariable UUID id, Authentication auth
    ) {
        return response(releases.preview(workspaceId, id, users.requireUserEntity(auth).getId()));
    }

    @PostMapping("/releases/{id}/rollback")
    public ResponseEntity<ReleaseHistoryService.Release> rollback(
            @PathVariable UUID workspaceId, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RollbackRequest request, Authentication auth
    ) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(releases.requestRollback(
                workspaceId, id, users.requireUserEntity(auth), key, request.expectedOperationId(),
                request.reason(), request.confirmed()));
    }

    @PostMapping("/deployments/{id}/reconcile")
    public ResponseEntity<ReleaseHistoryService.Release> reconcile(
            @PathVariable UUID workspaceId, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) ReconciliationRequest request, Authentication auth
    ) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(releases.reconcile(
                workspaceId, id, request == null ? null : request.candidateReleaseId(), users.requireUserEntity(auth), key));
    }

    private static <T> ResponseEntity<T> response(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    public record RollbackRequest(@NotNull UUID expectedOperationId, @Size(min = 8, max = 128) String reason,
                                  boolean confirmed) {}

    public record ReconciliationRequest(UUID candidateReleaseId) {}
}
