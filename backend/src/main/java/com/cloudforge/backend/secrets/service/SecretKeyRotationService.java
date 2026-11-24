package com.cloudforge.backend.secrets.service;

import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.secrets.crypto.SecretCryptoException;
import com.cloudforge.backend.secrets.crypto.SecretCryptoService;
import com.cloudforge.backend.secrets.crypto.SecretKeyProvider;
import com.cloudforge.backend.secrets.crypto.SecretManagementUnavailableException;
import com.cloudforge.backend.secrets.dto.KeyRotationRunDto;
import com.cloudforge.backend.secrets.dto.KeyUsageSummaryDto;
import com.cloudforge.backend.secrets.entity.EnvironmentSecretVersionEntity;
import com.cloudforge.backend.secrets.entity.KeyRotationRunStatus;
import com.cloudforge.backend.secrets.entity.SecretKeyRotationRunEntity;
import com.cloudforge.backend.secrets.repository.EnvironmentSecretVersionRepository;
import com.cloudforge.backend.secrets.repository.SecretKeyRotationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operator service for master Key-Encryption Key (KEK) inspection and resumable,
 * idempotent, DEK-only batch rewrapping across historical secret versions.
 * Never decrypts secret ciphertexts or emits key material.
 */
@Service
public class SecretKeyRotationService {

    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_BATCH_SIZE = 200;
    private static final Logger log = LoggerFactory.getLogger(SecretKeyRotationService.class);

    private final SecretKeyProvider keyProvider;
    private final SecretCryptoService cryptoService;
    private final EnvironmentSecretVersionRepository versionRepository;
    private final SecretKeyRotationRunRepository rotationRunRepository;
    private final SecretAuditService secretAuditService;
    private final TransactionTemplate transactionTemplate;
    private final Object rotationMutex = new Object();

