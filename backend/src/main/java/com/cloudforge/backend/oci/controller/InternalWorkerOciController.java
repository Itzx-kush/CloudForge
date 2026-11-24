package com.cloudforge.backend.oci.controller;

import com.cloudforge.backend.oci.dto.OciBuildRecordDto;
import com.cloudforge.backend.oci.dto.OciPublishAttemptDto;
import com.cloudforge.backend.oci.dto.WorkerBuildResultReportRequest;
import com.cloudforge.backend.oci.dto.WorkerHandoffUploadResponse;
import com.cloudforge.backend.oci.dto.WorkerOciBuildDescriptorResponse;
import com.cloudforge.backend.oci.dto.WorkerOciLeaseRequest;
import com.cloudforge.backend.oci.dto.WorkerPublishResultReportRequest;
import com.cloudforge.backend.oci.dto.WorkerRegistryCredentialResponse;
import com.cloudforge.backend.oci.entity.OciHandoffType;
import com.cloudforge.backend.oci.service.OciBuildLifecycleService;
import com.cloudforge.backend.oci.service.RegistryCredentialResolver;
import com.cloudforge.backend.oci.service.SourceHandoffService;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * Authenticated, lease-bound, fenced internal worker endpoints for Phase 3.5B OCI source/layout
 * handoffs, build descriptors, registry credential delivery, and verified artifact reporting.
 *
 * <p>Protected exclusively by {@code ROLE_TRUSTED_WORKER} stateless filter chain.</p>
 */
@RestController
@RequestMapping("/api/internal/workers/{workerId}/attempts/{attemptId}")
public class InternalWorkerOciController {

    public static final String HEADER_LEASE_TOKEN = "X-CloudForge-Lease-Token";
    public static final String HEADER_FENCING_GENERATION = "X-CloudForge-Fencing-Generation";
    public static final String HEADER_CONTENT_SHA256 = "X-CloudForge-Content-Sha256";
    public static final String HEADER_FILE_COUNT = "X-CloudForge-File-Count";
    public static final String HEADER_EXTRACTED_BYTES = "X-CloudForge-Extracted-Bytes";
    public static final String HEADER_IMAGE_SIZE_BYTES = "X-CloudForge-Image-Size-Bytes";
    public static final String HEADER_COMMIT_SHA = "X-CloudForge-Commit-Sha";
    public static final String HEADER_REPOSITORY_ID = "X-CloudForge-Repository-Id";
    public static final String HEADER_HANDOFF_ID = "X-CloudForge-Handoff-Id";

    private final SourceHandoffService handoffService;
    private final OciBuildLifecycleService lifecycleService;
    private final RegistryCredentialResolver credentialResolver;

    public InternalWorkerOciController(
            SourceHandoffService handoffService,
            OciBuildLifecycleService lifecycleService,
            RegistryCredentialResolver credentialResolver
    ) {
        this.handoffService = handoffService;
        this.lifecycleService = lifecycleService;
        this.credentialResolver = credentialResolver;
    }

