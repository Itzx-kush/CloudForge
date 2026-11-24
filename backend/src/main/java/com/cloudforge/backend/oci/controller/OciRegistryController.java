package com.cloudforge.backend.oci.controller;

import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.oci.dto.CreateRegistryConnectionRequest;
import com.cloudforge.backend.oci.dto.OciRegistryConnectionDto;
import com.cloudforge.backend.oci.dto.OciSubsystemStatusDto;
import com.cloudforge.backend.oci.dto.UpdateRegistryConnectionRequest;
import com.cloudforge.backend.oci.service.ArtifactQueryService;
import com.cloudforge.backend.oci.service.RegistryConnectionService;
import com.cloudforge.backend.security.CurrentUserResolver;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Workspace-scoped REST API for OCI registry connections (OWNER mutations, member reads)
 * and OCI subsystem status. Never returns plaintext registry credentials.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class OciRegistryController {

    private final RegistryConnectionService registryConnectionService;
    private final ArtifactQueryService artifactQueryService;
    private final CurrentUserResolver currentUserResolver;

    public OciRegistryController(
            RegistryConnectionService registryConnectionService,
            ArtifactQueryService artifactQueryService,
            CurrentUserResolver currentUserResolver
    ) {
        this.registryConnectionService = registryConnectionService;
        this.artifactQueryService = artifactQueryService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/oci/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OciSubsystemStatusDto> getOciStatus(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(artifactQueryService.getWorkspaceOciStatus(workspaceId, user));
    }

    @GetMapping(value = "/registries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<OciRegistryConnectionDto>> listRegistries(
            @PathVariable UUID workspaceId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registryConnectionService.listConnections(workspaceId, user));
    }

    @GetMapping(value = "/registries/{registryId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OciRegistryConnectionDto> getRegistry(
            @PathVariable UUID workspaceId,
            @PathVariable UUID registryId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registryConnectionService.getConnection(workspaceId, registryId, user));
    }

    @PostMapping(
            value = "/registries",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<OciRegistryConnectionDto> createRegistry(
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateRegistryConnectionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(registryConnectionService.createConnection(workspaceId, request, user));
    }

    @PutMapping(
            value = "/registries/{registryId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<OciRegistryConnectionDto> updateRegistry(
            @PathVariable UUID workspaceId,
            @PathVariable UUID registryId,
            @Valid @RequestBody UpdateRegistryConnectionRequest request,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registryConnectionService.updateConnection(workspaceId, registryId, request, user));
    }

    @PostMapping(value = "/registries/{registryId}/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OciRegistryConnectionDto> disableRegistry(
            @PathVariable UUID workspaceId,
            @PathVariable UUID registryId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registryConnectionService.disableConnection(workspaceId, registryId, user));
    }
}
