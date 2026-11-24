package com.cloudforge.backend.oci.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.exception.ForbiddenOperationException;
import com.cloudforge.backend.exception.ResourceNotFoundException;
import com.cloudforge.backend.github.GitHubSourceClient;
import com.cloudforge.backend.oci.config.OciBuildProperties;
import com.cloudforge.backend.oci.dto.WorkerHandoffUploadResponse;
import com.cloudforge.backend.oci.entity.OciHandoffStatus;
import com.cloudforge.backend.oci.entity.OciHandoffType;
import com.cloudforge.backend.oci.entity.OciSourceHandoffEntity;
import com.cloudforge.backend.oci.repository.OciSourceHandoffRepository;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.repository.PipelineRunRepository;
import com.cloudforge.backend.orchestration.repository.PipelineStageRunRepository;
import com.cloudforge.backend.source.ProjectSourceService;
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
import com.cloudforge.backend.worker.service.WorkerRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ephemeral, bounded, cryptographically bound build-context and OCI-layout handoff service.
 *
 * <p>Binds every staged bundle strictly to {@code (workspace, project, pipelineRun, stageAttempt,
 * githubRepositoryId, commitSha, sha256Checksum)}. Enforces per-bundle and total storage caps,
 * TTL expiry, and immediate cleanup when runs complete or cancel — never acts as a general-purpose
 * source code hosting service.</p>
 */
@Service
public class SourceHandoffService {

    private static final Logger log = LoggerFactory.getLogger(SourceHandoffService.class);
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SAFE_STORAGE_TOKEN = Pattern.compile("^[a-f0-9-]{36}\\.(?:zip|tar)$");

    private final WorkerProperties workerProperties;
    private final OciBuildProperties ociProperties;
    private final ProjectSourceService sourceService;
    private final WorkerNodeRepository workerNodeRepository;
    private final PipelineRunRepository runRepository;
    private final PipelineStageRunRepository stageRunRepository;
    private final StageExecutionAttemptRepository attemptRepository;
    private final OciSourceHandoffRepository handoffRepository;

    public SourceHandoffService(
            WorkerProperties workerProperties,
            OciBuildProperties ociProperties,
            ProjectSourceService sourceService,
            WorkerNodeRepository workerNodeRepository,
            PipelineRunRepository runRepository,
            PipelineStageRunRepository stageRunRepository,
            StageExecutionAttemptRepository attemptRepository,
            OciSourceHandoffRepository handoffRepository
    ) {
        this.workerProperties = workerProperties;
        this.ociProperties = ociProperties;
        this.sourceService = sourceService;
        this.workerNodeRepository = workerNodeRepository;
        this.runRepository = runRepository;
        this.stageRunRepository = stageRunRepository;
        this.attemptRepository = attemptRepository;
        this.handoffRepository = handoffRepository;
    }

    @Transactional
    public WorkerHandoffUploadResponse uploadSourceContext(
            String pathWorkerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            String declaredSha256,
            int fileCount,
            long extractedBytes,
            InputStream bodyStream,
            WorkerPrincipal principal
    ) {
        requireOciEnabled();
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!sourceService.permitsWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not approved for GitHub source handoff");
        }
        String expectedSha = validateSha256(declaredSha256);
        if (fileCount < 1 || fileCount > 12_000 || extractedBytes < 1 || extractedBytes > 128L * 1024 * 1024) {
            throw new BadRequestException("Invalid source handoff file count or extracted size bounds");
        }

        LockedContext ctx = lockAndValidateActiveAttempt(
                principal.workerId(),
                attemptId,
                leaseToken,
                fencingGeneration,
                WorkerAdapterType.GITHUB_SOURCE,
                AllowedWorkerOperation.GITHUB_SOURCE_ACQUIRE
        );
        if (ctx.attempt().getSourceArchiveIssuedAt() == null) {
            throw new ConflictException("Source handoff requires a prior issued GitHub source archive grant");
        }
        if (ctx.run().getLinkedRepository() == null || !GitHubSourceClient.isCommitSha(ctx.run().getCommitSha())) {
            throw new ConflictException("Source handoff requires a linked GitHub repository and pinned 40-char commit SHA");
        }

