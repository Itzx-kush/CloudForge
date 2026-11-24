package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import com.cloudforge.backend.worker.entity.AttemptFailureCategory;
import com.cloudforge.backend.worker.entity.AttemptStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduling and worker-assignment contract bridging CloudForge's Phase 3.2
 * orchestration engine with Phase 3.3 isolated build workers.
 *
 * <p>Preserves full Phase 3.2 compatibility while extending descriptors for
 * fenced leasing, allowlisted operation dispatch, and verified attempt outcomes.
 * Never exposed to ordinary workspace end-users.</p>
 */
public interface WorkerAssignmentContract {

    /**
     * Returns currently {@code READY} stage tickets eligible for isolated worker claiming
     * for a given workspace run. Read-only; never mutates stage state.
     */
    List<StageDispatchDescriptor> listReadyStageTickets(UUID workspaceId, UUID runId);

    /**
     * Immutable work descriptor derived strictly from persisted execution snapshots.
     * Deliberately excludes secrets and arbitrary shell commands.
     */
    record StageDispatchDescriptor(
            UUID workspaceId,
            UUID projectId,
            UUID pipelineRunId,
            UUID stageRunId,
            String stageKey,
            String displayName,
            PipelineStageType stageType,
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

    /**
     * Phase 3.3 / 3.4 fenced lease ticket handed to an authenticated trusted worker upon atomic claim.
     * Deliberately excludes plaintext secrets; workers holding active leases must fetch secrets
     * separately through the lease-bound internal delivery endpoint when {@code hasSecretBindings} is true.
     */
    record LeasedStageWork(
            StageDispatchDescriptor stage,
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
            List<String> secretAliases,
            boolean sourceHandoffRequired
    ) {
        public LeasedStageWork(
                StageDispatchDescriptor stage,
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
                    hasSecretBindings,
                    secretAliases,
                    false
            );
        }

        public LeasedStageWork(
                StageDispatchDescriptor stage,
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
                    List.of(),
                    false
            );
        }
    }

    /**
     * Phase 3.2 base outcome record (preserved for compatibility).
     */
    record WorkerExecutionOutcome(
            UUID stageRunId,
            String workerId,
            int attemptNumber,
            PipelineStageStatus terminalStatus,
            String summary,
            Instant completedAt
    ) {
    }

    /**
     * Phase 3.3 authenticated, fenced attempt execution result submitted by a trusted worker.
     */
    record AuthenticatedAttemptResult(
            UUID attemptId,
            UUID pipelineRunId,
            UUID stageRunId,
            String workerId,
            int attemptNumber,
            String leaseToken,
            long fencingGeneration,
            AttemptStatus resultStatus,
            AttemptFailureCategory failureCategory,
            Integer exitCode,
            String summary,
            Map<String, Object> resourceUsage,
            Instant startedAt,
            Instant completedAt,
            boolean terminationConfirmed
    ) {
    }
}
