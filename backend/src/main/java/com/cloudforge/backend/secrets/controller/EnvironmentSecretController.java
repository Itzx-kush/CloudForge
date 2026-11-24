package com.cloudforge.backend.secrets.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.secrets.dto.CreateEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretDetailDto;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretSummaryDto;
import com.cloudforge.backend.secrets.dto.EnvironmentSecretVersionDto;
import com.cloudforge.backend.secrets.dto.RevokeEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.RotateEnvironmentSecretRequest;
import com.cloudforge.backend.secrets.dto.SecretStatusResponse;
import com.cloudforge.backend.secrets.entity.SecretLifecycleStatus;
import com.cloudforge.backend.secrets.service.SecretManagementService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Write-only environment secret management REST API.
 * Enforces {@code Cache-Control: no-store} on all secret endpoints and never
 * returns plaintext secret values through any browser/user route.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class EnvironmentSecretController {

    private final SecretManagementService secretManagementService;
    private final CurrentUserResolver currentUserResolver;

    public EnvironmentSecretController(
            SecretManagementService secretManagementService,
            CurrentUserResolver currentUserResolver
    ) {
        this.secretManagementService = secretManagementService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/secrets/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SecretStatusResponse> getSecretSubsystemStatus(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.getWorkspaceSecretStatus(workspaceId, user));
    }

    @GetMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PagedResponse<EnvironmentSecretSummaryDto>> listSecrets(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @RequestParam(name = "status", required = false) SecretLifecycleStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.ASC, "secretName")
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.listSecrets(
                        workspaceId, projectId, environmentId, status, query, pageable, user
                ));
    }

    @PostMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<EnvironmentSecretSummaryDto> createSecret(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @Valid @RequestBody CreateEnvironmentSecretRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        EnvironmentSecretSummaryDto created = secretManagementService.createSecret(
                workspaceId, projectId, environmentId, request, user
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets/{secretId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<EnvironmentSecretDetailDto> getSecretDetail(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID secretId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.getSecretDetail(workspaceId, projectId, environmentId, secretId, user));
    }

    @GetMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets/{secretId}/versions",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<List<EnvironmentSecretVersionDto>> listSecretVersions(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID secretId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.listSecretVersions(workspaceId, projectId, environmentId, secretId, user));
    }

    @PostMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets/{secretId}/rotate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<EnvironmentSecretSummaryDto> rotateSecret(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID secretId,
            @Valid @RequestBody RotateEnvironmentSecretRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.rotateSecret(workspaceId, projectId, environmentId, secretId, request, user));
    }

    @PostMapping(
            value = "/projects/{projectId}/environments/{environmentId}/secrets/{secretId}/revoke",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<EnvironmentSecretSummaryDto> revokeSecret(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID secretId,
            @Valid @RequestBody(required = false) RevokeEnvironmentSecretRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(secretManagementService.revokeSecret(workspaceId, projectId, environmentId, secretId, request, user));
    }

    @DeleteMapping("/projects/{projectId}/environments/{environmentId}/secrets/{secretId}")
    public ResponseEntity<Void> deleteSecret(
            @PathVariable UUID workspaceId,
            @PathVariable UUID projectId,
            @PathVariable UUID environmentId,
            @PathVariable UUID secretId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        secretManagementService.deleteSecret(workspaceId, projectId, environmentId, secretId, user);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