        return storeHandoffBundle(
                ctx,
                OciHandoffType.SOURCE_CONTEXT,
                expectedSha,
                fileCount,
                extractedBytes,
                ociProperties.getMaxSourceHandoffBytes(),
                ".zip",
                bodyStream
        );
    }

    @Transactional
    public WorkerHandoffUploadResponse uploadOciImageLayout(
            String pathWorkerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            String declaredSha256,
            long imageSizeBytes,
            InputStream bodyStream,
            WorkerPrincipal principal
    ) {
        requireOciEnabled();
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!ociProperties.permitsBuildWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not approved for OCI build layout handoff");
        }
        String expectedSha = validateSha256(declaredSha256);
        if (imageSizeBytes < 1 || imageSizeBytes > ociProperties.getMaxOciLayoutHandoffBytes()) {
            throw new BadRequestException("Invalid OCI layout size bounds");
        }

        LockedContext ctx = lockAndValidateActiveAttempt(
                principal.workerId(),
                attemptId,
                leaseToken,
                fencingGeneration,
                WorkerAdapterType.OCI_BUILDKIT,
                AllowedWorkerOperation.OCI_IMAGE_BUILD
        );
        if (ctx.run().getLinkedRepository() == null || !GitHubSourceClient.isCommitSha(ctx.run().getCommitSha())) {
            throw new ConflictException("OCI layout handoff requires a pinned GitHub commit");
        }

        return storeHandoffBundle(
                ctx,
                OciHandoffType.OCI_IMAGE_LAYOUT,
                expectedSha,
                1,
                imageSizeBytes,
                ociProperties.getMaxOciLayoutHandoffBytes(),
                ".tar",
                bodyStream
        );
    }

    @Transactional
    public HandoffDownload openVerifiedHandoffForConsumer(
            String pathWorkerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            OciHandoffType handoffType,
            AllowedWorkerOperation requiredConsumerOperation,
            WorkerPrincipal principal
    ) {
        requireOciEnabled();
        WorkerRegistryService.requireMatchingWorkerIdentity(pathWorkerId, principal);
        if (!ociProperties.permitsBuildWorker(principal.workerId())) {
            throw new ForbiddenOperationException("Worker is not approved for OCI build/publish execution");
        }

        LockedContext ctx = lockAndValidateActiveAttempt(
                principal.workerId(),
                attemptId,
                leaseToken,
                fencingGeneration,
                WorkerAdapterType.OCI_BUILDKIT,
                requiredConsumerOperation
        );
        PipelineRunEntity run = ctx.run();
        if (run.getLinkedRepository() == null || !GitHubSourceClient.isCommitSha(run.getCommitSha())) {
            throw new ConflictException("Pipeline run lacks a pinned immutable GitHub commit");
        }

        List<OciSourceHandoffEntity> candidates = handoffRepository.findLatestActiveByRunForUpdate(
                run.getId(),
                run.getWorkspace().getId(),
                handoffType,
                List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED)
        );
        if (candidates.isEmpty()) {
            throw new ConflictException("No verified " + handoffType + " handoff is available for this pipeline run");
        }
        OciSourceHandoffEntity handoff = candidates.get(0);
        Instant now = Instant.now();
        if (handoff.getExpiresAt().isBefore(now)) {
            handoff.setStatus(OciHandoffStatus.EXPIRED);
            handoffRepository.save(handoff);
            deleteStorageFileQuietly(handoff.getStorageToken());
            throw new ConflictException("Verified " + handoffType + " handoff has expired");
        }

        // Verify strict cryptographic and identity binding before streaming bytes
        if (!handoff.getWorkspace().getId().equals(run.getWorkspace().getId())
                || !handoff.getProject().getId().equals(run.getProject().getId())
                || !handoff.getPipelineRun().getId().equals(run.getId())
                || handoff.getGithubRepositoryId() != run.getLinkedRepository().getGithubRepoId()
                || !handoff.getCommitSha().equals(run.getCommitSha())) {
            throw new ForbiddenOperationException("Handoff identity binding mismatch; refusing to serve bundle");
        }

        Path file = resolveStorageFile(handoff.getStorageToken());
        if (!Files.isRegularFile(file)) {
            handoff.setStatus(OciHandoffStatus.CLEANED);
            handoff.setCleanedAt(now);
            handoffRepository.save(handoff);
            throw new ConflictException("Verified handoff artifact file is no longer present in ephemeral storage");
        }

        handoff.setStatus(OciHandoffStatus.CONSUMED);
        handoff.setConsumedAt(now);
        handoff.setConsumedByAttemptId(attemptId);
        handoffRepository.save(handoff);

        return new HandoffDownload(
                handoff.getId(),
                handoff.getHandoffType(),
                handoff.getGithubRepositoryId(),
                handoff.getRepositoryFullName(),
                handoff.getCommitSha(),
                handoff.getContentChecksumSha256(),
                handoff.getArchiveSizeBytes(),
                file
        );
    }

    @Transactional
    public void cleanupForRun(UUID pipelineRunId) {
        if (pipelineRunId == null) {
            return;
        }
        List<OciSourceHandoffEntity> active = handoffRepository.findAllByPipelineRunIdAndStatusIn(
                pipelineRunId,
                List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED, OciHandoffStatus.EXPIRED)
        );
        Instant now = Instant.now();
        for (OciSourceHandoffEntity handoff : active) {
            deleteStorageFileQuietly(handoff.getStorageToken());
            handoff.setStatus(OciHandoffStatus.CLEANED);
            handoff.setCleanedAt(now);
        }
        if (!active.isEmpty()) {
            handoffRepository.saveAll(active);
        }
    }

    @Scheduled(fixedDelayString = "${cloudforge.oci.handoff-cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupExpiredHandoffs() {
        if (!ociProperties.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<OciSourceHandoffEntity> expired = handoffRepository.findExpiredActive(
                List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED),
                now
        );
        for (OciSourceHandoffEntity handoff : expired) {
            deleteStorageFileQuietly(handoff.getStorageToken());
            handoff.setStatus(OciHandoffStatus.CLEANED);
            handoff.setCleanedAt(now);
        }
        if (!expired.isEmpty()) {
            handoffRepository.saveAll(expired);
            log.info("Cleaned {} expired ephemeral OCI handoff bundle(s)", expired.size());
        }
    }

    private WorkerHandoffUploadResponse storeHandoffBundle(
            LockedContext ctx,
            OciHandoffType handoffType,
            String expectedSha,
            int fileCount,
            long extractedBytes,
            long maxBundleBytes,
            String extension,
            InputStream bodyStream
    ) {
        if (handoffRepository.findBySourceAttemptIdAndHandoffType(ctx.attempt().getId(), handoffType).isPresent()) {
            throw new ConflictException("Handoff bundle already recorded for this stage execution attempt");
        }
        long usedBytes = handoffRepository.sumArchiveSizeBytesByStatusIn(
                List.of(OciHandoffStatus.AVAILABLE, OciHandoffStatus.CONSUMED)
        );
        if (usedBytes >= ociProperties.getMaxTotalHandoffStorageBytes()) {
            throw new ConflictException("Ephemeral OCI handoff storage quota exceeded; retry after cleanup");
        }

        String storageToken = UUID.randomUUID() + extension;
        Path targetFile = resolveStorageFile(storageToken);
        long writtenBytes = 0L;
        String computedSha;
        try {
            Files.createDirectories(targetFile.getParent());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long remainingQuota = Math.min(maxBundleBytes, ociProperties.getMaxTotalHandoffStorageBytes() - usedBytes);
            try (OutputStream out = Files.newOutputStream(targetFile, StandardOpenOption.CREATE_NEW)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = bodyStream.read(buf)) != -1) {
                    if (read > remainingQuota - writtenBytes) {
                        throw new BadRequestException("Uploaded handoff bundle exceeds allowed byte limit");
                    }
                    out.write(buf, 0, read);
                    md.update(buf, 0, read);
                    writtenBytes += read;
                }
            }
            if (writtenBytes == 0) {
                throw new BadRequestException("Uploaded handoff bundle must not be empty");
            }
            computedSha = HexFormat.of().formatHex(md.digest());
            if (!computedSha.equals(expectedSha)) {
                throw new ConflictException("Uploaded handoff checksum mismatch: integrity check failed");
            }
        } catch (BadRequestException | ConflictException ex) {
            deleteStorageFileQuietly(storageToken);
            throw ex;
        } catch (IOException | NoSuchAlgorithmException ex) {
            deleteStorageFileQuietly(storageToken);
            throw new IllegalStateException("Failed to persist ephemeral OCI handoff bundle", ex);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(60, ociProperties.getHandoffTtlSeconds()));
        PipelineRunEntity run = ctx.run();

        OciSourceHandoffEntity entity = new OciSourceHandoffEntity();
        entity.setWorkspace(run.getWorkspace());
        entity.setProject(run.getProject());
        entity.setPipelineRun(run);
        entity.setSourceStageRun(ctx.stage());
        entity.setSourceAttempt(ctx.attempt());
        entity.setHandoffType(handoffType);
        entity.setGithubRepositoryId(run.getLinkedRepository().getGithubRepoId());
        entity.setRepositoryFullName(run.getLinkedRepository().getFullName());
        entity.setCommitSha(run.getCommitSha());
        entity.setContentChecksumSha256(computedSha);
        entity.setArchiveSizeBytes(writtenBytes);
        entity.setExtractedBytes(extractedBytes);
        entity.setFileCount(fileCount);
        entity.setStorageToken(storageToken);
        entity.setStatus(OciHandoffStatus.AVAILABLE);
        entity.setExpiresAt(expiresAt);
        OciSourceHandoffEntity saved = handoffRepository.saveAndFlush(entity);

        return new WorkerHandoffUploadResponse(
                saved.getId(),
                saved.getHandoffType(),
                saved.getContentChecksumSha256(),
                saved.getArchiveSizeBytes(),
                saved.getExtractedBytes(),
                saved.getFileCount(),
                saved.getExpiresAt()
        );
    }

    private LockedContext lockAndValidateActiveAttempt(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration,
            WorkerAdapterType requiredAdapter,
            AllowedWorkerOperation requiredOperation
    ) {
        StageExecutionAttemptRepository.AttemptLockIds lockIds = attemptRepository.findLockIdsById(attemptId)
                .filter(ids -> workerId.equals(ids.getWorkerId()))
                .orElseThrow(() -> new ForbiddenOperationException("Execution attempt is unavailable for this worker"));

        // Consistent lock order: Worker -> Run -> Stage -> Attempt
        WorkerNodeEntity node = workerNodeRepository.findByWorkerIdForUpdate(workerId)
                .filter(n -> n.getStatus() != WorkerStatus.OFFLINE && n.getAdapterType() == requiredAdapter)
                .orElseThrow(() -> new ForbiddenOperationException("Active " + requiredAdapter + " worker is required"));

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
                || attempt.getCancellationRequestedAt() != null
                || stage.getCancellationRequestedAt() != null
                || run.getCancellationRequestedAt() != null
                || !requiredOperation.name().equals(attempt.getAllowedOperation())
                || !requiredOperation.name().equals(stage.getAllowedOperation())
                || attempt.getLeaseExpiresAt() == null || !attempt.getLeaseExpiresAt().isAfter(now)
                || stage.getLeaseExpiresAt() == null || !stage.getLeaseExpiresAt().isAfter(now)
                || !leaseToken.equals(attempt.getLeaseToken())
                || !leaseToken.equals(stage.getActiveLeaseToken())
                || fencingGeneration != attempt.getFencingGeneration()
                || fencingGeneration != stage.getFencingGeneration()) {
            throw new ConflictException("Active unexpired lease and matching fencing generation are required");
        }

        return new LockedContext(node, run, stage, attempt);
    }

    private void requireOciEnabled() {
        if (!workerProperties.isEnabled() || !ociProperties.isEnabled()) {
            throw new ForbiddenOperationException("OCI container build handoff is disabled in control-plane configuration");
        }
    }

    private static String validateSha256(String raw) {
        if (raw == null) {
            throw new BadRequestException("Content SHA-256 checksum header is required");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches()) {
            throw new BadRequestException("Invalid SHA-256 content checksum format");
        }
        return normalized;
    }

    private Path resolveStorageFile(String storageToken) {
        if (storageToken == null || !SAFE_STORAGE_TOKEN.matcher(storageToken).matches()) {
            throw new IllegalArgumentException("Invalid handoff storage token");
        }
        Path root = Path.of(ociProperties.getHandoffStorageDir()).toAbsolutePath().normalize();
        Path resolved = root.resolve(storageToken).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Storage path escape rejected");
        }
        return resolved;
    }

    private void deleteStorageFileQuietly(String storageToken) {
        try {
            if (storageToken != null && SAFE_STORAGE_TOKEN.matcher(storageToken).matches()) {
                Files.deleteIfExists(resolveStorageFile(storageToken));
            }
        } catch (Exception ignored) {
        }
    }

    private record LockedContext(
            WorkerNodeEntity worker,
            PipelineRunEntity run,
            PipelineStageRunEntity stage,
            StageExecutionAttemptEntity attempt
    ) {
    }

    public record HandoffDownload(
            UUID handoffId,
            OciHandoffType handoffType,
            long githubRepositoryId,
            String repositoryFullName,
            String commitSha,
            String contentChecksumSha256,
            long archiveSizeBytes,
            Path file
    ) {
    }
}
