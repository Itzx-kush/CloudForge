package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.github.GitHubSourceClient;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.dto.OciBuildRecordDto;
import com.cloudforge.backend.oci.dto.OciImageArtifactDto;
import com.cloudforge.backend.oci.dto.OciPublishAttemptDto;
import com.cloudforge.backend.oci.dto.WorkerBuildResultReportRequest;
import com.cloudforge.backend.oci.dto.WorkerOciBuildDescriptorResponse;
import com.cloudforge.backend.oci.dto.WorkerOciLeaseRequest;
import com.cloudforge.backend.oci.dto.WorkerPublishResultReportRequest;
import com.cloudforge.backend.oci.entity.OciArtifactEventEntity;
import com.cloudforge.backend.oci.entity.OciArtifactRetentionPolicy;
import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import com.cloudforge.backend.oci.entity.OciHandoffStatus;
import com.cloudforge.backend.oci.entity.OciHandoffType;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import com.cloudforge.backend.oci.entity.OciPublishAttemptEntity;
import com.cloudforge.backend.oci.entity.OciPublishAttemptStatus;
import com.cloudforge.backend.oci.entity.OciRegistryConnectionEntity;
import com.cloudforge.backend.oci.entity.OciSourceHandoffEntity;
import com.cloudforge.backend.oci.repository.OciArtifactEventRepository;
import com.cloudforge.backend.oci.repository.OciBuildRecordRepository;
import com.cloudforge.backend.oci.repository.OciImageArtifactRepository;
import com.cloudforge.backend.oci.repository.OciPublishAttemptRepository;
import com.cloudforge.backend.oci.repository.OciSourceHandoffRepository;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.service.AuditService;
import com.cloudforge.backend.worker.config.WorkerProperties;
import com.cloudforge.backend.worker.entity.AllowedWorkerOperation;
import com.cloudforge.backend.worker.entity.AttemptStatus;
import com.cloudforge.backend.worker.entity.StageExecutionAttemptEntity;
import com.cloudforge.backend.worker.entity.WorkerAdapterType;
import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import com.cloudforge.backend.worker.repository.StageExecutionAttemptRepository;
import com.cloudforge.backend.worker.repository.WorkerNodeRepository;
import com.cloudforge.backend.worker.security.WorkerPrincipal;
import com.cloudforge.backend.worker.service.LogSanitizer;
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Coordinates the full OCI build, registry publish, and immutable digest verification lifecycle
 * between {@code OCI_BUILDKIT} workers and CloudForge's PostgreSQL persistence layer.
 */
@Service
public class OciBuildLifecycleService {

