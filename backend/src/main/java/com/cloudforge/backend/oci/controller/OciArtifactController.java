package com.cloudforge.backend.oci.controller;

import com.cloudforge.backend.dto.PagedResponse;
import com.cloudforge.backend.entity.UserEntity;
import com.cloudforge.backend.oci.dto.OciBuildRecordDto;
import com.cloudforge.backend.oci.dto.OciImageArtifactDetailDto;
import com.cloudforge.backend.oci.dto.OciImageArtifactDto;
import com.cloudforge.backend.oci.dto.PipelineRunArtifactsResponse;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import com.cloudforge.backend.oci.service.ArtifactQueryService;
import com.cloudforge.backend.security.CurrentUserResolver;
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

import java.util.UUID;

/**
 * Read-only workspace-isolated REST API exposing OCI build history, immutable digest-verified
 * container image artifacts, publish attempts, and lifecycle events.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class OciArtifactController {

    private final ArtifactQueryService artifactQueryService;
    private final CurrentUserResolver currentUserResolver;

    public OciArtifactController(
            ArtifactQueryService artifactQueryService,
            CurrentUserResolver currentUserResolver
    ) {
        this.artifactQueryService = artifactQueryService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/builds", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<OciBuildRecordDto>> listBuilds(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "status", required = false) OciBuildStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return ResponseEntity.ok(artifactQueryService.listBuilds(workspaceId, projectId, status, query, pageable, user));
    }

    @GetMapping(value = "/artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PagedResponse<OciImageArtifactDto>> listArtifacts(
            @PathVariable UUID workspaceId,
            @RequestParam(name = "projectId", required = false) UUID projectId,
            @RequestParam(name = "verificationStatus", required = false) OciArtifactVerificationStatus verificationStatus,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return ResponseEntity.ok(
                artifactQueryService.listArtifacts(workspaceId, projectId, verificationStatus, query, pageable, user)
        );
    }

    @GetMapping(value = "/artifacts/{artifactId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OciImageArtifactDetailDto> getArtifactDetail(
            @PathVariable UUID workspaceId,
            @PathVariable UUID artifactId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(artifactQueryService.getArtifactDetail(workspaceId, artifactId, user));
    }

    @GetMapping(value = "/pipelines/runs/{runId}/artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineRunArtifactsResponse> getPipelineRunArtifacts(
            @PathVariable UUID workspaceId,
            @PathVariable UUID runId,
            Authentication authentication
    ) {
        UserEntity user = currentUserResolver.requireUserEntity(authentication);
        return ResponseEntity.ok(artifactQueryService.getPipelineRunArtifacts(workspaceId, runId, user));
    }
}