    public SecretKeyRotationService(
            SecretKeyProvider keyProvider,
            SecretCryptoService cryptoService,
            EnvironmentSecretVersionRepository versionRepository,
            SecretKeyRotationRunRepository rotationRunRepository,
            SecretAuditService secretAuditService,
            PlatformTransactionManager transactionManager
    ) {
        this.keyProvider = keyProvider;
        this.cryptoService = cryptoService;
        this.versionRepository = versionRepository;
        this.rotationRunRepository = rotationRunRepository;
        this.secretAuditService = secretAuditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public KeyUsageSummaryDto getKeyUsageSummary() {
        String activeKeyId = keyProvider.isAvailable() ? keyProvider.getActiveKeyId() : null;
        Map<String, Long> breakdown = new LinkedHashMap<>();
        long totalVersions = 0L;
        for (Object[] row : versionRepository.countVersionsByKekId()) {
            String kekId = (String) row[0];
            long count = ((Number) row[1]).longValue();
            breakdown.put(kekId, count);
            totalVersions += count;
        }
        long needingRotation = activeKeyId != null ? versionRepository.countByKekIdNot(activeKeyId) : 0L;
        boolean running = rotationRunRepository.existsByStatus(KeyRotationRunStatus.RUNNING);
        List<KeyRotationRunDto> recentRuns = rotationRunRepository.findTop20ByOrderByStartedAtDesc().stream()
                .map(run -> toDto(run, false))
                .toList();

        return new KeyUsageSummaryDto(
                keyProvider.isEnabled(),
                keyProvider.isAvailable(),
                keyProvider.getProviderType(),
                activeKeyId,
                keyProvider.listConfiguredKeyIds().stream().sorted().toList(),
                totalVersions,
                needingRotation,
                breakdown,
                running,
                recentRuns,
                keyProvider.getStatusDiagnostic()
        );
    }

    /**
     * Rewraps wrapped DEKs under the active KEK in bounded batches without decrypting
     * underlying secret ciphertexts. Fails closed on individual rows if historical KEKs
     * are missing from the configured keyring.
     */
    public KeyRotationRunDto rotateMasterKey(Integer requestedBatchSize, boolean dryRun, String initiatedBy) {
        if (!keyProvider.isEnabled() || !keyProvider.isAvailable()) {
            throw new SecretManagementUnavailableException(keyProvider.getStatusDiagnostic());
        }

        String targetKekId = keyProvider.getActiveKeyId();
        int batchSize = requestedBatchSize == null
                ? DEFAULT_BATCH_SIZE
                : Math.min(Math.max(requestedBatchSize, 1), MAX_BATCH_SIZE);
        String actorName = StringUtils.hasText(initiatedBy) ? initiatedBy.trim() : "operator-cli";
        if (actorName.length() > 120) {
            actorName = actorName.substring(0, 120);
        }

        if (dryRun) {
            int candidates = (int) versionRepository.countByKekIdNot(targetKekId);
            return new KeyRotationRunDto(
                    null,
                    targetKekId,
                    candidates == 0 ? KeyRotationRunStatus.SUCCEEDED : KeyRotationRunStatus.RUNNING,
                    candidates,
                    0,
                    0,
                    0,
                    0,
                    null,
                    actorName,
                    null,
                    true,
                    Instant.now(),
                    candidates == 0 ? Instant.now() : null
            );
        }

        synchronized (rotationMutex) {
            UUID runId = initializeRotationRun(targetKekId, actorName);
            int processed = 0;
            int rewrapped = 0;
            int skipped = 0;
            int failed = 0;
            UUID cursor = null;
            String firstFailureMessage = null;

            while (true) {
                BatchResult batchResult = processBatch(runId, targetKekId, batchSize, cursor);
                processed += batchResult.processed();
                rewrapped += batchResult.rewrapped();
                skipped += batchResult.skipped();
                failed += batchResult.failed();
                if (firstFailureMessage == null && batchResult.firstFailureMessage() != null) {
                    firstFailureMessage = batchResult.firstFailureMessage();
                }
                if (batchResult.lastSeenId() == null || batchResult.candidateCount() < batchSize) {
                    cursor = batchResult.lastSeenId();
                    break;
                }
                cursor = batchResult.lastSeenId();
            }

            KeyRotationRunStatus finalStatus = failed > 0
                    ? KeyRotationRunStatus.COMPLETED_WITH_ERRORS
                    : KeyRotationRunStatus.SUCCEEDED;
            SecretKeyRotationRunEntity completedRun = finalizeRotationRun(
                    runId, finalStatus, processed, rewrapped, skipped, failed, cursor, firstFailureMessage
            );

            log.info(
                    "Completed master key rotation runId={} targetKekId={} status={} processed={} rewrapped={} skipped={} failed={}",
                    completedRun.getId(),
                    targetKekId,
                    finalStatus,
                    processed,
                    rewrapped,
                    skipped,
                    failed
            );
            return toDto(completedRun, false);
        }
    }

    private UUID initializeRotationRun(String targetKekId, String initiatedBy) {
        return transactionTemplate.execute(status -> {
            if (rotationRunRepository.existsByStatus(KeyRotationRunStatus.RUNNING)) {
                throw new ConflictException("A master key rotation run is already in progress");
            }
            int candidates = (int) versionRepository.countByKekIdNot(targetKekId);
            SecretKeyRotationRunEntity run = new SecretKeyRotationRunEntity();
            run.setTargetKekId(targetKekId);
            run.setStatus(KeyRotationRunStatus.RUNNING);
            run.setTotalCandidates(candidates);
            run.setInitiatedBy(initiatedBy);
            run.setStartedAt(Instant.now());
            SecretKeyRotationRunEntity saved = rotationRunRepository.save(run);
            return saved.getId();
        });
    }

    private BatchResult processBatch(UUID runId, String targetKekId, int batchSize, UUID afterId) {
        return transactionTemplate.execute(status -> {
            PageRequest pageRequest = PageRequest.of(0, batchSize);
            List<UUID> candidateIds = afterId == null
                    ? versionRepository.findVersionIdsNeedingKeyRewrap(targetKekId, pageRequest)
                    : versionRepository.findVersionIdsNeedingKeyRewrapAfter(targetKekId, afterId, pageRequest);

            int processed = 0;
            int rewrapped = 0;
            int skipped = 0;
            int failed = 0;
            UUID lastSeenId = afterId;
            String firstError = null;

            for (UUID versionId : candidateIds) {
                lastSeenId = versionId;
                processed++;
                EnvironmentSecretVersionEntity version = versionRepository.findByIdForUpdate(versionId).orElse(null);
                if (version == null) {
                    skipped++;
                    continue;
                }
                if (targetKekId.equals(version.getKekId())) {
                    skipped++;
                    continue;
                }

                try {
                    SecretCryptoService.SecretContext context = new SecretCryptoService.SecretContext(
                            version.getWorkspace().getId(),
                            version.getProject().getId(),
                            version.getEnvironment().getId(),
                            version.getSecret().getId(),
                            version.getVersionNumber()
                    );
                    SecretKeyProvider.WrappedDek rewrappedDek = cryptoService.rewrapDataEncryptionKey(
                            version.getKekId(),
                            version.getWrappedDek(),
                            version.getEncryptionFormatVersion(),
                            context
                    );
                    String previousKekId = version.getKekId();
                    version.setWrappedDek(rewrappedDek.wrappedKeyBytes());
                    version.setKekId(rewrappedDek.keyId());
                    versionRepository.save(version);
                    rewrapped++;

                    secretAuditService.recordSecretEvent(
                            version.getWorkspace(),
                            null,
                            SecretAuditService.ACTION_SECRET_KEY_REWRAPPED,
                            "ENVIRONMENT_SECRET_VERSION",
                            version.getId().toString(),
                            "Rewrapped DEK for secret \"" + version.getSecret().getSecretName()
                                    + "\" v" + version.getVersionNumber()
                                    + " from key " + previousKekId + " to " + rewrappedDek.keyId(),
                            Map.of(
                                    "entryId", version.getSecret().getId().toString(),
                                    "versionNumber", version.getVersionNumber(),
                                    "fromKekId", previousKekId,
                                    "toKekId", rewrappedDek.keyId()
                            )
                    );
                } catch (SecretCryptoException | SecretManagementUnavailableException ex) {
                    failed++;
                    if (firstError == null) {
                        firstError = truncate(ex.getMessage(), 480);
                    }
                    log.warn(
                            "Failed closed while rewrapping secret versionId={} currentKekId={}: {}",
                            version.getId(),
                            version.getKekId(),
                            ex.getMessage()
                    );
                }
            }

            SecretKeyRotationRunEntity run = rotationRunRepository.findByIdForUpdate(runId).orElseThrow();
            run.setProcessedCount(run.getProcessedCount() + processed);
            run.setRewrappedCount(run.getRewrappedCount() + rewrapped);
            run.setSkippedCount(run.getSkippedCount() + skipped);
            run.setFailedCount(run.getFailedCount() + failed);
            run.setLastProcessedVersionId(lastSeenId);
            rotationRunRepository.save(run);

            return new BatchResult(candidateIds.size(), processed, rewrapped, skipped, failed, lastSeenId, firstError);
        });
    }

    private SecretKeyRotationRunEntity finalizeRotationRun(
            UUID runId,
            KeyRotationRunStatus finalStatus,
            int processed,
            int rewrapped,
            int skipped,
            int failed,
            UUID cursor,
            String errorSummary
    ) {
        return transactionTemplate.execute(status -> {
            SecretKeyRotationRunEntity run = rotationRunRepository.findByIdForUpdate(runId).orElseThrow();
            run.setStatus(finalStatus);
            run.setProcessedCount(processed);
            run.setRewrappedCount(rewrapped);
            run.setSkippedCount(skipped);
            run.setFailedCount(failed);
            run.setLastProcessedVersionId(cursor);
            run.setErrorSummary(errorSummary);
            run.setCompletedAt(Instant.now());
            SecretKeyRotationRunEntity saved = rotationRunRepository.save(run);

            secretAuditService.recordSecretEvent(
                    null,
                    null,
                    SecretAuditService.ACTION_SECRET_KEY_ROTATION_RUN,
                    "SECRET_KEY_ROTATION_RUN",
                    saved.getId().toString(),
                    "Master key rotation run finished with status " + finalStatus
                            + " (target=" + saved.getTargetKekId()
                            + ", rewrapped=" + rewrapped
                            + ", skipped=" + skipped
                            + ", failed=" + failed + ")",
                    Map.of(
                            "targetKekId", saved.getTargetKekId(),
                            "status", finalStatus.name(),
                            "rewrappedCount", rewrapped,
                            "skippedCount", skipped,
                            "failedCount", failed
                    )
            );
            return saved;
        });
    }

    private KeyRotationRunDto toDto(SecretKeyRotationRunEntity entity, boolean dryRun) {
        return new KeyRotationRunDto(
                entity.getId(),
                entity.getTargetKekId(),
                entity.getStatus(),
                entity.getTotalCandidates(),
                entity.getProcessedCount(),
                entity.getRewrappedCount(),
                entity.getSkippedCount(),
                entity.getFailedCount(),
                entity.getLastProcessedVersionId(),
                entity.getInitiatedBy(),
                entity.getErrorSummary(),
                dryRun,
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private record BatchResult(
            int candidateCount,
            int processed,
            int rewrapped,
            int skipped,
            int failed,
            UUID lastSeenId,
            String firstFailureMessage
    ) {
    }
}
