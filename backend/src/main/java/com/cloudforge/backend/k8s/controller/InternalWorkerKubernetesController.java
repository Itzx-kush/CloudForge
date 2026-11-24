package com.cloudforge.backend.k8s.controller;

import com.cloudforge.backend.k8s.dto.WorkerKubernetesDtos;
import com.cloudforge.backend.k8s.service.ClusterCredentialResolver;
import com.cloudforge.backend.k8s.service.KubernetesDeploymentLifecycleService;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated internal worker endpoints exclusively for trusted {@code KUBERNETES_DEPLOY}
 * workers protected by the stateless {@code ROLE_TRUSTED_WORKER} security filter chain.
 */
@RestController
@RequestMapping("/api/internal/workers/{workerId}")
public class InternalWorkerKubernetesController {

    private final KubernetesDeploymentLifecycleService lifecycleService;
    private final ClusterCredentialResolver credentialResolver;

    public InternalWorkerKubernetesController(
            KubernetesDeploymentLifecycleService lifecycleService,
            ClusterCredentialResolver credentialResolver
    ) {
        this.lifecycleService = lifecycleService;
        this.credentialResolver = credentialResolver;
    }

    @PostMapping(value = "/k8s/cluster-validations/claim", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkerKubernetesDtos.ClusterValidationTask> claimClusterValidation(
            @PathVariable String workerId,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return lifecycleService.claimNextClusterValidation(workerId, principal)
                .map(task -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(task))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(
            value = "/k8s/cluster-validations/{clusterId}/credentials",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerKubernetesDtos.ClusterCredentialsResponse> fetchClusterValidationCredentials(
            @PathVariable String workerId,
            @PathVariable UUID clusterId,
            @Valid @RequestBody WorkerKubernetesDtos.ClusterValidationNonceRequest request,
            HttpServletRequest httpRequest,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(credentialResolver.resolveForClusterValidation(workerId, clusterId, request, principal, httpRequest));
    }

    @PostMapping(
            value = "/k8s/cluster-validations/{clusterId}/complete",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> completeClusterValidation(
            @PathVariable String workerId,
            @PathVariable UUID clusterId,
            @Valid @RequestBody WorkerKubernetesDtos.ClusterValidationResultRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        lifecycleService.completeClusterValidation(workerId, clusterId, request, principal);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(
            value = "/attempts/{attemptId}/k8s-descriptor",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerKubernetesDtos.DeploymentExecutionDescriptor> fetchDeploymentDescriptor(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerKubernetesDtos.LeaseRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.resolveExecutionDescriptor(workerId, attemptId, request, principal));
    }

    @PostMapping(
            value = "/attempts/{attemptId}/k8s-credentials",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerKubernetesDtos.ClusterCredentialsResponse> fetchDeploymentCredentials(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerKubernetesDtos.LeaseRequest request,
            HttpServletRequest httpRequest,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(credentialResolver.resolveForDeploymentAttempt(workerId, attemptId, request, principal, httpRequest));
    }

    @PostMapping(
            value = "/attempts/{attemptId}/k8s-status",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> updateDeploymentStatus(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerKubernetesDtos.DeploymentStatusUpdateRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        lifecycleService.recordStatusUpdate(workerId, attemptId, request, principal);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(
            value = "/attempts/{attemptId}/k8s-result",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> reportDeploymentResult(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerKubernetesDtos.DeploymentResultReportRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        lifecycleService.reportDeploymentResult(workerId, attemptId, request, principal);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static WorkerPrincipal requireWorker(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WorkerPrincipal principal)) {
            throw new IllegalStateException("Trusted WorkerPrincipal missing from security context");
        }
        return principal;
    }
}
