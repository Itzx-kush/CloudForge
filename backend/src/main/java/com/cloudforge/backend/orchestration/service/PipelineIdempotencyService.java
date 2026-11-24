package com.cloudforge.backend.orchestration.service;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.orchestration.entity.PipelineIdempotencyKeyEntity;
import com.cloudforge.backend.orchestration.repository.PipelineIdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-backed, restart-safe idempotency manager scoped by
 * {@code (workspaceId, actorUserId, operation, idempotencyKey)}.
 *
 * <p>Uses atomic {@code INSERT ... ON CONFLICT DO NOTHING} plus SHA-256 request
 * fingerprinting so repeated requests return the existing pipeline run while
 * conflicting payloads sharing a key fail cleanly with {@code 409 Conflict}.</p>
 */
@Service
public class PipelineIdempotencyService {

    public static final String OPERATION_CREATE_RUN = "CREATE_PIPELINE_RUN";
    public static final Duration KEY_RETENTION_TTL = Duration.ofHours(24);
    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final PipelineIdempotencyKeyRepository repository;

    public PipelineIdempotencyService(PipelineIdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    public String normalizeAndValidateKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            return null;
        }
        String trimmed = rawKey.trim();
        if (!SAFE_KEY_PATTERN.matcher(trimmed).matches()) {
            throw new BadRequestException(
                    "Idempotency-Key must be 1-128 characters containing only letters, digits, dots, underscores, colons, or hyphens"
            );
        }
        return trimmed;
    }

    public String computeRunRequestFingerprint(
            UUID workspaceId,
            UUID definitionId,
            String targetBranch,
            String commitSha,
            String triggerType
    ) {
        String canonical = workspaceId + "|"
                + definitionId + "|"
                + (targetBranch == null ? "" : targetBranch.trim()) + "|"
                + (commitSha == null ? "" : commitSha.trim().toLowerCase()) + "|"
                + (triggerType == null ? "" : triggerType.trim().toUpperCase());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    public ClaimOutcome claimOrResolve(
            UUID workspaceId,
            UUID actorUserId,
            String operation,
            String normalizedKey,
            String requestFingerprint
    ) {
        Instant now = Instant.now();
        // Reclaim expired key slots cleanly before attempting atomic claim.
        repository.deleteExpiredKey(workspaceId, actorUserId, operation, normalizedKey, now);

        UUID newRecordId = UUID.randomUUID();
        Instant expiresAt = now.plus(KEY_RETENTION_TTL);

        int claimed = repository.claimKey(
                newRecordId,
                workspaceId,
                actorUserId,
                operation,
                normalizedKey,
                requestFingerprint,
                expiresAt,
                now,
                now
        );

        if (claimed == 1) {
            return ClaimOutcome.claimed(newRecordId);
        }

        PipelineIdempotencyKeyEntity existing = repository
                .findByWorkspaceIdAndActorIdAndOperationAndIdempotencyKey(
                        workspaceId, actorUserId, operation, normalizedKey
                )
                .orElseThrow(() -> new ConflictException(
                        "Concurrent idempotency conflict detected; retry request with the same Idempotency-Key"
                ));

        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            throw new ConflictException(
                    "Idempotency-Key \"" + normalizedKey + "\" was already used with a different pipeline run payload"
            );
        }

        if (existing.getPipelineRun() == null) {
            throw new ConflictException(
                    "A pipeline run request with Idempotency-Key \"" + normalizedKey + "\" is still being finalized; retry shortly"
            );
        }

        return ClaimOutcome.replay(existing.getPipelineRun().getId());
    }

    public void attachRun(UUID idempotencyRecordId, UUID pipelineRunId) {
        if (idempotencyRecordId != null && pipelineRunId != null) {
            repository.attachRunId(idempotencyRecordId, pipelineRunId, Instant.now());
        }
    }

    public record ClaimOutcome(boolean replay, UUID claimedRecordId, UUID existingRunId) {
        static ClaimOutcome claimed(UUID recordId) {
            return new ClaimOutcome(false, recordId, null);
        }

        static ClaimOutcome replay(UUID runId) {
            return new ClaimOutcome(true, null, runId);
        }
    }
}