    public static final Pattern OCI_DIGEST_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private final WorkerProperties workerProperties;
    private final OciBuildProperties ociProperties;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final OciSourceHandoffRepository handoffRepository;
    private final OciBuildRecordRepository buildRecordRepository;
    private final OciImageArtifactRepository artifactRepository;
    private final OciPublishAttemptRepository publishAttemptRepository;
    private final OciArtifactEventRepository artifactEventRepository;
    private final BuildRequestValidator buildRequestValidator;
    private final LogSanitizer logSanitizer;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public OciBuildLifecycleService(
            WorkerProperties workerProperties,
            OciBuildProperties ociProperties,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            OciSourceHandoffRepository handoffRepository,
            OciBuildRecordRepository buildRecordRepository,
            OciImageArtifactRepository artifactRepository,
            OciPublishAttemptRepository publishAttemptRepository,
            OciArtifactEventRepository artifactEventRepository,
            BuildRequestValidator buildRequestValidator,
            LogSanitizer logSanitizer,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.workerProperties = workerProperties;
        this.ociProperties = ociProperties;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.handoffRepository = handoffRepository;
        this.buildRecordRepository = buildRecordRepository;
        this.artifactRepository = artifactRepository;
        this.publishAttemptRepository = publishAttemptRepository;
        this.artifactEventRepository = artifactEventRepository;
        this.buildRequestValidator = buildRequestValidator;
        this.logSanitizer = logSanitizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Issues a validated, immutable OCI build or publish execution descriptor from DB state
     * and initializes the corresponding {@code oci_build_records} or {@code oci_publish_attempts} row.
     */
    @Transactional
    public WorkerOciBuildDescriptorResponse resolveExecutionDescriptor(
            String pathWorkerId,
            UUID attemptId,
            WorkerOciLeaseRequest request,
            WorkerPrincipal principal
    ) {
        LockedOciAttempt ctx = lockAndValidateOciAttempt(pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal);
        PipelineRunEntity run = ctx.run();
        PipelineStageRunEntity stage = ctx.stage();
        StageExecutionAttemptEntity attempt = ctx.attempt();

        if (run.getLinkedRepository() == null || !GitHubSourceClient.isCommitSha(run.getCommitSha())) {
            throw new ConflictException("OCI build/publish requires a pinned 40-char immutable GitHub commit");
        }

        Map<String, String> meta = readStringMap(stage.getMetadataJson());
        Instant now = Instant.now();

        if (AllowedWorkerOperation.OCI_IMAGE_BUILD.name().equals(attempt.getAllowedOperation())) {
            BuildRequestValidator.ValidatedOciBuildConfig cfg = buildRequestValidator.validateBuildStageConfig(
                    run.getWorkspace().getId(), stage.getStageKey(), meta, stage.getTimeoutSeconds()
            );

            List<OciSourceHandoffEntity> sourceHandoffs = handoffRepository.findLatestActiveByRunForUpdate(
                    run.getId(),
                    run.getWorkspace().getId(),
                    OciHandoffType.SOURCE_CONTEXT,
                    List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED)
            );
            if (sourceHandoffs.isEmpty()) {
                throw new ConflictException("No verified SOURCE_CONTEXT handoff exists for this pipeline run");
            }
            OciSourceHandoffEntity srcHandoff = sourceHandoffs.get(0);
            if (srcHandoff.getExpiresAt().isBefore(now)) {
                throw new ConflictException("Verified source context handoff has expired");
            }
            if (srcHandoff.getGithubRepositoryId() != run.getLinkedRepository().getGithubRepoId()
                    || !srcHandoff.getCommitSha().equals(run.getCommitSha())) {
                throw new ForbiddenOperationException("Source handoff repository/commit mismatch");
            }

            OciBuildRecordEntity buildRecord = buildRecordRepository.findByAttemptIdForUpdate(attempt.getId())
                    .orElseGet(() -> {
                        OciBuildRecordEntity b = new OciBuildRecordEntity();
                        b.setWorkspace(run.getWorkspace());
                        b.setProject(run.getProject());
                        b.setPipelineRun(run);
                        b.setStageRun(stage);
                        b.setAttempt(attempt);
                        b.setLinkedRepository(run.getLinkedRepository());
                        b.setGithubRepositoryId(run.getLinkedRepository().getGithubRepoId());
                        b.setRepositoryFullName(run.getLinkedRepository().getFullName());
                        b.setCommitSha(run.getCommitSha());
                        b.setSourceHandoff(srcHandoff);
                        b.setSourceChecksumSha256(srcHandoff.getContentChecksumSha256());
                        b.setRegistryConnection(cfg.registryConnection());
                        b.setDockerfilePath(cfg.dockerfilePath());
                        b.setBuildContextPath(cfg.buildContextPath());
                        b.setTargetImageName(cfg.targetImageName());
                        b.setTargetTag(cfg.targetTag());
                        b.setTargetPlatform(cfg.targetPlatform());
                        b.setBuildMode(cfg.buildMode());
                        b.setConfigChecksumSha256(cfg.configChecksumSha256());
                        b.setConfigSnapshotJson(cfg.configSnapshotJson());
                        b.setStatus(OciBuildStatus.RUNNING);
                        b.setWorkerId(principal.workerId());
                        b.setStartedAt(now);
                        OciBuildRecordEntity saved = buildRecordRepository.saveAndFlush(b);
                        recordEvent(run, saved, null, null, "OCI_BUILD_STARTED", "worker:" + principal.workerId(),
                                "Started BuildKit OCI image build for " + cfg.targetImageName() + " (" + cfg.targetPlatform() + ")",
                                Map.of("dockerfilePath", cfg.dockerfilePath(), "contextPath", cfg.buildContextPath(),
                                        "commitSha", run.getCommitSha(), "sourceChecksum", srcHandoff.getContentChecksumSha256()),
                                now);
                        return saved;
                    });

            OciRegistryConnectionEntity reg = cfg.registryConnection();
            return new WorkerOciBuildDescriptorResponse(
                    buildRecord.getId(),
                    null,
                    run.getWorkspace().getId(),
                    run.getProject().getId(),
                    run.getId(),
                    stage.getId(),
                    attempt.getId(),
                    AllowedWorkerOperation.OCI_IMAGE_BUILD.name(),
                    run.getLinkedRepository().getGithubRepoId(),
                    run.getLinkedRepository().getFullName(),
                    run.getCommitSha(),
                    cfg.dockerfilePath(),
                    cfg.buildContextPath(),
                    cfg.targetImageName(),
                    cfg.targetTag(),
                    cfg.targetPlatform(),
                    cfg.buildMode(),
                    cfg.buildArgs(),
                    cfg.cpuLimit(),
                    cfg.memoryLimit(),
                    cfg.timeoutSeconds(),
                    cfg.configChecksumSha256(),
                    srcHandoff.getId(),
                    srcHandoff.getContentChecksumSha256(),
                    null,
                    null,
                    null,
                    reg.getId(),
                    reg.getRegistryHost(),
                    reg.getScheme(),
                    reg.getAllowedNamespace(),
                    reg.isAllowInsecureLocal(),
                    false // NEVER expose credentials to BUILD stage
            );
        }

        if (AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(attempt.getAllowedOperation())) {
            BuildRequestValidator.ValidatedOciPublishConfig pubCfg =
                    buildRequestValidator.validatePublishStageConfig(run.getWorkspace().getId(), stage.getStageKey(), meta);

            List<OciBuildRecordEntity> succeededBuilds = buildRecordRepository
                    .findSucceededByRunAndWorkspace(run.getId(), run.getWorkspace().getId());
            OciBuildRecordEntity sourceBuild = succeededBuilds.stream()
                    .filter(b -> pubCfg.buildStageKey() == null
                            || b.getStageRun().getStageKey().equalsIgnoreCase(pubCfg.buildStageKey()))
                    .findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "OCI_IMAGE_PUBLISH requires a SUCCEEDED upstream OCI_IMAGE_BUILD record in this pipeline run"
                    ));

