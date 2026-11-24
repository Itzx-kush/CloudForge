package com.cloudforge.worker.client;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticated internal control-plane client interface implemented over HTTP
 * ({@link HttpControlPlaneClient}) and mockable in unit tests.
 */
public interface ControlPlaneClient {

    void register(RegistrationPayload payload);

    HeartbeatResult heartbeat(String workerId, List<ActiveLease> activeLeases);

    Optional<ClaimedStageLease> claimNextStage(String workerId);

    void acknowledgeRunning(String workerId, UUID attemptId, String leaseToken, long fencingGeneration, Instant startedAt);

    default SecretDeliveryResult fetchAttemptSecrets(
            String workerId,
            UUID attemptId,
            String leaseToken,
            long fencingGeneration
    ) {
        throw new UnsupportedOperationException("Worker secret delivery is not supported by this client");
    }

    default URI fetchSourceArchive(String workerId, UUID attemptId, String leaseToken, long fencingGeneration) {
        throw new UnsupportedOperationException("GitHub source acquisition is unavailable");
    }

    void appendLogs(String workerId, UUID attemptId, String leaseToken, long fencingGeneration, List<LogEntry> entries);

    void completeAttempt(String workerId, UUID attemptId, CompletionPayload payload);

    void updateStatus(String workerId, String status);

    record RegistrationPayload(
            String workerId,
            String displayName,
            String adapterType,
            List<String> supportedOperations,
            int maxConcurrency,
            String hostLabel,
            String agentVersion
    ) {
    }

    record ActiveLease(UUID attemptId, String leaseToken, long fencingGeneration) {
    }

    record HeartbeatResult(String workerId, String status, Instant serverTime, List<LeaseDirective> directives) {
    }

    record LeaseDirective(
            UUID attemptId,
            UUID stageRunId,
            boolean leaseValid,
            Instant leaseExpiresAt,
            boolean cancelRequested,
            Instant terminationDeadlineAt,
            String message
    ) {
    }

    record StageInfo(
            UUID workspaceId,
            UUID projectId,
            UUID pipelineRunId,
            UUID stageRunId,
            String stageKey,
            String displayName,
            String stageType,
            int executionOrder,
            int definitionVersion,
            String repositoryFullName,
            String targetBranch,
            String commitSha,
            int timeoutSeconds,
            int maxRetries,
            int attemptNumber
    ) {
    }

    record ClaimedStageLease(
            StageInfo stage,
            UUID attemptId,
            String workerId,
            String leaseToken,
            long fencingGeneration,
            String allowedOperation,
            boolean idempotentOperation,
            Instant leaseStartedAt,
            Instant leaseExpiresAt,
            int heartbeatIntervalSeconds,
            int maxLogLines,
            int maxLogBytes,
            boolean hasSecretBindings,
            List<String> secretAliases
    ) {
        public ClaimedStageLease(
                StageInfo stage,
                UUID attemptId,
                String workerId,
                String leaseToken,
                long fencingGeneration,
                String allowedOperation,
                boolean idempotentOperation,
                Instant leaseStartedAt,
                Instant leaseExpiresAt,
                int heartbeatIntervalSeconds,
                int maxLogLines,
                int maxLogBytes
        ) {
            this(
                    stage,
                    attemptId,
                    workerId,
                    leaseToken,
                    fencingGeneration,
                    allowedOperation,
                    idempotentOperation,
                    leaseStartedAt,
                    leaseExpiresAt,
                    heartbeatIntervalSeconds,
                    maxLogLines,
                    maxLogBytes,
                    false,
                    List.of()
            );
        }
    }

    record DeliveredSecret(
            String alias,
            String secretName,
            UUID secretId,
            UUID secretVersionId,
            int versionNumber,
            String value
    ) {
        @Override
        public String toString() {
            return "DeliveredSecret[alias=" + alias + ", secretId=" + secretId
                    + ", versionNumber=" + versionNumber + ", value=[REDACTED]]";
        }
    }

    record SecretDeliveryResult(
            UUID attemptId,
            UUID pipelineRunId,
            UUID stageRunId,
            long fencingGeneration,
            Instant deliveredAt,
            List<DeliveredSecret> secrets
    ) {
        @Override
        public String toString() {
            return "SecretDeliveryResult[attemptId=" + attemptId
                    + ", count=" + (secrets == null ? 0 : secrets.size()) + ", secrets=[REDACTED]]";
        }
    }

    record LogEntry(String streamType, String message, Instant occurredAt) {
    }

    record CompletionPayload(
            UUID pipelineRunId,
            UUID stageRunId,
            int attemptNumber,
            String leaseToken,
            long fencingGeneration,
            String resultStatus,
            String failureCategory,
            Integer exitCode,
            String summary,
            Map<String, Object> resourceUsage,
            Instant startedAt,
            Instant completedAt,
            boolean terminationConfirmed
    ) {
    }
}
