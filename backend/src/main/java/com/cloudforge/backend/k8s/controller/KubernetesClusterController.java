package com.cloudforge.backend.k8s.controller;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.k8s.dto.CreateClusterConnectionRequest;
import com.cloudforge.backend.k8s.dto.CreateNamespaceBindingRequest;
import com.cloudforge.backend.k8s.dto.KubernetesClusterConnectionDto;
import com.cloudforge.backend.k8s.dto.KubernetesNamespaceBindingDto;
import com.cloudforge.backend.k8s.dto.KubernetesSubsystemStatusDto;
import com.cloudforge.backend.k8s.dto.UpdateClusterConnectionRequest;
import com.cloudforge.backend.k8s.service.KubernetesClusterService;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped REST endpoints for Kubernetes cluster connections, validation requests,
 * approved namespace bindings, and Kubernetes deployment subsystem status.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class KubernetesClusterController {

    private final CurrentUserResolver currentUserResolver;
    private final KubernetesClusterService clusterService;
    private final KubernetesDeploymentService deploymentService;

    public KubernetesClusterController(
            CurrentUserResolver currentUserResolver,
            KubernetesClusterService clusterService,
            KubernetesDeploymentService deploymentService
    ) {
        this.currentUserResolver = currentUserResolver;
        this.clusterService = clusterService;
        this.deploymentService = deploymentService;
    }

    @GetMapping("/kubernetes/status")
    public ResponseEntity<KubernetesSubsystemStatusDto> getSubsystemStatus(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deploymentService.getSubsystemStatus(workspaceId, user.getId()));
    }

    @GetMapping("/clusters")
    public ResponseEntity<List<KubernetesClusterConnectionDto>> listClusters(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.listClusters(workspaceId, user.getId()));
    }

    @PostMapping("/clusters")
    public ResponseEntity<KubernetesClusterConnectionDto> createCluster(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateClusterConnectionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        KubernetesClusterConnectionDto created = clusterService.createCluster(workspaceId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping("/clusters/{clusterId}")
    public ResponseEntity<KubernetesClusterConnectionDto> getCluster(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.getCluster(workspaceId, clusterId, user.getId()));
    }

    @PutMapping("/clusters/{clusterId}")
    public ResponseEntity<KubernetesClusterConnectionDto> updateCluster(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            @Valid @RequestBody UpdateClusterConnectionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.updateCluster(workspaceId, clusterId, request, user));
    }

    @PostMapping("/clusters/{clusterId}/disable")
    public ResponseEntity<KubernetesClusterConnectionDto> disableCluster(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.disableCluster(workspaceId, clusterId, user));
    }

    @PostMapping("/clusters/{clusterId}/validate")
    public ResponseEntity<KubernetesClusterConnectionDto> validateCluster(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        KubernetesClusterConnectionDto updated = clusterService.requestClusterValidation(workspaceId, clusterId, user);
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(updated);
    }

    @GetMapping("/clusters/{clusterId}/namespaces")
    public ResponseEntity<List<KubernetesNamespaceBindingDto>> listClusterNamespaces(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.listClusterNamespaces(workspaceId, clusterId, user.getId()));
    }

    @PostMapping("/clusters/{clusterId}/namespaces")
    public ResponseEntity<KubernetesNamespaceBindingDto> createNamespaceBinding(
            @PathVariable UUID workspaceId,
            @PathVariable UUID clusterId,
            @Valid @RequestBody CreateNamespaceBindingRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        KubernetesNamespaceBindingDto created = clusterService.createNamespaceBinding(workspaceId, clusterId, request, user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping("/namespaces")
    public ResponseEntity<List<KubernetesNamespaceBindingDto>> listWorkspaceNamespaces(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(clusterService.listWorkspaceNamespaces(workspaceId, user.getId()));
    }
}