            if (!isValidDigest(sourceBuild.getBuiltManifestDigest()) || sourceBuild.getOciHandoff() == null) {
                throw new ConflictException("Upstream OCI build lacks a verified OCI image layout handoff and manifest digest");
            }
            OciSourceHandoffEntity ociHandoff = sourceBuild.getOciHandoff();
            if (ociHandoff.getExpiresAt().isBefore(now) || ociHandoff.getStatus() == OciHandoffStatus.CLEANED) {
                throw new ConflictException("Upstream OCI image layout handoff has expired or been cleaned");
            }

            OciRegistryConnectionEntity reg = pubCfg.registryConnection();
            String effectiveTag = StringUtils.hasText(pubCfg.targetTag())
                    ? pubCfg.targetTag()
                    : (StringUtils.hasText(sourceBuild.getTargetTag())
                    ? sourceBuild.getTargetTag()
                    : "sha-" + run.getCommitSha().substring(0, 12));

            OciPublishAttemptEntity publishAttempt = publishAttemptRepository.findByAttemptIdForUpdate(attempt.getId())
                    .orElseGet(() -> {
                        OciPublishAttemptEntity p = new OciPublishAttemptEntity();
                        p.setWorkspace(run.getWorkspace());
                        p.setProject(run.getProject());
                        p.setPipelineRun(run);
                        p.setStageRun(stage);
                        p.setAttempt(attempt);
                        p.setBuildRecord(sourceBuild);
                        p.setRegistryConnection(reg);
                        p.setWorkerId(principal.workerId());
                        p.setRegistryHost(reg.getRegistryHost());
                        p.setImageRepository(pubCfg.targetImageName());
                        p.setImageTag(effectiveTag);
                        p.setExpectedDigest(sourceBuild.getBuiltManifestDigest());
                        p.setStatus(OciPublishAttemptStatus.STARTED);
                        p.setStartedAt(now);
                        OciPublishAttemptEntity saved = publishAttemptRepository.saveAndFlush(p);
                        recordEvent(run, sourceBuild, null, saved, "OCI_PUBLISH_STARTED", "worker:" + principal.workerId(),
                                "Started OCI registry publication to " + reg.getRegistryHost() + "/" + pubCfg.targetImageName(),
                                Map.of("expectedDigest", sourceBuild.getBuiltManifestDigest(), "registryHost", reg.getRegistryHost()),
                                now);
                        return saved;
                    });

