package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Explicit scheduling and worker-assignment contract preparing CloudForge Phase 3.2
 * for Phase 3.3 isolated build workers.
 *
 * <p><strong>Phase 3.2 Boundary:</strong> No live worker implementation or HTTP completion
 * endpoint is active in this milestone. Stage runs remain in {@code READY}/{@code PENDING}
 * until an authenticated, trusted Phase 3.3 isolated worker executor implements this contract.</p>
 */
public interface WorkerAssignmentContract {

    /**
     * Returns currently {@code READY} stage tickets eligible for future isolated worker claiming
     * for a given workspace run. Read-only in Phase 3.2; never transitions stages automatically.
     */
    List<StageDispatchDescriptor> listReadyStageTickets(UUID workspaceId, UUID runId);

    /**
     * Immutable work descriptor handed to a future Phase 3.3 isolated worker.
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
     * Contract for how a future authenticated Phase 3.3 worker will report verified stage outcomes.
     * Not exposed to workspace end-users via REST.
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
}