    @PostMapping(
            value = "/source-handoff",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerHandoffUploadResponse> uploadSourceHandoff(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @RequestHeader(HEADER_LEASE_TOKEN) String leaseToken,
            @RequestHeader(HEADER_FENCING_GENERATION) long fencingGeneration,
            @RequestHeader(HEADER_CONTENT_SHA256) String contentSha256,
            @RequestHeader(HEADER_FILE_COUNT) int fileCount,
            @RequestHeader(HEADER_EXTRACTED_BYTES) long extractedBytes,
            HttpServletRequest request,
            Authentication authentication
    ) throws IOException {
        WorkerPrincipal principal = requireWorker(authentication);
        WorkerHandoffUploadResponse response = handoffService.uploadSourceContext(
                workerId,
                attemptId,
                leaseToken,
                fencingGeneration,
                contentSha256,
                fileCount,
                extractedBytes,
                request.getInputStream(),
                principal
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping(
            value = "/oci-descriptor",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerOciBuildDescriptorResponse> fetchOciDescriptor(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerOciLeaseRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.resolveExecutionDescriptor(workerId, attemptId, request, principal));
    }

    @PostMapping(
            value = "/build-context",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<Resource> downloadBuildContext(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerOciLeaseRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        SourceHandoffService.HandoffDownload download = handoffService.openVerifiedHandoffForConsumer(
                workerId,
                attemptId,
                request.leaseToken(),
                request.fencingGeneration(),
                OciHandoffType.SOURCE_CONTEXT,
                AllowedWorkerOperation.OCI_IMAGE_BUILD,
                principal
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HEADER_HANDOFF_ID, download.handoffId().toString())
                .header(HEADER_CONTENT_SHA256, download.contentChecksumSha256())
                .header(HEADER_COMMIT_SHA, download.commitSha())
                .header(HEADER_REPOSITORY_ID, String.valueOf(download.githubRepositoryId()))
                .contentLength(download.archiveSizeBytes())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(download.file()));
    }

    @PostMapping(
            value = "/oci-layout-upload",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerHandoffUploadResponse> uploadOciLayout(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @RequestHeader(HEADER_LEASE_TOKEN) String leaseToken,
            @RequestHeader(HEADER_FENCING_GENERATION) long fencingGeneration,
            @RequestHeader(HEADER_CONTENT_SHA256) String contentSha256,
            @RequestHeader(HEADER_IMAGE_SIZE_BYTES) long imageSizeBytes,
            HttpServletRequest request,
            Authentication authentication
    ) throws IOException {
        WorkerPrincipal principal = requireWorker(authentication);
        WorkerHandoffUploadResponse response = handoffService.uploadOciImageLayout(
                workerId,
                attemptId,
                leaseToken,
                fencingGeneration,
                contentSha256,
                imageSizeBytes,
                request.getInputStream(),
                principal
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping(
            value = "/oci-layout-download",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<Resource> downloadOciLayout(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerOciLeaseRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        SourceHandoffService.HandoffDownload download = handoffService.openVerifiedHandoffForConsumer(
                workerId,
                attemptId,
                request.leaseToken(),
                request.fencingGeneration(),
                OciHandoffType.OCI_IMAGE_LAYOUT,
                AllowedWorkerOperation.OCI_IMAGE_PUBLISH,
                principal
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HEADER_HANDOFF_ID, download.handoffId().toString())
                .header(HEADER_CONTENT_SHA256, download.contentChecksumSha256())
                .header(HEADER_COMMIT_SHA, download.commitSha())
                .header(HEADER_REPOSITORY_ID, String.valueOf(download.githubRepositoryId()))
                .contentLength(download.archiveSizeBytes())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(download.file()));
    }

    @PostMapping(
            value = "/build-result",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<OciBuildRecordDto> reportBuildResult(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerBuildResultReportRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.reportBuildResult(workerId, attemptId, request, principal));
    }

    @PostMapping(
            value = "/registry-credentials",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<WorkerRegistryCredentialResponse> fetchRegistryCredentials(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerOciLeaseRequest request,
            HttpServletRequest httpRequest,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(credentialResolver.resolveForPublishAttempt(workerId, attemptId, request, principal, httpRequest));
    }

    @PostMapping(
            value = "/publish-result",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<OciPublishAttemptDto> reportPublishResult(
            @PathVariable String workerId,
            @PathVariable UUID attemptId,
            @Valid @RequestBody WorkerPublishResultReportRequest request,
            Authentication authentication
    ) {
        WorkerPrincipal principal = requireWorker(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.reportPublishResult(workerId, attemptId, request, principal));
    }

    private static WorkerPrincipal requireWorker(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof WorkerPrincipal principal)) {
            throw new IllegalStateException("Trusted WorkerPrincipal missing from security context");
        }
        return principal;
    }
}