            return new WorkerOciBuildDescriptorResponse(
                    sourceBuild.getId(),
                    publishAttempt.getId(),
                    run.getWorkspace().getId(),
                    run.getProject().getId(),
                    run.getId(),
                    stage.getId(),
                    attempt.getId(),
                    AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name(),
                    run.getLinkedRepository().getGithubRepoId(),
                    run.getLinkedRepository().getFullName(),
                    run.getCommitSha(),
                    sourceBuild.getDockerfilePath(),
                    sourceBuild.getBuildContextPath(),
                    pubCfg.targetImageName(),
                    effectiveTag,
                    sourceBuild.getTargetPlatform(),
                    sourceBuild.getBuildMode(),
                    Map.of(),
                    "1.0",
                    "512m",
                    stage.getTimeoutSeconds(),
                    sourceBuild.getConfigChecksumSha256(),
                    sourceBuild.getSourceHandoff() != null ? sourceBuild.getSourceHandoff().getId() : null,
                    sourceBuild.getSourceChecksumSha256(),
                    ociHandoff.getId(),
                    ociHandoff.getContentChecksumSha256(),
                    sourceBuild.getBuiltManifestDigest(),
                    reg.getId(),
                    reg.getRegistryHost(),
                    reg.getScheme(),
                    reg.getAllowedNamespace(),
                    reg.isAllowInsecureLocal(),
                    reg.getPasswordSecret() != null
            );
        }

        throw new ForbiddenOperationException("Attempt is not an OCI build or publish operation");
    }

    /**
     * Persists verified BuildKit output metadata before the worker may mark {@code OCI_IMAGE_BUILD} succeeded.
     */
    @Transactional
    public OciBuildRecordDto reportBuildResult(
            String pathWorkerId,
            UUID attemptId,
            WorkerBuildResultReportRequest request,
            WorkerPrincipal principal
    ) {
        LockedOciAttempt ctx = lockAndValidateOciAttempt(pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal);
        if (!AllowedWorkerOperation.OCI_IMAGE_BUILD.name().equals(ctx.attempt().getAllowedOperation())) {
            throw new ForbiddenOperationException("Build result reporting is only valid for OCI_IMAGE_BUILD attempts");
        }

        OciBuildRecordEntity record = buildRecordRepository.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> new ConflictException("OCI build descriptor was not initialized for this attempt"));

        Instant now = Instant.now();
        String safeError = StringUtils.hasText(request.errorSummary())
                ? logSanitizer.sanitize(request.errorSummary().trim(), 480).message()
                : null;

        if (request.status() == OciBuildStatus.SUCCEEDED) {
            if (!isValidDigest(request.builtManifestDigest())) {
                throw new BadRequestException("A valid sha256 OCI manifest digest is required to mark an OCI build SUCCEEDED");
            }
            if (request.ociHandoffId() == null) {
                throw new BadRequestException("A verified ociHandoffId is required to mark an OCI build SUCCEEDED");
            }
            OciSourceHandoffEntity ociHandoff = handoffRepository.findById(request.ociHandoffId())
                    .filter(h -> h.getSourceAttempt().getId().equals(attemptId)
                            && h.getHandoffType() == OciHandoffType.OCI_IMAGE_LAYOUT)
                    .orElseThrow(() -> new ConflictException("Referenced OCI layout handoff does not belong to this build attempt"));

            record.setStatus(OciBuildStatus.SUCCEEDED);
            record.setBuiltManifestDigest(request.builtManifestDigest().trim().toLowerCase(Locale.ROOT));
            record.setImageMediaType(StringUtils.hasText(request.imageMediaType())
                    ? request.imageMediaType().trim()
                    : "application/vnd.oci.image.manifest.v1+json");
            record.setImageSizeBytes(request.imageSizeBytes());
            record.setOciHandoff(ociHandoff);
            record.setFailureCategory(null);
            record.setErrorSummary(null);
        } else {
            record.setStatus(request.status());
            record.setFailureCategory(request.failureCategory());
            record.setErrorSummary(safeError);
        }

        if (request.startedAt() != null && record.getStartedAt() == null) {
            record.setStartedAt(request.startedAt());
        }
        record.setCompletedAt(request.completedAt() != null ? request.completedAt() : now);
        OciBuildRecordEntity saved = buildRecordRepository.saveAndFlush(record);

        recordEvent(
                ctx.run(),
                saved,
                null,
                null,
                "OCI_BUILD_" + saved.getStatus().name(),
                "worker:" + principal.workerId(),
                saved.getStatus() == OciBuildStatus.SUCCEEDED
                        ? "BuildKit OCI image built with manifest digest " + saved.getBuiltManifestDigest()
                        : ("BuildKit OCI image build ended with " + saved.getStatus() + (safeError != null ? ": " + safeError : "")),
                Map.of(
                        "status", saved.getStatus().name(),
                        "digest", saved.getBuiltManifestDigest() != null ? saved.getBuiltManifestDigest() : "",
                        "imageSizeBytes", saved.getImageSizeBytes() != null ? saved.getImageSizeBytes() : 0L
                ),
                now
        );

        return toBuildDto(saved);
    }

    /**
     * Verifies and persists registry publish outcomes, immutable digest verification,
     * and idempotent {@code oci_image_artifacts} records.
     */
    @Transactional
    public OciPublishAttemptDto reportPublishResult(
            String pathWorkerId,
            UUID attemptId,
            WorkerPublishResultReportRequest request,
            WorkerPrincipal principal
    ) {
        LockedOciAttempt ctx = lockAndValidateOciAttempt(pathWorkerId, attemptId, request.leaseToken(), request.fencingGeneration(), principal);
        if (!AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(ctx.attempt().getAllowedOperation())) {
            throw new ForbiddenOperationException("Publish result reporting is only valid for OCI_IMAGE_PUBLISH attempts");
        }

        OciPublishAttemptEntity publishAttempt = publishAttemptRepository.findByAttemptIdForUpdate(attemptId)
                .orElseThrow(() -> new ConflictException("OCI publish descriptor was not initialized for this attempt"));

        Instant now = Instant.now();
        String safeError = StringUtils.hasText(request.errorSummary())
                ? logSanitizer.sanitize(request.errorSummary().trim(), 480).message()
                : null;

        String reportedDigest = StringUtils.hasText(request.reportedDigest())
                ? request.reportedDigest().trim().toLowerCase(Locale.ROOT)
                : null;
        String verifiedDigest = StringUtils.hasText(request.verifiedDigest())
                ? request.verifiedDigest().trim().toLowerCase(Locale.ROOT)
                : null;

        publishAttempt.setReportedDigest(reportedDigest);
        publishAttempt.setVerifiedDigest(verifiedDigest);
        publishAttempt.setCompletedAt(request.completedAt() != null ? request.completedAt() : now);

        if (request.status() == OciPublishAttemptStatus.VERIFIED) {
            // Strict server-side immutable digest verification invariants
            if (!request.verified()
                    || !isValidDigest(reportedDigest)
                    || !isValidDigest(verifiedDigest)
                    || !reportedDigest.equals(verifiedDigest)
                    || (publishAttempt.getExpectedDigest() != null && !publishAttempt.getExpectedDigest().equals(verifiedDigest))) {
                publishAttempt.setStatus(OciPublishAttemptStatus.FAILED);
                publishAttempt.setFailureCategory("DIGEST_VERIFICATION_FAILED");
                publishAttempt.setErrorSummary("Digest verification mismatch: expected=" + publishAttempt.getExpectedDigest()
                        + ", reported=" + reportedDigest + ", verified=" + verifiedDigest);
                publishAttemptRepository.saveAndFlush(publishAttempt);

                recordEvent(
                        ctx.run(),
                        publishAttempt.getBuildRecord(),
                        null,
                        publishAttempt,
                        "OCI_DIGEST_VERIFICATION_FAILED",
                        "worker:" + principal.workerId(),
                        publishAttempt.getErrorSummary(),
                        Map.of(
                                "expectedDigest", String.valueOf(publishAttempt.getExpectedDigest()),
                                "reportedDigest", String.valueOf(reportedDigest),
                                "verifiedDigest", String.valueOf(verifiedDigest)
                        ),
                        now
                );
                throw new ConflictException(publishAttempt.getErrorSummary());
            }

            String immutableRef = publishAttempt.getRegistryHost() + "/"
                    + publishAttempt.getImageRepository() + "@" + verifiedDigest;
            String mediaType = StringUtils.hasText(request.imageMediaType())
                    ? request.imageMediaType().trim()
                    : (publishAttempt.getBuildRecord() != null && publishAttempt.getBuildRecord().getImageMediaType() != null
                    ? publishAttempt.getBuildRecord().getImageMediaType()
                    : "application/vnd.oci.image.manifest.v1+json");
            String platform = StringUtils.hasText(request.platform())
                    ? request.platform().trim()
                    : (publishAttempt.getBuildRecord() != null ? publishAttempt.getBuildRecord().getTargetPlatform() : "linux/amd64");

            // Idempotent artifact persistence keyed on (workspace, run, registryHost, imageRepository, manifestDigest)
            OciImageArtifactEntity artifact = artifactRepository
                    .findByWorkspaceIdAndPipelineRunIdAndRegistryHostAndImageRepositoryAndManifestDigest(
                            ctx.run().getWorkspace().getId(),
                            ctx.run().getId(),
                            publishAttempt.getRegistryHost(),
                            publishAttempt.getImageRepository(),
                            verifiedDigest
                    )
                    .orElseGet(() -> {
                        OciImageArtifactEntity created = new OciImageArtifactEntity();
                        created.setWorkspace(ctx.run().getWorkspace());
                        created.setProject(ctx.run().getProject());
                        created.setPipelineRun(ctx.run());
                        created.setStageRun(ctx.stage());
                        created.setAttempt(ctx.attempt());
                        created.setBuildRecord(publishAttempt.getBuildRecord());
                        created.setRegistryConnection(publishAttempt.getRegistryConnection());
                        created.setLinkedRepository(ctx.run().getLinkedRepository());
                        created.setGithubRepositoryId(ctx.run().getLinkedRepository().getGithubRepoId());
                        created.setRepositoryFullName(ctx.run().getLinkedRepository().getFullName());
                        created.setCommitSha(ctx.run().getCommitSha());
                        created.setRegistryHost(publishAttempt.getRegistryHost());
                        created.setImageRepository(publishAttempt.getImageRepository());
                        created.setImageTag(publishAttempt.getImageTag());
                        created.setManifestDigest(verifiedDigest);
                        created.setImmutableReference(immutableRef);
                        created.setImageMediaType(mediaType);
                        created.setPlatform(platform);
                        created.setImageSizeBytes(request.imageSizeBytes() != null
                                ? request.imageSizeBytes()
                                : (publishAttempt.getBuildRecord() != null ? publishAttempt.getBuildRecord().getImageSizeBytes() : null));
                        created.setVerificationStatus(OciArtifactVerificationStatus.VERIFIED);
                        created.setVerifiedAt(now);
                        created.setPublishedAt(now);
                        created.setRetentionPolicy(OciArtifactRetentionPolicy.RETAIN_METADATA);
                        return artifactRepository.saveAndFlush(created);
                    });

            publishAttempt.setArtifact(artifact);
            publishAttempt.setStatus(OciPublishAttemptStatus.VERIFIED);
            publishAttempt.setFailureCategory(null);
            publishAttempt.setErrorSummary(null);
            OciPublishAttemptEntity savedPublish = publishAttemptRepository.saveAndFlush(publishAttempt);

            recordEvent(
                    ctx.run(),
                    publishAttempt.getBuildRecord(),
                    artifact,
                    savedPublish,
                    "OCI_ARTIFACT_VERIFIED",
                    "worker:" + principal.workerId(),
                    "Published and independently verified immutable OCI artifact " + immutableRef,
                    Map.of(
                            "immutableReference", immutableRef,
                            "manifestDigest", verifiedDigest,
                            "platform", platform,
                            "commitSha", ctx.run().getCommitSha()
                    ),
                    now
            );

            auditService.record(
                    ctx.run().getWorkspace(),
                    null,
                    "OCI_ARTIFACT_PUBLISHED_VERIFIED",
                    "OCI_IMAGE_ARTIFACT",
                    artifact.getId().toString(),
                    "Published and verified OCI artifact " + immutableRef + " from commit " + ctx.run().getCommitSha(),
                    Map.of(
                            "pipelineRunId", ctx.run().getId().toString(),
                            "immutableReference", immutableRef,
                            "manifestDigest", verifiedDigest,
                            "commitSha", ctx.run().getCommitSha()
                    )
            );
            return toPublishDto(savedPublish);
        }

        // Failed / Cancelled / Reconciliation required
        publishAttempt.setStatus(request.status());
        publishAttempt.setFailureCategory(request.failureCategory());
        publishAttempt.setErrorSummary(safeError);
        OciPublishAttemptEntity savedPublish = publishAttemptRepository.saveAndFlush(publishAttempt);

        recordEvent(
                ctx.run(),
                publishAttempt.getBuildRecord(),
                null,
                savedPublish,
                "OCI_PUBLISH_" + savedPublish.getStatus().name(),
                "worker:" + principal.workerId(),
                "OCI publish attempt ended with " + savedPublish.getStatus()
                        + (safeError != null ? ": " + safeError : ""),
                Map.of(
                        "status", savedPublish.getStatus().name(),
                        "failureCategory", String.valueOf(savedPublish.getFailureCategory())
                ),
                now
        );

        return toPublishDto(savedPublish);
    }

    /**
     * Enforced by {@code WorkerResultProcessorService} before allowing an OCI stage to transition to SUCCEEDED.
     */
    @Transactional(readOnly = true)
    public void verifyCompletionPreconditions(StageExecutionAttemptEntity attempt) {
        if (AllowedWorkerOperation.OCI_IMAGE_BUILD.name().equals(attempt.getAllowedOperation())) {
            OciBuildRecordEntity record = buildRecordRepository.findByAttemptId(attempt.getId())
                    .orElseThrow(() -> new ConflictException(
                            "OCI_IMAGE_BUILD cannot succeed without a persisted verified oci_build_records outcome"
                    ));
            if (record.getStatus() != OciBuildStatus.SUCCEEDED || !isValidDigest(record.getBuiltManifestDigest())) {
                throw new ConflictException(
                        "OCI_IMAGE_BUILD cannot succeed until BuildKit confirms a valid OCI manifest digest"
                );
            }
        } else if (AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(attempt.getAllowedOperation())) {
            OciPublishAttemptEntity publish = publishAttemptRepository.findByAttemptId(attempt.getId())
                    .orElseThrow(() -> new ConflictException(
                            "OCI_IMAGE_PUBLISH cannot succeed without a verified oci_publish_attempts record"
                    ));
            if (publish.getStatus() != OciPublishAttemptStatus.VERIFIED
                    || publish.getArtifact() == null
                    || !isValidDigest(publish.getVerifiedDigest())) {
                throw new ConflictException(
                        "OCI_IMAGE_PUBLISH cannot succeed until the published manifest digest is independently retrieved and verified"
                );
            }
        }
    }

    /**
     * Reconciles interrupted/expired/cancelled OCI attempts when worker crashes or lease expires.
     */
    @Transactional
    public void reconcileAbortedAttempt(StageExecutionAttemptEntity attempt, String reason, boolean cancelled) {
        Instant now = Instant.now();
        if (AllowedWorkerOperation.OCI_IMAGE_BUILD.name().equals(attempt.getAllowedOperation())) {
            buildRecordRepository.findByAttemptIdForUpdate(attempt.getId()).ifPresent(record -> {
                if (record.getStatus() == OciBuildStatus.PENDING || record.getStatus() == OciBuildStatus.RUNNING) {
                    record.setStatus(cancelled ? OciBuildStatus.CANCELLED : OciBuildStatus.FAILED);
                    record.setErrorSummary(reason);
                    record.setCompletedAt(now);
                    buildRecordRepository.save(record);
                }
            });
        } else if (AllowedWorkerOperation.OCI_IMAGE_PUBLISH.name().equals(attempt.getAllowedOperation())) {
            publishAttemptRepository.findByAttemptIdForUpdate(attempt.getId()).ifPresent(pub -> {
                if (pub.getStatus() == OciPublishAttemptStatus.STARTED || pub.getStatus() == OciPublishAttemptStatus.PUSHED) {
                    pub.setStatus(cancelled ? OciPublishAttemptStatus.CANCELLED : OciPublishAttemptStatus.RECONCILIATION_REQUIRED);
                    pub.setErrorSummary(reason);
                    pub.setCompletedAt(now);
                    publishAttemptRepository.save(pub);
                }
            });
        }
    }

    private LockedOciAttempt lockAndValidateOciAttempt(
            String pathWorkerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            WorkerPrincipal principal
    ) {
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!workerProperties.isEnabled() || !ociProperties.isEnabled()) {
            throw new ForbiddenOperationException("OCI container builds are disabled");
        }
        if (!ociProperties.permitsBuildWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not allowlisted for OCI build/publish execution");
        }

        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .filter(ids -> principal.workerId().equals(ids.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Execution attempt unavailable"));

        WorkerNodeEntity worker = workerNodeRepository.findByWorkerIdForUpdate(principal.workerId())
                .filter(w -> w.getStatus() != WorkerStatus.OFFLINE && w.getAdapterType() == WorkerAdapterType.OCI_BUILDKIT)
                .orElseThrow(() -> new ForbiddenOperationException("An active OCI_BUILDKIT worker is required"));
        PipelineRunEntity run = runRepository
                .findByIdAndWorkspaceIdForUpdate(lockIds.getPipelineRunId(), lockIds.getWorkspaceId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline run not found"));
        PipelineStageRunEntity stage = stageRunRepository
                .findByIdForUpdate(lockIds.getStageRunId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline stage not found"));
        StageExecutionAttemptEntity attempt = attemptRepository
                .findByIdForUpdate(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution attempt not found"));

        Instant now = Instant.now();
        if (attempt.getStatus() != AttemptStatus.RUNNING
                || stage.getStatus() != PipelineStageStatus.RUNNING
                || run.getStatus().isTerminal()
                || attempt.isTerminationConfirmed()
                || attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null || !stage.getLeaseExpiresAt().isAfter(now)
                || !leaseToken.equals(attempt.getLeaseToken())
                || !leaseToken.equals(stage.getActiveLeaseToken())
                || fencingGeneration != attempt.getFencingGeneration()
                || fencingGeneration != stage.getFencingGeneration()) {
            throw new ConflictException("Active unexpired lease and matching fencing generation are required");
        }

        return new LockedOciAttempt(worker, run, stage, attempt);
    }

    public static boolean isValidDigest(String digest) {
        return digest != null && OCI_DIGEST_PATTERN.matcher(digest.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private void recordEvent(
            PipelineRunEntity run,
            OciBuildRecordEntity build,
            OciImageArtifactEntity artifact,
            OciPublishAttemptEntity publishAttempt,
            String eventType,
            String actorLabel,
            String message,
            Map<String, Object> metadata,
            Instant occurredAt
    ) {
        OciArtifactEventEntity ev = new OciArtifactEventEntity();
        ev.setWorkspace(run.getWorkspace());
        ev.setProject(run.getProject());
        ev.setPipelineRun(run);
        ev.setBuildRecord(build);
        ev.setArtifact(artifact);
        ev.setPublishAttempt(publishAttempt);
        ev.setEventType(eventType);
        ev.setActorLabel(actorLabel);
        ev.setMessage(message);
        ev.setMetadataJson(writeJson(metadata == null ? Map.of() : metadata));
        ev.setOccurredAt(occurredAt);
        artifactEventRepository.save(ev);
    }

    public OciBuildRecordDto toBuildDto(OciBuildRecordEntity b) {
        return new OciBuildRecordDto(
                b.getId(),
                b.getWorkspace().getId(),
                b.getProject().getId(),
                b.getProject().getName(),
                b.getPipelineRun().getId(),
                b.getPipelineRun().getRunNumber(),
                b.getStageRun().getId(),
                b.getAttempt().getId(),
                b.getGithubRepositoryId(),
                b.getRepositoryFullName(),
                b.getCommitSha(),
                b.getSourceChecksumSha256(),
                b.getRegistryConnection() != null ? b.getRegistryConnection().getId() : null,
                b.getRegistryConnection() != null ? b.getRegistryConnection().getRegistryHost() : null,
                b.getDockerfilePath(),
                b.getBuildContextPath(),
                b.getTargetImageName(),
                b.getTargetTag(),
                b.getTargetPlatform(),
                b.getBuildMode(),
                b.getConfigChecksumSha256(),
                b.getStatus(),
                b.getBuiltManifestDigest(),
                b.getImageMediaType(),
                b.getImageSizeBytes(),
                b.getWorkerId(),
                b.getFailureCategory(),
                b.getErrorSummary(),
                b.getStartedAt(),
                b.getCompletedAt(),
                b.getCreatedAt()
        );
    }

    public OciImageArtifactDto toArtifactDto(OciImageArtifactEntity a) {
        return new OciImageArtifactDto(
                a.getId(),
                a.getWorkspace().getId(),
                a.getProject().getId(),
                a.getProject().getName(),
                a.getPipelineRun().getId(),
                a.getPipelineRun().getRunNumber(),
                a.getStageRun().getId(),
                a.getAttempt().getId(),
                a.getBuildRecord() != null ? a.getBuildRecord().getId() : null,
                a.getRegistryConnection() != null ? a.getRegistryConnection().getId() : null,
                a.getGithubRepositoryId(),
                a.getRepositoryFullName(),
                a.getCommitSha(),
                a.getRegistryHost(),
                a.getImageRepository(),
                a.getImageTag(),
                a.getManifestDigest(),
                a.getImmutableReference(),
                a.getImageMediaType(),
                a.getPlatform(),
                a.getImageSizeBytes(),
                a.getVerificationStatus(),
                a.getVerifiedAt(),
                a.getPublishedAt(),
                a.getRetentionPolicy(),
                a.getErrorSummary(),
                a.getCreatedAt()
        );
    }

    public OciPublishAttemptDto toPublishDto(OciPublishAttemptEntity p) {
        return new OciPublishAttemptDto(
                p.getId(),
                p.getWorkspace().getId(),
                p.getProject().getId(),
                p.getPipelineRun().getId(),
                p.getStageRun().getId(),
                p.getAttempt().getId(),
                p.getBuildRecord() != null ? p.getBuildRecord().getId() : null,
                p.getRegistryConnection() != null ? p.getRegistryConnection().getId() : null,
                p.getArtifact() != null ? p.getArtifact().getId() : null,
                p.getWorkerId(),
                p.getRegistryHost(),
                p.getImageRepository(),
                p.getImageTag(),
                p.getExpectedDigest(),
                p.getReportedDigest(),
                p.getVerifiedDigest(),
                p.getStatus(),
                p.getFailureCategory(),
                p.getErrorSummary(),
                p.getStartedAt(),
                p.getCompletedAt()
        );
    }

    private Map<String, String> readStringMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private record LockedOciAttempt(
            WorkerNodeEntity worker,
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt
    ) {
    }
}
